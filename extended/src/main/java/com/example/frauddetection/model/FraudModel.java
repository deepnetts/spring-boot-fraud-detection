package com.example.frauddetection.model;

import java.util.ArrayList;
import java.util.List;

import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;
import com.example.frauddetection.features.FeatureScaler;
import com.example.frauddetection.features.TensorEncoder;

import deepnetts.core.DeepNetts;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.tensor.Tensor1D;

/**
 * A trained Deep Netts network together with the feature scaling it was trained with.
 */
public final class FraudModel implements AutoCloseable {

    /** Turns a transaction into its unscaled features. One instance serves every model. */
    public static final TensorEncoder<Transaction, Tensor1D> FEATURES = new FeatureExtractor();

    private final FeedForwardNetwork network;
    private final ModelMetadata metadata;
    private final TensorEncoder<Transaction, Tensor1D> encoder;

    public FraudModel(FeedForwardNetwork network, ModelMetadata metadata) {
        requireCurrentFeatures(metadata.featureNames());
        this.network = network;
        this.metadata = metadata;
        this.encoder = encoder(metadata.scaler());
    }

    /**
     * Throws if a model trained with {@code featureNames} can't read the features the current {@link FeatureExtractor}
     * produces, naming what changed.
     */
    static void requireCurrentFeatures(List<String> featureNames) {
        if (featureNames.equals(FeatureExtractor.FEATURE_NAMES)) {
            return;
        }
        List<String> added = new ArrayList<>(FeatureExtractor.FEATURE_NAMES);
        added.removeAll(featureNames);
        List<String> removed = new ArrayList<>(featureNames);
        removed.removeAll(FeatureExtractor.FEATURE_NAMES);
        throw new IllegalArgumentException("it was trained with " + featureNames.size()
                + " features and the current FeatureExtractor has " + FeatureExtractor.NUM_FEATURES + ". Added: " + added
                + ". Removed: " + removed + (added.isEmpty() && removed.isEmpty() ? ". The feature order changed" : ""));
    }

    /**
     * The tensor a network trained with {@code scaler} reads: the features, scaled with the statistics fitted on
     * its training data. Training, evaluation and prediction all use it.
     */
    public static TensorEncoder<Transaction, Tensor1D> encoder(FeatureScaler scaler) {
        return transaction -> scaler.transform(FEATURES.toTensor(transaction));
    }

    /** Fraud score in [0, 1]: higher means more suspicious. It is not a calibrated probability. */
    public float predict(Transaction transaction) {
        Tensor1D input = encoder.toTensor(transaction);
        // the network keeps per-layer activations as state, so inference must not run concurrently
        synchronized (network) {
            // not predict(float...): it relies on a transient field that is null after loading from file
            return network.predict(input).getValues()[0];
        }
    }

    /** The same model with its evaluation results. It shares the network, so only one of the two is used and closed. */
    FraudModel withEvaluation(EvaluationReport report) {
        return new FraudModel(network, metadata.withEvaluation(report));
    }

    public FeedForwardNetwork network() {
        return network;
    }

    public ModelMetadata metadata() {
        return metadata;
    }

    @Override
    public void close() throws Exception {
        synchronized (network) {
            if (network.getThreadPool() != null) {
                network.getThreadPool().shutdownNow();
            }
            // close() only frees the CUDA memory arena and throws when CUDA isn't in use (Deep Netts 4.0.4 and 4.0.5)
            if (DeepNetts.getInstance().useCuda()) {
                network.close();
            }
        }
    }
}
