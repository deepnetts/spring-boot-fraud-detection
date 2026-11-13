package com.example.frauddetection.minimal.features;

import deepnetts.tensor.TensorBase;

/**
 * Turns a business record into the tensor that a Deep Netts network reads.
 *
 * <p>Training, evaluation and prediction use the same encoder, so the network always receives its inputs
 * in the same order.
 *
 * @param <R> the business record, for example a transaction
 * @param <T> the Deep Netts tensor type that the network reads
 */
@FunctionalInterface
public interface TensorEncoder<R, T extends TensorBase> {

    T encodeToTensor(R record);
}
