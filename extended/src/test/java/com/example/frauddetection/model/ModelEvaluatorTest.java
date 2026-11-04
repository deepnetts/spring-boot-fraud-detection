package com.example.frauddetection.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelEvaluatorTest {

    @Test
    void computesAucAndThresholdMetrics() {
        float[] scores = {0.9f, 0.8f, 0.3f, 0.6f, 0.2f, 0.1f};
        boolean[] labels = {true, true, true, false, false, false};

        EvaluationReport report = ModelEvaluator.evaluate(scores, labels, 0.5, 0.7);

        // 8 of the 9 (fraud, legit) pairs are ranked correctly
        assertEquals(8.0 / 9, report.rocAuc(), 1e-9);
        assertEquals(new EvaluationReport.ConfusionMatrix(2, 1, 2, 1), report.confusionMatrix());
        assertEquals(2.0 / 3, report.precision(), 1e-9);
        assertEquals(2.0 / 3, report.recall(), 1e-9);
    }

    @Test
    void reportsMetricsAtTheRecommendedThreshold() {
        float[] scores = {0.9f, 0.8f, 0.3f, 0.6f, 0.2f, 0.1f};
        boolean[] labels = {true, true, true, false, false, false};

        EvaluationReport report = ModelEvaluator.evaluate(scores, labels, 0.5, 0.7);

        assertEquals(0.7, report.recommendedThreshold(), 1e-9);
        assertEquals(1, report.recommendedPrecision(), 1e-9);
        assertEquals(2.0 / 3, report.recommendedRecall(), 1e-9);
        assertEquals(0.8, report.recommendedF1(), 1e-9);
    }

    @Test
    void prAucIsTheAveragePrecision() {
        // ranked: fraud, fraud, legit, fraud, legit, legit
        float[] scores = {0.9f, 0.8f, 0.3f, 0.6f, 0.2f, 0.1f};
        boolean[] labels = {true, true, true, false, false, false};

        assertEquals((1 + 1 + 3.0 / 4) / 3, ModelEvaluator.averagePrecision(scores, labels), 1e-9);
        assertEquals((1 + 1 + 3.0 / 4) / 3, ModelEvaluator.evaluate(scores, labels, 0.5, 0.5).prAuc(), 1e-9);
    }

    @Test
    void prAucTreatsTiedScoresAsOneStep() {
        assertEquals(0.5, ModelEvaluator.averagePrecision(new float[] {0.5f, 0.5f}, new boolean[] {true, false}), 1e-9);
    }

    @Test
    void prAucIsNotANumberWithoutFraud() {
        assertEquals(Double.NaN, ModelEvaluator.averagePrecision(new float[] {0.5f}, new boolean[] {false}));
    }

    @Test
    void recommendsTheThresholdWithTheBestF1() {
        float[] scores = {0.9f, 0.8f, 0.6f, 0.2f};
        boolean[] labels = {true, true, false, false};

        assertEquals(0.61, ModelEvaluator.recommendThreshold(scores, labels), 1e-9);
    }

    @Test
    void tiesGetHalfCredit() {
        assertEquals(0.5, ModelEvaluator.rocAuc(new float[] {0.5f, 0.5f}, new boolean[] {true, false}), 1e-9);
    }
}
