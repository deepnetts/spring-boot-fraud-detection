package com.example.frauddetection.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Checked at startup: an invalid setting stops the application with a message naming it. */
@Validated
@ConfigurationProperties("fraud")
public record FraudProperties(@NotNull @Valid Model model, @NotNull @Valid Training training, @NotNull @Valid Data data,
        @NotNull @Valid Retraining retraining) {

    /**
     * Scheduled retraining, which only helps when new labelled data has arrived since the last training.
     *
     * @param cron             Spring cron expression, such as {@code 0 0 3 * * *} for 03:00 every day. {@code -} turns it off
     * @param qualityTolerance how much lower, as an absolute PR AUC difference, the new model may score than the active
     *                         model on the same test rows and still replace it
     */
    public record Retraining(@NotBlank String cron, @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) double qualityTolerance) {
    }

    public record Model(
            @NotNull Path dir,
            boolean trainIfMissing,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double threshold) {
    }

    public record Training(
            @Positive int samples,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double fraudRate,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double testFraction,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false) double validationFraction,
            @NotEmpty List<@Positive Integer> hiddenLayers,
            @Positive int maxEpochs,
            @Positive float learningRate,
            @PositiveOrZero float l2Regularization,
            long seed) {

        @AssertTrue(message = "test-fraction and validation-fraction must add up to less than 1")
        public boolean isTrainingDataLeft() {
            return testFraction + validationFraction < 1;
        }

        public Training withOverrides(Integer samples, Double fraudRate, Integer maxEpochs, Long seed) {
            return new Training(
                    samples != null ? samples : this.samples,
                    fraudRate != null ? fraudRate : this.fraudRate,
                    testFraction,
                    validationFraction,
                    hiddenLayers,
                    maxEpochs != null ? maxEpochs : this.maxEpochs,
                    learningRate,
                    l2Regularization,
                    seed != null ? seed : this.seed);
        }
    }

    public record Data(@NotNull Source source, Path csvPath) {

        public enum Source {
            SYNTHETIC, CSV
        }

        @AssertTrue(message = "fraud.data.csv-path is required when fraud.data.source is csv")
        public boolean isCsvPathSet() {
            return source != Source.CSV || (csvPath != null && !csvPath.toString().isBlank());
        }
    }
}
