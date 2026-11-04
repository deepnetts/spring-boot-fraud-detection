package com.example.frauddetection.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PoC performance monitoring, kept in memory. It compares the model's decisions with outcomes confirmed later,
 * for example after a chargeback or an analyst's review, and counts how often the model was right.
 * <p>
 * Only transactions sent with a {@code transactionId} can receive an outcome. The counts start again when the
 * application restarts or a new model is activated. A production system would store decisions and outcomes durably,
 * wait for late labels and watch the numbers over time.
 */
public class PerformanceMonitor {

    /** Decisions waiting for an outcome. The oldest are forgotten first. */
    static final int MAX_PENDING = 10_000;

    private final Map<String, Boolean> pending = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_PENDING;
        }
    };
    private long assessments;
    private long alerts;
    private int truePositives;
    private int falsePositives;
    private int falseNegatives;
    private int trueNegatives;

    synchronized void recordAssessment(String transactionId, boolean flagged) {
        assessments++;
        if (flagged) {
            alerts++;
        }
        if (transactionId != null && !transactionId.isBlank()) {
            pending.put(transactionId, flagged);
        }
    }

    /**
     * Records the confirmed outcome of a scored transaction.
     *
     * @return {@code false} if this model has not scored the transaction, its outcome was already recorded, or it
     *         was forgotten
     */
    synchronized boolean recordOutcome(String transactionId, boolean fraud) {
        Boolean flagged = pending.remove(transactionId);
        if (flagged == null) {
            return false;
        }
        if (flagged && fraud) {
            truePositives++;
        } else if (flagged) {
            falsePositives++;
        } else if (fraud) {
            falseNegatives++;
        } else {
            trueNegatives++;
        }
        return true;
    }

    synchronized void reset() {
        pending.clear();
        assessments = 0;
        alerts = 0;
        truePositives = 0;
        falsePositives = 0;
        falseNegatives = 0;
        trueNegatives = 0;
    }

    synchronized Snapshot snapshot() {
        int outcomes = truePositives + falsePositives + falseNegatives + trueNegatives;
        return new Snapshot(assessments, alerts, assessments == 0 ? null : (double) alerts / assessments, outcomes,
                truePositives, falsePositives, falseNegatives, trueNegatives,
                ratio(truePositives, truePositives + falsePositives), ratio(truePositives, truePositives + falseNegatives));
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /**
     * Monitoring numbers for the active model.
     *
     * @param assessments      transactions scored
     * @param alerts           transactions flagged as fraud
     * @param alertRate        share of scored transactions that were flagged. A sudden change can mean the incoming
     *                         data has changed; {@code null} before the first assessment
     * @param labelledOutcomes scored transactions whose real outcome has been reported
     * @param precision        of the flagged transactions with an outcome, the share that was fraud; {@code null} until
     *                         a flagged transaction has an outcome
     * @param recall           of the fraud cases with an outcome, the share that was flagged; {@code null} until a fraud
     *                         case has an outcome
     */
    public record Snapshot(long assessments, long alerts, Double alertRate, int labelledOutcomes, int truePositives,
            int falsePositives, int falseNegatives, int trueNegatives, Double precision, Double recall) {
    }
}
