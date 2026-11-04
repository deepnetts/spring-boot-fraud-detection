package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;

/**
 * @param fraudScore     model output in [0, 1]: higher means more suspicious. Use it to rank transactions
 *                       and compare them with a threshold. It is not a calibrated probability
 * @param fraud          whether the score reached the configured threshold
 * @param riskIndicators human-readable red flags present in the transaction; these are
 *                       rule-based hints for analysts, not an explanation of the network's output
 */
public record FraudAssessment(
        String transactionId,
        double fraudScore,
        boolean fraud,
        RiskLevel riskLevel,
        double threshold,
        List<String> riskIndicators,
        Instant modelTrainedAt) {

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        static RiskLevel of(double score, double threshold) {
            if (score < threshold / 2) return LOW;
            if (score < threshold) return MEDIUM;
            if (score < (1 + threshold) / 2) return HIGH;
            return CRITICAL;
        }
    }
}
