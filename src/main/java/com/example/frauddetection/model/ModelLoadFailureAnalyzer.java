package com.example.frauddetection.model;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a model that can't be loaded into Spring Boot's "APPLICATION FAILED TO START" report. */
class ModelLoadFailureAnalyzer extends AbstractFailureAnalyzer<ModelLoadException> {

    private static final String START_FRESH = "move the file out of fraud.model.dir, or set fraud.model.dir to an "
            + "empty folder. With fraud.model.train-if-missing: true the next start trains a new model.";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ModelLoadException cause) {
        String action = switch (cause.reason()) {
            case INCOMPATIBLE -> "The file was not changed. Either restore the code that matches this model, or "
                    + START_FRESH;
            case UNREADABLE -> "The file was not changed. Check the file and its permissions, or restore it from a "
                    + "backup. To start with a new model instead, " + START_FRESH;
        };
        return new FailureAnalysis(cause.getMessage(), action, cause);
    }
}
