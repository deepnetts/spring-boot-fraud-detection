package com.example.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.frauddetection.service.FraudDetectionService;

@SpringBootTest(properties = {
        "fraud.model.dir=target/test-model-${random.uuid}",
        "fraud.training.samples=8000",
        "fraud.training.max-epochs=15"
})
@AutoConfigureMockMvc
class FraudDetectionApplicationTest {

    private static final String SUSPICIOUS = """
            {
              "transactionId": "suspicious-1",
              "amount": 2499.99,
              "customerAvgAmount": 45.0,
              "hourOfDay": 3,
              "merchantCategory": "ELECTRONICS",
              "distanceFromHomeKm": 850.0,
              "distanceFromLastTransactionKm": 790.0,
              "minutesSinceLastTransaction": 12.0,
              "transactionsLast24h": 11,
              "failedAttemptsLast24h": 3,
              "accountAgeDays": 400,
              "cardNotPresent": true,
              "usedChip": false,
              "usedPin": false,
              "foreignTransaction": true,
              "newDevice": true,
              "newMerchant": true
            }""";

    private static final String ORDINARY = """
            {
              "transactionId": "ordinary-1",
              "amount": 42.10,
              "customerAvgAmount": 55.0,
              "hourOfDay": 13,
              "merchantCategory": "GROCERY",
              "distanceFromHomeKm": 3.2,
              "distanceFromLastTransactionKm": 2.5,
              "minutesSinceLastTransaction": 720.0,
              "transactionsLast24h": 2,
              "failedAttemptsLast24h": 0,
              "accountAgeDays": 1500,
              "cardNotPresent": false,
              "usedChip": true,
              "usedPin": true,
              "foreignTransaction": false,
              "newDevice": false,
              "newMerchant": false
            }""";

    @Autowired
    MockMvc mvc;

    @Autowired
    FraudDetectionService service;

    @Test
    void trainedModelSeparatesFraudFromLegitimateTraffic() {
        assertThat(service.modelInfo().evaluation().rocAuc()).isGreaterThan(0.9);
    }

    @Test
    void flagsSuspiciousTransaction() throws Exception {
        mvc.perform(post("/api/transactions/assess").contentType(MediaType.APPLICATION_JSON).content(SUSPICIOUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("suspicious-1"))
                .andExpect(jsonPath("$.fraud").value(true))
                .andExpect(jsonPath("$.riskIndicators").isNotEmpty());
    }

    @Test
    void passesOrdinaryTransaction() throws Exception {
        mvc.perform(post("/api/transactions/assess").contentType(MediaType.APPLICATION_JSON).content(ORDINARY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraud").value(false))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void scoresBatch() throws Exception {
        mvc.perform(post("/api/transactions/assess/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + SUSPICIOUS + "," + ORDINARY + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void rejectsInvalidTransaction() throws Exception {
        mvc.perform(post("/api/transactions/assess").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": -5, \"hourOfDay\": 30}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesToRetrainOverAnExistingModelWithoutOverwrite() throws Exception {
        mvc.perform(post("/api/model/train"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/model/train").contentType(MediaType.APPLICATION_JSON).content("{\"overwrite\": false}"))
                .andExpect(status().isConflict());
    }

    @Test
    void exposesModelInfo() throws Exception {
        mvc.perform(get("/api/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(27))
                .andExpect(jsonPath("$.evaluation.rocAuc").isNumber());
    }
}
