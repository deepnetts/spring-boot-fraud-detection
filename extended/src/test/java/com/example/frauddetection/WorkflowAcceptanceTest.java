package com.example.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.frauddetection.config.FraudProperties;
import com.jayway.jsonpath.JsonPath;

/** The whole workflow from the README, run once for each data source. */
@AutoConfigureMockMvc
abstract class WorkflowAcceptanceTest {

    private static final String TRANSACTION = """
            {
              "transactionId": "accept-1", "amount": 42.10, "customerAvgAmount": 55.0, "hourOfDay": 13,
              "merchantCategory": "GROCERY", "distanceFromHomeKm": 3.2, "distanceFromLastTransactionKm": 2.5,
              "minutesSinceLastTransaction": 720.0, "transactionsLast24h": 2, "failedAttemptsLast24h": 0,
              "accountAgeDays": 1500, "cardNotPresent": false, "usedChip": true, "usedPin": true,
              "foreignTransaction": false, "newDevice": false, "newMerchant": false
            }""";

    @Autowired
    MockMvc mvc;

    @Autowired
    FraudProperties properties;

    abstract String expectedSource();

    abstract int expectedRows();

    abstract int expectedFraudRows();

    @Test
    void runsTheFullWorkflow() throws Exception {
        // analyse the data
        mvc.perform(get("/api/data/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(expectedSource()))
                .andExpect(jsonPath("$.rows").value(expectedRows()))
                .andExpect(jsonPath("$.fraudRows").value(expectedFraudRows()))
                .andExpect(jsonPath("$.categories.length()").value(greaterThanOrEqualTo(2)));

        // train on first start, evaluate and save
        String trained = mvc.perform(get("/api/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(27))
                .andExpect(jsonPath("$.evaluation.rocAuc").isNumber())
                .andExpect(jsonPath("$.evaluation.prAuc").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(Files.exists(properties.model().dir().resolve("fraud-model.bin"))).isTrue();

        // ready and healthy
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.model.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // score
        String assessment = assess(TRANSACTION).andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("accept-1"))
                .andExpect(jsonPath("$.fraudScore").isNumber())
                .andReturn().getResponse().getContentAsString();
        String riskLevel = JsonPath.read(assessment, "$.riskLevel");

        // monitor
        mvc.perform(get("/actuator/metrics/fraud.assessments").param("tag", "risk:" + riskLevel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(greaterThanOrEqualTo(1.0)));

        // clear failures and safeguards
        assess(TRANSACTION.replace("42.10", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        mvc.perform(post("/api/model/train"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        // retrain: the new model replaces the old one and scoring carries on
        String retrained = mvc.perform(post("/api/model/train").contentType(MediaType.APPLICATION_JSON).content("{\"overwrite\": true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(retrained, "$.trainedAt")).isNotEqualTo(JsonPath.<String>read(trained, "$.trainedAt"));
        assess(TRANSACTION).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelTrainedAt").value(JsonPath.<String>read(retrained, "$.trainedAt")));
    }

    private ResultActions assess(String body) throws Exception {
        return mvc.perform(post("/api/transactions/assess").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
