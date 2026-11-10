package com.example.frauddetection.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.CsvTransactionReader;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.data.TrainingDataException;
import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;
import com.example.frauddetection.features.FeatureScaler;
import com.example.frauddetection.features.TensorEncoder;

import deepnetts.data.DataSetItem;
import deepnetts.data.MLDataItem;
import deepnetts.data.TabularDataSet;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.activation.ActivationType;
import deepnetts.net.loss.LossType;
import deepnetts.net.train.BackpropagationTrainer;
import deepnetts.net.train.opt.OptimizerType;
import deepnetts.tensor.Tensor1D;

/**
 * Trains a feed-forward network (ReLU hidden layers, sigmoid output, binary cross-entropy)
 * with Deep Netts.
 * <p>
 * The validation split chooses the recommended threshold and the test split gives the final metrics.
 * All three splits keep the natural fraud rate: the data is imbalanced and is not rebalanced.
 */
@Component
public class FraudModelTrainer {

    private static final Logger log = LoggerFactory.getLogger(FraudModelTrainer.class);

    private final double threshold;
    private final FraudProperties.Data dataSource;

    public FraudModelTrainer(FraudProperties properties) {
        this.threshold = properties.model().threshold();
        this.dataSource = properties.data();
    }

    /** Trains on the configured data source. */
    public FraudModel train(Training cfg) {
        return train(loadData(cfg), cfg);
    }

    public List<LabeledTransaction> loadData(Training cfg) {
        return switch (dataSource.source()) {
            case CSV -> CsvTransactionReader.read(dataSource.csvPath());
            case SYNTHETIC -> new SyntheticTransactionGenerator(cfg.seed()).generate(cfg.samples(), cfg.fraudRate());
        };
    }

    public FraudModel train(List<LabeledTransaction> data, Training cfg) {
        return trainWithTestSet(data, cfg).model();
    }

    /** A trained model and the test split it was evaluated on, so that another model can be scored on the same rows. */
    public record TrainingRun(FraudModel model, List<LabeledTransaction> testSet) {
    }

    public TrainingRun trainWithTestSet(List<LabeledTransaction> data, Training cfg) {
        Split split = split(data, cfg);
        requireBothClasses("training", split.train(), data);
        requireBothClasses("validation", split.validation(), data);
        requireBothClasses("test", split.test(), data);
        List<LabeledTransaction> train = split.train();

        // the scaling is fitted on the training split only, then the same encoder serves training, evaluation and the API
        FeatureScaler scaler = FeatureScaler.fit(train.stream().map(t -> FraudModel.FEATURES.toTensor(t.transaction())).toList());
        TabularDataSet<MLDataItem> trainingSet = toDataSet(train, FraudModel.encoder(scaler));

        FeedForwardNetwork network = buildNetwork(cfg.hiddenLayers(), cfg.seed());
        BackpropagationTrainer trainer = network.getTrainer();
        trainer.setOptimizer(OptimizerType.ADAM)
                .setLearningRate(cfg.learningRate())
                .setL2Regularization(cfg.l2Regularization())
                .setStopEpochs(cfg.maxEpochs())
                .setStopError(1e-4f)
                .setShuffle(true);

        log.info("Training fraud model on {} examples, {} features, hidden layers {}",
                train.size(), FeatureExtractor.NUM_FEATURES, cfg.hiddenLayers());
        long start = System.nanoTime();
        network.train(trainingSet);
        double seconds = (System.nanoTime() - start) / 1e9;

        ModelMetadata provisional = new ModelMetadata(Instant.now(), FeatureExtractor.FEATURE_NAMES, scaler,
                cfg.hiddenLayers(), trainingSet.size(), trainer.getCurrentEpoch(), seconds, null);
        FraudModel model = new FraudModel(network, provisional);
        double recommendedThreshold = ModelEvaluator.recommendThreshold(scores(model, split.validation()),
                labels(split.validation()));
        EvaluationReport report = ModelEvaluator.evaluate(scores(model, split.test()), labels(split.test()), threshold,
                recommendedThreshold);
        log.info("Trained in {}s. Test ROC AUC {}, PR AUC {}, precision {}, recall {} at threshold {}, recommended threshold {}",
                String.format(Locale.ROOT, "%.1f", seconds), fmt(report.rocAuc()), fmt(report.prAuc()),
                fmt(report.precision()), fmt(report.recall()), threshold, recommendedThreshold);

        FraudModel trained = new FraudModel(network, new ModelMetadata(provisional.trainedAt(), provisional.featureNames(),
                scaler, cfg.hiddenLayers(), trainingSet.size(), provisional.epochs(), seconds, report));
        return new TrainingRun(trained, split.test());
    }

    /**
     * Stratified, so all three parts have the fraud rate of the data. It is shuffled first because
     * real data is often sorted, by date for example. This random split suits the synthetic data, where
     * rows are independent. Real transactions may need a time-based or customer-aware split instead.
     */
    static Split split(List<LabeledTransaction> data, Training cfg) {
        List<LabeledTransaction> shuffled = new ArrayList<>(data);
        Collections.shuffle(shuffled, new Random(cfg.seed()));

        List<LabeledTransaction> train = new ArrayList<>();
        List<LabeledTransaction> validation = new ArrayList<>();
        List<LabeledTransaction> test = new ArrayList<>();
        for (boolean label : new boolean[] {true, false}) {
            List<LabeledTransaction> group = shuffled.stream().filter(t -> t.fraud() == label).toList();
            int testEnd = (int) Math.round(group.size() * cfg.testFraction());
            int validationEnd = testEnd + (int) Math.round(group.size() * cfg.validationFraction());
            test.addAll(group.subList(0, testEnd));
            validation.addAll(group.subList(testEnd, validationEnd));
            train.addAll(group.subList(validationEnd, group.size()));
        }
        return new Split(train, validation, test);
    }

    /** Without both classes in every part, metrics like ROC AUC aren't defined. */
    private static void requireBothClasses(String part, List<LabeledTransaction> rows, List<LabeledTransaction> data) {
        long frauds = rows.stream().filter(LabeledTransaction::fraud).count();
        if (frauds == 0 || frauds == rows.size()) {
            long totalFrauds = data.stream().filter(LabeledTransaction::fraud).count();
            throw new TrainingDataException("Not enough data to train and evaluate a model: the " + part
                    + " split needs both fraud and legitimate rows. The data has " + data.size() + " rows, "
                    + totalFrauds + " of them fraud.");
        }
    }

    static float[] scores(FraudModel model, List<LabeledTransaction> transactions) {
        float[] scores = new float[transactions.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = model.predict(transactions.get(i).transaction());
        }
        return scores;
    }

    static boolean[] labels(List<LabeledTransaction> transactions) {
        boolean[] labels = new boolean[transactions.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = transactions.get(i).fraud();
        }
        return labels;
    }

    static FeedForwardNetwork buildNetwork(List<Integer> hiddenLayers, long seed) {
        FeedForwardNetwork.Builder builder = FeedForwardNetwork.builder()
                .addInputLayer(FeatureExtractor.NUM_FEATURES);
        for (int neurons : hiddenLayers) {
            builder.addFullyConnectedLayer(neurons, ActivationType.RELU);
        }
        return builder
                .addOutputLayer(1, ActivationType.SIGMOID)
                .lossFunction(LossType.CROSS_ENTROPY)
                .randomSeed(seed)
                .build();
    }

    /** The training split at its natural fraud rate: fraud rows are not duplicated or reweighted. */
    private static TabularDataSet<MLDataItem> toDataSet(List<LabeledTransaction> train,
            TensorEncoder<Transaction, Tensor1D> encoder) {
        TabularDataSet<MLDataItem> dataSet = new TabularDataSet<>(FeatureExtractor.NUM_FEATURES, 1);
        List<String> columns = new ArrayList<>(FeatureExtractor.FEATURE_NAMES);
        columns.add("fraud");
        dataSet.setColumnNames(columns.toArray(String[]::new));

        for (LabeledTransaction t : train) {
            // float values: new Tensor1D(int) would create an empty tensor of that size
            dataSet.add(new DataSetItem(encoder.toTensor(t.transaction()), new Tensor1D(t.fraud() ? 1f : 0f)));
        }
        dataSet.shuffle();
        return dataSet;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    record Split(List<LabeledTransaction> train, List<LabeledTransaction> validation, List<LabeledTransaction> test) {
    }
}
