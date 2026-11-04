package com.example.frauddetection.minimal;

import deepnetts.core.DeepNetts;
import deepnetts.data.DataSetItem;
import deepnetts.data.DataSets;
import deepnetts.data.MLDataItem;
import deepnetts.data.TabularDataSet;
import deepnetts.data.norm.Standardizer;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.activation.ActivationType;
import deepnetts.net.loss.LossType;
import deepnetts.net.train.opt.OptimizerType;
import deepnetts.tensor.Tensor1D;
import deepnetts.util.FileIO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The complete machine learning workflow, one method per step:
 * {@link #loadData} → {@link #train} → {@link #evaluate} → {@link #save} / {@link #load} → {@link #predict}.
 *
 * <p>On startup the saved network is loaded, or a new one is trained and saved if there is none.
 * Either way it is then evaluated on transactions it has never seen.
 */
@Component
public class FraudModel {

    private static final Logger log = LoggerFactory.getLogger(FraudModel.class);

    /** The nine inputs, in CSV column order, followed by the two outputs. */
    static final String[] COLUMNS = {"amount", "customerAvgAmount", "hourOfDay", "distanceFromHomeKm",
            "transactionsLast24h", "failedAttemptsLast24h", "online", "newDevice", "foreign", "legitimate", "fraud"};
    static final int INPUTS = 9;
    /** Position of the fraud output. The network has two outputs, legitimate and fraud, that sum to 1. */
    static final int FRAUD = 1;

    static {
        // the network is tiny, so one thread trains much faster than coordinating several
        DeepNetts.getInstance().setMaxThreads(1);
    }

    private final Path trainingData;
    private final Path testData;
    private final Path modelFile;
    private final double threshold;
    private FeedForwardNetwork network;

    public FraudModel(@Value("${fraud.training-data}") Path trainingData, @Value("${fraud.test-data}") Path testData,
                      @Value("${fraud.model-file}") Path modelFile, @Value("${fraud.threshold}") double threshold) {
        this.trainingData = trainingData;
        this.testData = testData;
        this.modelFile = modelFile;
        this.threshold = threshold;
    }

    @PostConstruct
    void start() throws IOException, ClassNotFoundException {
        if (Files.exists(modelFile)) {
            network = load(modelFile);
            log.info("Loaded fraud model from {}", modelFile.toAbsolutePath());
        } else {
            network = train(loadData(trainingData));
            save(network, modelFile);
            log.info("Saved fraud model to {}", modelFile.toAbsolutePath());
        }
        log.info(evaluate(network, loadData(testData), threshold).toTable());
    }

    // ---- Step 1: data - read labelled transactions from a CSV file ----

    /** Reads the CSV file and turns its 0/1 fraud column into the two outputs the network learns. */
    static TabularDataSet<MLDataItem> loadData(Path csvFile) throws IOException {
        TabularDataSet<MLDataItem> rows = DataSets.readCsv(csvFile.toFile(), INPUTS, 1, true, ",");
        TabularDataSet<MLDataItem> data = new TabularDataSet<>(INPUTS, 2);
        // required: Deep Netts uses the output column names as class labels and fails without them
        data.setColumnNames(COLUMNS);
        for (MLDataItem row : rows) {
            boolean fraud = row.getTargetOutput().getValues()[0] == 1f;
            data.add(new DataSetItem(row.getInput().getValues().clone(), fraud ? new float[]{0, 1} : new float[]{1, 0}));
        }
        return data;
    }

    // ---- Step 2: preprocessing and training ----

    /** Scales the inputs, then trains a network with 9 inputs, one hidden layer and two outputs. */
    static FeedForwardNetwork train(TabularDataSet<MLDataItem> trainingSet) {
        // each input gets mean 0 and standard deviation 1, measured on the training data only
        Standardizer scaler = new Standardizer(trainingSet);
        scaler.apply(trainingSet);

        FeedForwardNetwork network = FeedForwardNetwork.builder()
                .addInputLayer(INPUTS)
                .addFullyConnectedLayer(16, ActivationType.RELU)
                .addOutputLayer(2, ActivationType.SOFTMAX)
                .lossFunction(LossType.CROSS_ENTROPY)
                .randomSeed(42)
                .build();
        network.getTrainer()
                .setOptimizer(OptimizerType.ADAM)
                .setLearningRate(0.001f)
                .setMaxEpochs(30);

        log.info("Training fraud model on {} transactions", trainingSet.size());
        long start = System.nanoTime();
        network.train(trainingSet);
        log.info("Training finished in {} ms", (System.nanoTime() - start) / 1_000_000);

        // from now on the network scales every input itself, and the saved model file includes the scaling
        network.setNormalizer(scaler);
        return network;
    }

    // ---- Step 3: evaluation on transactions the network has never seen ----

    /** Counts right and wrong answers on the test set, flagging a transaction when its score reaches the threshold. */
    static Evaluation evaluate(FeedForwardNetwork network, TabularDataSet<MLDataItem> testSet, double threshold) {
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        int trueNegatives = 0;
        for (MLDataItem row : testSet) {
            boolean flagged = fraudScore(network, row.getInput().getValues()) >= threshold;
            boolean fraud = row.getTargetOutput().getValues()[FRAUD] == 1f;
            if (flagged && fraud) {
                truePositives++;
            } else if (flagged) {
                falsePositives++;
            } else if (fraud) {
                falseNegatives++;
            } else {
                trueNegatives++;
            }
        }
        return new Evaluation(threshold, truePositives, falsePositives, falseNegatives, trueNegatives);
    }

    // ---- Step 4: saving and loading ----

    static void save(FeedForwardNetwork network, Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        FileIO.writeToFile(network, absolute.toString());
    }

    static FeedForwardNetwork load(Path file) throws IOException, ClassNotFoundException {
        return FileIO.createFromFile(file.toFile(), FeedForwardNetwork.class);
    }

    // ---- Step 5: prediction - used by the REST endpoint ----

    public FraudPrediction predict(TransactionRequest transaction) {
        double score = score(toInputs(transaction));
        return new FraudPrediction(score, score >= threshold, threshold);
    }

    /** The request fields as numbers, in the same order as the CSV columns. */
    static float[] toInputs(TransactionRequest t) {
        return new float[]{
                t.amount().floatValue(),
                t.customerAvgAmount().floatValue(),
                t.hourOfDay(),
                t.distanceFromHomeKm().floatValue(),
                t.transactionsLast24h(),
                t.failedAttemptsLast24h(),
                t.online() ? 1 : 0,
                t.newDevice() ? 1 : 0,
                t.foreign() ? 1 : 0};
    }

    /** A Deep Netts network keeps intermediate results in its layers, so one prediction at a time. */
    private synchronized double score(float[] inputs) {
        return fraudScore(network, inputs);
    }

    /** Returns the network's fraud output for unscaled inputs. The network applies its stored scaling itself. */
    static float fraudScore(FeedForwardNetwork network, float[] inputs) {
        // predict(Tensor) also works on a network loaded from a file; predict(float[]) does not in Deep Netts 4.0.4
        return network.predict(Tensor1D.of(inputs.clone())).getValues()[FRAUD];
    }

    /**
     * Results on the test set at one threshold. All results come from synthetic data.
     * Fraud is the positive class: a "positive" is a transaction the model flags.
     */
    public record Evaluation(double threshold, int truePositives, int falsePositives, int falseNegatives,
                             int trueNegatives) {

        int total() {
            return truePositives + falsePositives + falseNegatives + trueNegatives;
        }

        int fraudCases() {
            return truePositives + falseNegatives;
        }

        /** Of the flagged transactions, the share that really is fraud. */
        public double precision() {
            return truePositives / (double) Math.max(1, truePositives + falsePositives);
        }

        /** Of the fraud cases, the share the model flags. */
        public double recall() {
            return truePositives / (double) Math.max(1, fraudCases());
        }

        /** Share of all answers that are right. Misleading on its own, because fraud is rare. */
        public double accuracy() {
            return (truePositives + trueNegatives) / (double) total();
        }

        /** Accuracy of a "model" that flags nothing. */
        public double accuracyWhenFlaggingNothing() {
            return (total() - fraudCases()) / (double) total();
        }

        String toTable() {
            return String.format(Locale.ROOT, """
                    Evaluation on %,d test transactions (%d fraud) that were never used for training, at threshold %.2f.
                    These are results on synthetic data, not real-world performance.
                                          flagged   not flagged
                      fraud             %9d %13d
                      legitimate        %9d %13d
                      Precision %.2f   share of flagged transactions that are fraud
                      Recall    %.2f   share of fraud cases that are flagged
                      Accuracy  %.4f (flagging nothing would score %.4f, so accuracy alone says little)""",
                    total(), fraudCases(), threshold, truePositives, falseNegatives, falsePositives, trueNegatives,
                    precision(), recall(), accuracy(), accuracyWhenFlaggingNothing());
        }
    }
}
