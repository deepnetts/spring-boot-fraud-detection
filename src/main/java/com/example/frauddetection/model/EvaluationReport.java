package com.example.frauddetection.model;

import java.io.Serializable;

/**
 * Evaluation of a trained model on a test split that keeps the natural (imbalanced) fraud rate and
 * played no part in training or in choosing the recommended threshold.
 * <p>
 * ROC AUC and PR AUC don't depend on a threshold. The next group of metrics is taken at the configured
 * {@code threshold}, and the {@code recommended} metrics at the threshold chosen on the validation
 * split. Precision and PR AUC depend on the fraud rate, so they only hold at this test set's rate.
 */
public record EvaluationReport(
        int testSamples,
        int fraudSamples,
        double threshold,
        double rocAuc,
        double prAuc,
        double accuracy,
        double precision,
        double recall,
        double f1,
        double falsePositiveRate,
        ConfusionMatrix confusionMatrix,
        double recommendedThreshold,
        double recommendedPrecision,
        double recommendedRecall,
        double recommendedF1) implements Serializable {

    public record ConfusionMatrix(int truePositives, int falsePositives, int trueNegatives, int falseNegatives)
            implements Serializable {
    }
}
