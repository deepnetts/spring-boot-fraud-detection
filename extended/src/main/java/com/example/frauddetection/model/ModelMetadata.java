package com.example.frauddetection.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

import com.example.frauddetection.features.FeatureScaler;

/**
 * Everything besides the network weights that is needed to use and describe a trained model.
 */
public record ModelMetadata(
        Instant trainedAt,
        List<String> featureNames,
        FeatureScaler scaler,
        List<Integer> hiddenLayers,
        int trainingSamples,
        int epochs,
        double trainingSeconds,
        EvaluationReport evaluation) implements Serializable {
}
