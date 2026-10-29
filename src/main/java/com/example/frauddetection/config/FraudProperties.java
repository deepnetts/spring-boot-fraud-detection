package com.example.frauddetection.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud")
public record FraudProperties(Model model, Training training) {

    public record Model(Path dir, boolean trainIfMissing, double threshold) {
    }

    public record Training(
            int samples,
            double fraudRate,
            double testFraction,
            double targetFraudShare,
            List<Integer> hiddenLayers,
            int maxEpochs,
            float learningRate,
            float l2Regularization,
            long seed) {

        public Training withOverrides(Integer samples, Double fraudRate, Integer maxEpochs, Long seed) {
            return new Training(
                    samples != null ? samples : this.samples,
                    fraudRate != null ? fraudRate : this.fraudRate,
                    testFraction,
                    targetFraudShare,
                    hiddenLayers,
                    maxEpochs != null ? maxEpochs : this.maxEpochs,
                    learningRate,
                    l2Regularization,
                    seed != null ? seed : this.seed);
        }
    }
}
