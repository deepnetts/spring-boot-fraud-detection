package com.example.frauddetection.data;

/** The training data can't be used. No model is trained, and any existing model stays in place. */
public class TrainingDataException extends RuntimeException {

    public TrainingDataException(String message) {
        super(message);
    }

    public TrainingDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
