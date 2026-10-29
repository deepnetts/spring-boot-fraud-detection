package com.example.frauddetection.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.features.FeatureExtractor;
import com.example.frauddetection.features.FeatureScaler;

import deepnetts.data.DataSetItem;
import deepnetts.data.MLDataItem;
import deepnetts.data.TabularDataSet;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.activation.ActivationType;
import deepnetts.net.loss.LossType;
import deepnetts.net.train.BackpropagationTrainer;
import deepnetts.net.train.opt.OptimizerType;

/**
 * Trains a feed-forward network (ReLU hidden layers, sigmoid output, binary cross-entropy)
 * with Deep Netts.
 * <p>
 * Fraud is rare, so the training split is rebalanced by oversampling fraud examples while the
 * test split keeps the natural fraud rate for honest evaluation.
 */
@Component
public class FraudModelTrainer {

    private static final Logger log = LoggerFactory.getLogger(FraudModelTrainer.class);

    private final double threshold;

    public FraudModelTrainer(FraudProperties properties) {
        this.threshold = properties.model().threshold();
    }

    public FraudModel train(Training cfg) {
        List<LabeledTransaction> data = new SyntheticTransactionGenerator(cfg.seed())
                .generate(cfg.samples(), cfg.fraudRate());
        return train(data, cfg);
    }

    public FraudModel train(List<LabeledTransaction> data, Training cfg) {
        // stratified split so the test set has the same fraud rate as the data
        List<LabeledTransaction> train = new ArrayList<>();
        List<LabeledTransaction> test = new ArrayList<>();
        for (boolean label : new boolean[] {true, false}) {
            List<LabeledTransaction> group = data.stream().filter(t -> t.fraud() == label).toList();
            int testCount = (int) Math.round(group.size() * cfg.testFraction());
            test.addAll(group.subList(0, testCount));
            train.addAll(group.subList(testCount, group.size()));
        }

        List<float[]> trainFeatures = train.stream().map(t -> FeatureExtractor.extract(t.transaction())).toList();
        FeatureScaler scaler = FeatureScaler.fit(trainFeatures);
        TabularDataSet<MLDataItem> trainingSet = toOversampledDataSet(train, trainFeatures, scaler, cfg.targetFraudShare());

        FeedForwardNetwork network = buildNetwork(cfg.hiddenLayers(), cfg.seed());
        BackpropagationTrainer trainer = network.getTrainer();
        trainer.setOptimizer(OptimizerType.ADAM)
                .setLearningRate(cfg.learningRate())
                .setL2Regularization(cfg.l2Regularization())
                .setStopEpochs(cfg.maxEpochs())
                .setStopError(1e-4f)
                .setShuffle(true);

        log.info("Training fraud model on {} examples ({} after oversampling fraud), {} features, hidden layers {}",
                train.size(), trainingSet.size(), FeatureExtractor.NUM_FEATURES, cfg.hiddenLayers());
        long start = System.nanoTime();
        network.train(trainingSet);
        double seconds = (System.nanoTime() - start) / 1e9;

        ModelMetadata provisional = new ModelMetadata(Instant.now(), FeatureExtractor.FEATURE_NAMES, scaler,
                cfg.hiddenLayers(), trainingSet.size(), trainer.getCurrentEpoch(), seconds, null);
        FraudModel model = new FraudModel(network, provisional);
        EvaluationReport report = evaluate(model, test);
        log.info("Trained in {}s. Test ROC AUC {}, precision {}, recall {} at threshold {}",
                String.format(Locale.ROOT, "%.1f", seconds), fmt(report.rocAuc()), fmt(report.precision()), fmt(report.recall()), threshold);

        return new FraudModel(network, new ModelMetadata(provisional.trainedAt(), provisional.featureNames(), scaler,
                cfg.hiddenLayers(), trainingSet.size(), provisional.epochs(), seconds, report));
    }

    public EvaluationReport evaluate(FraudModel model, List<LabeledTransaction> test) {
        float[] probabilities = new float[test.size()];
        boolean[] labels = new boolean[test.size()];
        for (int i = 0; i < test.size(); i++) {
            probabilities[i] = model.predict(test.get(i).transaction());
            labels[i] = test.get(i).fraud();
        }
        return ModelEvaluator.evaluate(probabilities, labels, threshold);
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

    private static TabularDataSet<MLDataItem> toOversampledDataSet(List<LabeledTransaction> train,
            List<float[]> features, FeatureScaler scaler, double targetFraudShare) {
        long frauds = train.stream().filter(LabeledTransaction::fraud).count();
        long legit = train.size() - frauds;
        // copies of each fraud example needed to reach the target share of fraud in the training set
        int copies = frauds == 0 ? 1
                : (int) Math.max(1, Math.round(targetFraudShare * legit / ((1 - targetFraudShare) * frauds)));

        TabularDataSet<MLDataItem> dataSet = new TabularDataSet<>(FeatureExtractor.NUM_FEATURES, 1);
        List<String> columns = new ArrayList<>(FeatureExtractor.FEATURE_NAMES);
        columns.add("fraud");
        dataSet.setColumnNames(columns.toArray(String[]::new));

        for (int i = 0; i < train.size(); i++) {
            boolean fraud = train.get(i).fraud();
            float[] input = scaler.transform(features.get(i));
            for (int c = 0; c < (fraud ? copies : 1); c++) {
                dataSet.add(new DataSetItem(input.clone(), new float[] {fraud ? 1 : 0}));
            }
        }
        dataSet.shuffle();
        return dataSet;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
