package com.example.frauddetection.model;

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
        if (!metadata.featureNames().equals(FeatureExtractor.FEATURE_NAMES)) {
            throw new IllegalArgumentException(
                    "Model was trained with different features " + metadata.featureNames());
        }
        this.network = network;
        this.metadata = metadata;
        this.encoder = encoder(metadata.scaler());
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
