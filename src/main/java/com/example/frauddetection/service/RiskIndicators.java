package com.example.frauddetection.service;

import java.util.ArrayList;
import java.util.List;

import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;

/**
 * Classic fraud red flags, reported alongside the model score to help analysts review alerts.
 */
final class RiskIndicators {

    private RiskIndicators() {
    }

    static List<String> of(Transaction tx) {
        List<String> flags = new ArrayList<>();
        double ratio = tx.amount() / Math.max(tx.customerAvgAmount(), 1.0);
        if (ratio >= 3) {
            flags.add("Amount is %.1fx the customer's average".formatted(ratio));
        }
        if (tx.amount() < 5 && tx.transactionsLast24h() >= 8) {
            flags.add("Small amount with high velocity (possible card testing)");
        } else if (tx.transactionsLast24h() >= 8) {
            flags.add("High velocity: %d transactions in 24h".formatted(tx.transactionsLast24h()));
        }
        if (tx.failedAttemptsLast24h() >= 2) {
            flags.add("%d failed attempts in 24h".formatted(tx.failedAttemptsLast24h()));
        }
        if (FeatureExtractor.isNight(tx.hourOfDay())) {
            flags.add("Night-time transaction");
        }
        if (tx.distanceFromHomeKm() >= 100) {
            flags.add("%.0f km from home".formatted(tx.distanceFromHomeKm()));
        }
        if (tx.distanceFromLastTransactionKm() >= 100 && tx.minutesSinceLastTransaction() < 120) {
            flags.add("Large distance from previous transaction in a short time");
        }
        if (tx.foreignTransaction()) {
            flags.add("Foreign transaction");
        }
        if (tx.accountAgeDays() < 30) {
            flags.add("New account (%d days)".formatted(tx.accountAgeDays()));
        }
        if (tx.cardNotPresent() && tx.newDevice()) {
            flags.add("Card-not-present from a new device");
        }
        if (!tx.cardNotPresent() && !tx.usedChip()) {
            flags.add("Card present without chip (magstripe fallback)");
        }
        if (tx.merchantCategory().isHighRisk()) {
            flags.add("High-risk merchant category: " + tx.merchantCategory());
        }
        return flags;
    }
}
