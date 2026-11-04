package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;

import com.example.frauddetection.model.EvaluationReport;
import com.example.frauddetection.model.ModelMetadata;

public record ModelInfo(
        Instant trainedAt,
        List<String> features,
        List<Integer> hiddenLayers,
        int trainingSamples,
        int epochs,
        double trainingSeconds,
        double threshold,
        EvaluationReport evaluation) {

    static ModelInfo of(ModelMetadata metadata, double threshold) {
        return new ModelInfo(metadata.trainedAt(), metadata.featureNames(), metadata.hiddenLayers(),
                metadata.trainingSamples(), metadata.epochs(), metadata.trainingSeconds(), threshold,
                metadata.evaluation());
    }
}
