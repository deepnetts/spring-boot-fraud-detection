package com.example.frauddetection.minimal;

/**
 * The model's answer for one transaction.
 *
 * @param fraudScore score from 0 to 1: higher means more suspicious. It ranks transactions and is compared
 *                   with the threshold. It is not a calibrated probability of fraud
 * @param fraud      the decision: {@code true} when {@code fraudScore} is at or above the configured threshold,
 *                   {@code fraud.threshold}
 */
public record FraudPrediction(double fraudScore, boolean fraud) {
}
