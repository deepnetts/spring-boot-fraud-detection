package com.example.frauddetection.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

/**
 * Turns a {@link Transaction} into the numeric feature vector fed to the neural network.
 * <p>
 * Heavy-tailed quantities (amounts, distances, time gaps) are log-transformed, the hour is
 * encoded cyclically so 23:00 and 00:00 end up close together, and the merchant category is
 * one-hot encoded. The resulting vector is standardised by {@link FeatureScaler} before training
 * and inference.
 */
public final class FeatureExtractor {

    private static final MerchantCategory[] CATEGORIES = MerchantCategory.values();

    public static final List<String> FEATURE_NAMES;

    static {
        List<String> names = new ArrayList<>(List.of(
                "log_amount",
                "log_amount_to_customer_avg",
                "hour_sin",
                "hour_cos",
                "is_night",
                "log_distance_from_home",
                "log_distance_from_last_txn",
                "log_minutes_since_last_txn",
                "txn_count_24h",
                "failed_attempts_24h",
                "log_account_age_days",
                "card_not_present",
                "used_chip",
                "used_pin",
                "foreign",
                "new_device",
                "new_merchant"));
        for (MerchantCategory category : CATEGORIES) {
            names.add("category_" + category.name().toLowerCase());
        }
        FEATURE_NAMES = Collections.unmodifiableList(names);
    }

    public static final int NUM_FEATURES = FEATURE_NAMES.size();

    private FeatureExtractor() {
    }

    public static float[] extract(Transaction tx) {
        float[] f = new float[NUM_FEATURES];
        double hourAngle = 2 * Math.PI * tx.hourOfDay() / 24.0;
        int i = 0;
        f[i++] = log1p(tx.amount());
        f[i++] = log1p(tx.amount() / Math.max(tx.customerAvgAmount(), 1.0));
        f[i++] = (float) Math.sin(hourAngle);
        f[i++] = (float) Math.cos(hourAngle);
        f[i++] = isNight(tx.hourOfDay()) ? 1 : 0;
        f[i++] = log1p(tx.distanceFromHomeKm());
        f[i++] = log1p(tx.distanceFromLastTransactionKm());
        f[i++] = log1p(tx.minutesSinceLastTransaction());
        f[i++] = tx.transactionsLast24h();
        f[i++] = tx.failedAttemptsLast24h();
        f[i++] = log1p(tx.accountAgeDays());
        f[i++] = flag(tx.cardNotPresent());
        f[i++] = flag(tx.usedChip());
        f[i++] = flag(tx.usedPin());
        f[i++] = flag(tx.foreignTransaction());
        f[i++] = flag(tx.newDevice());
        f[i++] = flag(tx.newMerchant());
        f[i + tx.merchantCategory().ordinal()] = 1;
        return f;
    }

    public static boolean isNight(int hourOfDay) {
        return hourOfDay < 6;
    }

    private static float log1p(double value) {
        return (float) Math.log1p(Math.max(value, 0));
    }

    private static float flag(boolean value) {
        return value ? 1 : 0;
    }
}
