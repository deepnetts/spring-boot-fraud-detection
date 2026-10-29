package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;

/**
 * @param fraudProbability model output in [0, 1]
 * @param fraud            whether the probability reached the configured threshold
 * @param riskIndicators   human-readable red flags present in the transaction; these are
 *                         rule-based hints for analysts, not an explanation of the network's output
 */
public record FraudAssessment(
        String transactionId,
        double fraudProbability,
        boolean fraud,
        RiskLevel riskLevel,
        double threshold,
        List<String> riskIndicators,
        Instant modelTrainedAt) {

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        static RiskLevel of(double probability, double threshold) {
            if (probability < threshold / 2) return LOW;
            if (probability < threshold) return MEDIUM;
            if (probability < (1 + threshold) / 2) return HIGH;
            return CRITICAL;
        }
    }
}
