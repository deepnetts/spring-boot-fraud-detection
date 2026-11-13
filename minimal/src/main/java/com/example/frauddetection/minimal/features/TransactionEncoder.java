package com.example.frauddetection.minimal.features;

import com.example.frauddetection.minimal.domain.Transaction;

import deepnetts.tensor.Tensor1D;

import java.util.List;

/**
 * Encodes a transaction as the nine inputs of the fraud network, in the order of {@link #FEATURE_NAMES}.
 *
 * <p>The two amounts and the distance use a log scale, {@code log(1 + x)}. They range from cents to thousands, and a
 * network extrapolates badly from values far outside its training data: on the raw scale a suspicious payment of
 * 100,000 EUR scored as legitimate. On the log scale it stays close to the largest values the network has seen.
 *
 * <p>The values are not standardized here. The network does that itself with the {@code Standardizer} that training
 * stores in it, so the scaling is saved and loaded with the model file.
 */
public class TransactionEncoder implements TensorEncoder<Transaction, Tensor1D> {

    /** Input names in tensor order. The CSV columns are the {@link Transaction} fields, in the same order. */
    public static final List<String> FEATURE_NAMES = List.of("logAmount", "logCustomerAvgAmount", "hourOfDay",
            "logDistanceFromHomeKm", "transactionsLast24h", "failedAttemptsLast24h", "online", "newDevice", "foreign");

    @Override
    public Tensor1D encodeToTensor(Transaction t) {
        return new Tensor1D(
                log(t.amount()),
                log(t.customerAvgAmount()),
                t.hourOfDay(),
                log(t.distanceFromHomeKm()),
                t.transactionsLast24h(),
                t.failedAttemptsLast24h(),
                t.online() ? 1 : 0,
                t.newDevice() ? 1 : 0,
                t.foreign() ? 1 : 0);
    }

    private static float log(double value) {
        return (float) Math.log1p(value);
    }
}
