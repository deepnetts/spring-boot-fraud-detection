package com.example.frauddetection.model;

import java.io.Serializable;

/**
 * Hold-out evaluation of a trained model. Computed on a test split that keeps the natural
 * (imbalanced) fraud rate, so precision and recall reflect production conditions. ROC AUC is
 * threshold independent; the remaining metrics are taken at {@code threshold}.
 */
public record EvaluationReport(
        int testSamples,
        int fraudSamples,
        double threshold,
        double rocAuc,
        double accuracy,
        double precision,
        double recall,
        double f1,
        double falsePositiveRate,
        ConfusionMatrix confusionMatrix,
        double bestF1Threshold,
        double bestF1) implements Serializable {

    public record ConfusionMatrix(int truePositives, int falsePositives, int trueNegatives, int falseNegatives)
            implements Serializable {
    }
}
