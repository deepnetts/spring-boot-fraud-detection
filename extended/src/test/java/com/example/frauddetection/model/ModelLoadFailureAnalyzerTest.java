package com.example.frauddetection.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

import com.example.frauddetection.model.ModelLoadException.Reason;

class ModelLoadFailureAnalyzerTest {

    private static final Path FILE = Path.of("model", "fraud-model.bin");

    @Test
    void explainsAnIncompatibleModel() {
        FailureAnalysis analysis = analyze(new ModelLoadException(Reason.INCOMPATIBLE, FILE, "different features", null));

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("is incompatible with the current code"));
        assertTrue(analysis.getDescription().contains(FILE.toAbsolutePath().toString()));
        assertTrue(analysis.getAction().contains("was not changed"));
        assertTrue(analysis.getAction().contains("restore the code"));
        assertTrue(analysis.getAction().contains("fraud.model.dir"));
    }

    @Test
    void explainsAnUnreadableModel() {
        FailureAnalysis analysis = analyze(new ModelLoadException(Reason.UNREADABLE, FILE, "truncated", null));

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("could not be read: truncated"));
        assertTrue(analysis.getAction().contains("was not changed"));
        assertTrue(analysis.getAction().contains("permissions"));
        assertTrue(analysis.getAction().contains("fraud.model.dir"));
    }

    @Test
    void isRegisteredWithSpringBoot() throws IOException {
        boolean registered = false;
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            var factories = new Properties();
            try (InputStream in = url.openStream()) {
                factories.load(in);
            }
            String analyzers = factories.getProperty(FailureAnalyzer.class.getName(), "");
            registered |= analyzers.contains(ModelLoadFailureAnalyzer.class.getName());
        }
        assertTrue(registered);
    }

    /** Spring Boot wraps exceptions thrown by an ApplicationRunner. */
    private static FailureAnalysis analyze(ModelLoadException failure) {
        return new ModelLoadFailureAnalyzer().analyze(new IllegalStateException(failure));
    }
}
