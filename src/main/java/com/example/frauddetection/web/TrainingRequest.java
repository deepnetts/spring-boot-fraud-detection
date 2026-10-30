package com.example.frauddetection.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Optional overrides of the configured training settings; null fields keep the defaults.
 *
 * @param overwrite must be true to replace an existing model, which is not kept
 */
public record TrainingRequest(
        @Min(1000) @Max(1_000_000) Integer samples,
        @DecimalMin("0.001") @DecimalMax("0.5") Double fraudRate,
        @Min(1) @Max(1000) Integer maxEpochs,
        Long seed,
        Boolean overwrite) {
}
