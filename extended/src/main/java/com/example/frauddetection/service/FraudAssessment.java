package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;

/**
 * @param fraudScore     model output in [0, 1]: higher means more suspicious. Use it to rank transactions
 *                       and compare them with a threshold. It is not a calibrated probability
 * @param fraud          the decision: whether the score reached the configured threshold, {@code fraud.model.threshold}
 * @param riskIndicators human-readable red flags present in the transaction; these are
 *                       rule-based hints for analysts, not an explanation of the network's output
 * @param modelTrainedAt when the model that produced the score was trained
 */
public record FraudAssessment(
        String transactionId,
        double fraudScore,
        boolean fraud,
        List<String> riskIndicators,
        Instant modelTrainedAt) {
}
