package com.example.frauddetection.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A card transaction described by the features commonly used in fraud detection:
 * amount relative to the customer's spending profile, time of day, geography,
 * velocity, account tenure, channel / authentication method and device signals.
 * <p>
 * Text marked "contract" is a requirement for real-data integrations, not behaviour of this code:
 * validation only checks presence and ranges, and {@code SyntheticTransactionGenerator} samples these
 * values directly instead of computing them from history. See docs/DATA_CONTRACT.md.
 *
 * @param transactionId                 optional caller-side identifier, echoed back in the response
 * @param amount                        transaction amount
 * @param customerAvgAmount             customer's average transaction amount; contract: prior 90 days,
 *                                      excluding this transaction and known fraud
 * @param hourOfDay                     hour the transaction happened (0-23); contract: in the cardholder's
 *                                      home time zone
 * @param merchantCategory              merchant category
 * @param distanceFromHomeKm            distance between the transaction location and the cardholder's home
 * @param distanceFromLastTransactionKm distance from the previous transaction's location
 * @param minutesSinceLastTransaction   time since the previous transaction on the card
 * @param transactionsLast24h           number of transactions on the card in the last 24 hours (velocity);
 *                                      contract: excludes this transaction
 * @param failedAttemptsLast24h         declined / failed authorisation attempts in the last 24 hours;
 *                                      contract: excludes this transaction
 * @param accountAgeDays                age of the card / account in days; contract: days since the account
 *                                      was opened
 * @param cardNotPresent                online, phone or mail order (no physical card)
 * @param usedChip                      EMV chip was read; contract: includes contactless reads
 * @param usedPin                       PIN was verified
 * @param foreignTransaction            merchant country differs from the card's issuing country
 * @param newDevice                     device / browser fingerprint never seen for this customer
 * @param newMerchant                   customer never transacted with this merchant before
 */
public record Transaction(
        String transactionId,
        @NotNull @PositiveOrZero Double amount,
        @NotNull @Positive Double customerAvgAmount,
        @NotNull @Min(0) @Max(23) Integer hourOfDay,
        @NotNull MerchantCategory merchantCategory,
        @NotNull @PositiveOrZero Double distanceFromHomeKm,
        @NotNull @PositiveOrZero Double distanceFromLastTransactionKm,
        @NotNull @PositiveOrZero Double minutesSinceLastTransaction,
        @NotNull @PositiveOrZero Integer transactionsLast24h,
        @NotNull @PositiveOrZero Integer failedAttemptsLast24h,
        @NotNull @PositiveOrZero Integer accountAgeDays,
        @NotNull Boolean cardNotPresent,
        @NotNull Boolean usedChip,
        @NotNull Boolean usedPin,
        @NotNull Boolean foreignTransaction,
        @NotNull Boolean newDevice,
        @NotNull Boolean newMerchant) {
}
