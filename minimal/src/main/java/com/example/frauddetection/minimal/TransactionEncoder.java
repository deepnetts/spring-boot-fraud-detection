package com.example.frauddetection.minimal;

import deepnetts.tensor.Tensor1D;

import java.util.List;

/**
 * Encodes a transaction as the nine inputs of the fraud network, in the order of {@link #FEATURE_NAMES}.
 *
 * <p>The values are not scaled here. The network scales them itself with the {@code Standardizer} that training
 * stores in it, so the scaling is saved and loaded with the model file.
 */
public class TransactionEncoder implements TensorEncoder<TransactionRequest, Tensor1D> {

    /** Input names in tensor order; also the input columns of {@code data/train.csv} and {@code data/test.csv}. */
    public static final List<String> FEATURE_NAMES = List.of("amount", "customerAvgAmount", "hourOfDay",
            "distanceFromHomeKm", "transactionsLast24h", "failedAttemptsLast24h", "online", "newDevice", "foreign");

    @Override
    public Tensor1D toTensor(TransactionRequest t) {
        return new Tensor1D(
                t.amount().floatValue(),
                t.customerAvgAmount().floatValue(),
                t.hourOfDay(),
                t.distanceFromHomeKm().floatValue(),
                t.transactionsLast24h(),
                t.failedAttemptsLast24h(),
                t.online() ? 1 : 0,
                t.newDevice() ? 1 : 0,
                t.foreign() ? 1 : 0);
    }
}
