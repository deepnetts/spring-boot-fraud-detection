package com.example.frauddetection.data;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns unusable training data at startup into Spring Boot's "APPLICATION FAILED TO START" report. */
class TrainingDataFailureAnalyzer extends AbstractFailureAnalyzer<TrainingDataException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, TrainingDataException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Fix the training data and start again. No model was saved. See \"Train on your own data\" in the README "
                        + "for the expected format, or set fraud.data.source to synthetic.",
                cause);
    }
}
