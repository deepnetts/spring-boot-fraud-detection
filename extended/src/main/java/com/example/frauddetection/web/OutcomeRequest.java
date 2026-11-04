package com.example.frauddetection.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The confirmed outcome of a transaction that was scored earlier.
 *
 * @param transactionId the {@code transactionId} sent when the transaction was scored
 * @param fraud         whether the transaction turned out to be fraud
 */
public record OutcomeRequest(@NotBlank String transactionId, @NotNull Boolean fraud) {
}
