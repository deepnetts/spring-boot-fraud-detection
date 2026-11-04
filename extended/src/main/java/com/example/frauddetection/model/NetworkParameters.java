package com.example.frauddetection.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.AbstractLayer;
import deepnetts.tensor.Tensor2D;
import deepnetts.tensor.TensorBase;

/**
 * Trained weights and biases of every layer (null for layers without them, e.g. the input layer).
 * <p>
 * Used instead of {@code NeuralNetwork.save/load}: in Deep Netts 4.0.4 a loaded
 * {@link FeedForwardNetwork} has uninitialised transient fields (input tensor, output layer
 * forward implementation) and fails on the first prediction. Rebuilding the same architecture and
 * copying the parameters in avoids that.
 */
public record NetworkParameters(List<float[]> weights, List<float[]> biases) implements Serializable {

    public static NetworkParameters capture(FeedForwardNetwork network) {
        List<float[]> weights = new ArrayList<>();
        List<float[]> biases = new ArrayList<>();
        for (AbstractLayer<?, ?, ?> layer : network.getLayers()) {
            weights.add(valuesOf(layer.getWeights()));
            biases.add(valuesOf(layer.getBiases()));
        }
        return new NetworkParameters(weights, biases);
    }

    public void restoreInto(FeedForwardNetwork network) {
        List<AbstractLayer> layers = network.getLayers();
        if (layers.size() != weights.size()) {
            throw new IllegalArgumentException(
                    "Saved parameters have %d layers, network has %d".formatted(weights.size(), layers.size()));
        }
        for (int i = 0; i < layers.size(); i++) {
            AbstractLayer<?, ?, ?> layer = layers.get(i);
            copyInto(layer.getWeights(), weights.get(i));
            copyInto(layer.getBiases(), biases.get(i));
        }
    }

    private static float[] valuesOf(TensorBase tensor) {
        return tensor == null ? null : tensor.getValues().clone();
    }

    private static void copyInto(TensorBase tensor, float[] values) {
        if (tensor == null || values == null) {
            return;
        }
        tensor.copyFrom(values);
        if (tensor instanceof Tensor2D matrix) {
            // Tensor2D keeps a copy of its rows that the forward pass reads from
            matrix.createRowsCache();
        }
    }
}
