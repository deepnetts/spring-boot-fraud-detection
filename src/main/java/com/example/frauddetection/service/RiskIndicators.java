package com.example.frauddetection.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            flags.add(format("Amount is %.1fx the customer's average", ratio));
        }
        if (tx.amount() < 5 && tx.transactionsLast24h() >= 8) {
            flags.add("Small amount with high velocity (possible card testing)");
        } else if (tx.transactionsLast24h() >= 8) {
            flags.add(format("High velocity: %d transactions in 24h", tx.transactionsLast24h()));
        }
        if (tx.failedAttemptsLast24h() >= 2) {
            flags.add(format("%d failed attempts in 24h", tx.failedAttemptsLast24h()));
        }
        if (FeatureExtractor.isNight(tx.hourOfDay())) {
            flags.add("Night-time transaction");
        }
        if (tx.distanceFromHomeKm() >= 100) {
            flags.add(format("%.0f km from home", tx.distanceFromHomeKm()));
        }
        if (tx.distanceFromLastTransactionKm() >= 100 && tx.minutesSinceLastTransaction() < 120) {
            flags.add("Large distance from previous transaction in a short time");
        }
        if (tx.foreignTransaction()) {
            flags.add("Foreign transaction");
        }
        if (tx.accountAgeDays() < 30) {
            flags.add(format("New account (%d days)", tx.accountAgeDays()));
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

    // Locale.ROOT keeps the text identical on every machine ("55.6x", never "55,6x")
    private static String format(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }
}
