package com.example.frauddetection.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelEvaluatorTest {

    @Test
    void computesAucAndThresholdMetrics() {
        float[] probabilities = {0.9f, 0.8f, 0.3f, 0.6f, 0.2f, 0.1f};
        boolean[] labels = {true, true, true, false, false, false};

        EvaluationReport report = ModelEvaluator.evaluate(probabilities, labels, 0.5);

        // 8 of the 9 (fraud, legit) pairs are ranked correctly
        assertEquals(8.0 / 9, report.rocAuc(), 1e-9);
        assertEquals(new EvaluationReport.ConfusionMatrix(2, 1, 2, 1), report.confusionMatrix());
        assertEquals(2.0 / 3, report.precision(), 1e-9);
        assertEquals(2.0 / 3, report.recall(), 1e-9);
    }

    @Test
    void tiesGetHalfCredit() {
        assertEquals(0.5, ModelEvaluator.rocAuc(new float[] {0.5f, 0.5f}, new boolean[] {true, false}), 1e-9);
    }
}
