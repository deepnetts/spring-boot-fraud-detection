package com.example.frauddetection.features;

import java.io.Serializable;
import java.util.List;

import deepnetts.tensor.Tensor1D;

/**
 * Z-score standardisation fitted on the training data. The fitted statistics are persisted
 * with the model so inference uses exactly the same scaling as training.
 */
public record FeatureScaler(float[] means, float[] stds) implements Serializable {

    public static FeatureScaler fit(List<Tensor1D> features) {
        int n = features.get(0).getValues().length;
        double[] sum = new double[n];
        double[] sumSq = new double[n];
        for (Tensor1D tensor : features) {
            float[] row = tensor.getValues();
            for (int j = 0; j < n; j++) {
                sum[j] += row[j];
                sumSq[j] += (double) row[j] * row[j];
            }
        }
        float[] means = new float[n];
        float[] stds = new float[n];
        for (int j = 0; j < n; j++) {
            double mean = sum[j] / features.size();
            double variance = Math.max(sumSq[j] / features.size() - mean * mean, 0);
            means[j] = (float) mean;
            // constant features would divide by zero; leave them centred but unscaled
            stds[j] = variance > 1e-12 ? (float) Math.sqrt(variance) : 1f;
        }
        return new FeatureScaler(means, stds);
    }

    public Tensor1D transform(Tensor1D features) {
        float[] row = features.getValues();
        float[] scaled = new float[row.length];
        for (int j = 0; j < row.length; j++) {
            scaled[j] = (row[j] - means[j]) / stds[j];
        }
        return Tensor1D.of(scaled);
    }
}
