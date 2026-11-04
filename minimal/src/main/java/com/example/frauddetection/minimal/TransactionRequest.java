package com.example.frauddetection.minimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A card transaction to score. The fields are the columns of {@code data/train.csv}, in the same order.
 * All fields are required: a missing value is rejected rather than replaced with a default.
 *
 * @param amount                payment amount in EUR
 * @param customerAvgAmount     the customer's average payment amount in EUR
 * @param hourOfDay             hour of the payment, 0 to 23
 * @param distanceFromHomeKm    distance between the customer's home and the payment location
 * @param transactionsLast24h   payments on the card in the previous 24 hours
 * @param failedAttemptsLast24h declined or failed attempts on the card in the previous 24 hours
 * @param online                online payment, {@code false} for a payment in a store
 * @param newDevice             a device the customer has not used before
 * @param foreign               the merchant is in another country than the card's issuer
 */
public record TransactionRequest(
        @NotNull @PositiveOrZero Double amount,
        @NotNull @Positive Double customerAvgAmount,
        @NotNull @PositiveOrZero @Max(23) Integer hourOfDay,
        @NotNull @PositiveOrZero Double distanceFromHomeKm,
        @NotNull @PositiveOrZero Integer transactionsLast24h,
        @NotNull @PositiveOrZero Integer failedAttemptsLast24h,
        @NotNull Boolean online,
        @NotNull Boolean newDevice,
        @NotNull Boolean foreign) {
}
