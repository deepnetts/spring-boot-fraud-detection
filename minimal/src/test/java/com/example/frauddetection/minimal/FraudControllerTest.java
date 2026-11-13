package com.example.frauddetection.minimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Starts the whole application, which trains a model into a temporary folder, and calls the endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
class FraudControllerTest {

    @TempDir
    static Path modelDir;

    @DynamicPropertySource
    static void modelFile(DynamicPropertyRegistry registry) {
        registry.add("fraud.model-file", () -> modelDir.resolve("fraud-model.dnet").toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void flagsASuspiciousTransaction() throws Exception {
        predict("""
                {"amount": 950, "customerAvgAmount": 45, "hourOfDay": 3, "distanceFromHomeKm": 850,
                 "transactionsLast24h": 11, "failedAttemptsLast24h": 3, "online": true, "newDevice": true, "foreign": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(true))
                .andExpect(jsonPath("$.threshold").doesNotExist());
    }

    @Test
    void doesNotFlagAnOrdinaryTransaction() throws Exception {
        predict("""
                {"amount": 42.10, "customerAvgAmount": 55, "hourOfDay": 13, "distanceFromHomeKm": 3.2,
                 "transactionsLast24h": 2, "failedAttemptsLast24h": 0, "online": false, "newDevice": false, "foreign": false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(false));
    }

    @Test
    void rejectsAnInvalidValue() throws Exception {
        predict("""
                {"amount": -5, "customerAvgAmount": 55, "hourOfDay": 13, "distanceFromHomeKm": 3.2,
                 "transactionsLast24h": 2, "failedAttemptsLast24h": 0, "online": false, "newDevice": false, "foreign": false}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid fields: amount must be greater than or equal to 0"));
    }

    @Test
    void rejectsAMissingField() throws Exception {
        predict("""
                {"amount": 42.10, "customerAvgAmount": 55}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("hourOfDay must not be null")));
    }

    @Test
    void rejectsADecimalHour() throws Exception {
        predict("""
                {"amount": 42.10, "customerAvgAmount": 55, "hourOfDay": 13.7, "distanceFromHomeKm": 3.2,
                 "transactionsLast24h": 2, "failedAttemptsLast24h": 0, "online": false, "newDevice": false, "foreign": false}""")
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions predict(String json) throws Exception {
        return mvc.perform(post("/api/fraud/predict").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
