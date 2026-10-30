package com.example.frauddetection.model;

import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;

import deepnetts.core.DeepNetts;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.tensor.Tensor1D;

/**
 * A trained Deep Netts network together with the feature scaling it was trained with.
 */
public final class FraudModel implements AutoCloseable {

    private final FeedForwardNetwork network;
    private final ModelMetadata metadata;

    public FraudModel(FeedForwardNetwork network, ModelMetadata metadata) {
        if (!metadata.featureNames().equals(FeatureExtractor.FEATURE_NAMES)) {
            throw new IllegalArgumentException(
                    "Model was trained with different features " + metadata.featureNames());
        }
        this.network = network;
        this.metadata = metadata;
    }

    /** Fraud score in [0, 1]: higher means more suspicious. It is not a calibrated probability. */
    public float predict(Transaction transaction) {
        return predictFeatures(FeatureExtractor.extract(transaction));
    }

    float predictFeatures(float[] rawFeatures) {
        float[] input = metadata.scaler().transform(rawFeatures);
        // the network keeps per-layer activations as state, so inference must not run concurrently
        synchronized (network) {
            // not predict(float...): it relies on a transient field that is null after loading from file
            return network.predict(Tensor1D.of(input)).getValues()[0];
        }
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
            // close() only frees the CUDA memory arena and throws when CUDA isn't in use (Deep Netts 4.0.4)
            if (DeepNetts.getInstance().useCuda()) {
                network.close();
            }
        }
    }
}
