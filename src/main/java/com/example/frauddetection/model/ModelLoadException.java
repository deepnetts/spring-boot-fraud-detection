package com.example.frauddetection.model;

import java.nio.file.Path;

/**
 * A saved model exists but can't be used. Startup stops rather than training over it, so the file
 * stays exactly as it was for the developer to inspect.
 */
public class ModelLoadException extends RuntimeException {

    public enum Reason {
        /** A valid model made by a different version of the code, e.g. different features. */
        INCOMPATIBLE,
        /** The file can't be read as a model: truncated, corrupted, wrong content, no permission. */
        UNREADABLE
    }

    private final Reason reason;
    private final Path file;

    public ModelLoadException(Reason reason, Path file, String detail, Throwable cause) {
        super(switch (reason) {
            case INCOMPATIBLE -> "The saved fraud model at %s is incompatible with the current code: %s"
                    .formatted(file.toAbsolutePath(), detail);
            case UNREADABLE -> "The saved fraud model at %s could not be read: %s"
                    .formatted(file.toAbsolutePath(), detail);
        }, cause);
        this.reason = reason;
        this.file = file;
    }

    public Reason reason() {
        return reason;
    }

    public Path file() {
        return file;
    }
}
