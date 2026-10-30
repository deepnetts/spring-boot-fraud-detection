package com.example.frauddetection.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

class TrainingDataFailureAnalyzerTest {

    @Test
    void reportsTheProblemAndWhatToDo() {
        // Spring Boot wraps exceptions thrown by an ApplicationRunner
        FailureAnalysis analysis = new TrainingDataFailureAnalyzer()
                .analyze(new IllegalStateException(new TrainingDataException("line 3, column amount: \"abc\" is not a number")));

        assertNotNull(analysis);
        assertEquals("line 3, column amount: \"abc\" is not a number", analysis.getDescription());
        assertTrue(analysis.getAction().contains("No model was saved"));
        assertTrue(analysis.getAction().contains("fraud.data.source"));
    }

    @Test
    void isRegisteredWithSpringBoot() throws IOException {
        boolean registered = false;
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            var factories = new Properties();
            try (InputStream in = url.openStream()) {
                factories.load(in);
            }
            registered |= factories.getProperty(FailureAnalyzer.class.getName(), "").contains(TrainingDataFailureAnalyzer.class.getName());
        }
        assertTrue(registered);
    }
}
