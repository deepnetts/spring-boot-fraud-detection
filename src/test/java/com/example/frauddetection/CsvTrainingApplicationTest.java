package com.example.frauddetection;

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

/** The whole path from a CSV file to a scoring API, with synthetic data switched off. */
@SpringBootTest(properties = {
        "fraud.data.source=csv",
        "fraud.data.csv-path=data/sample-transactions.csv",
        "fraud.model.dir=target/test-model-csv-${random.uuid}",
        "fraud.training.max-epochs=3"
})
@AutoConfigureMockMvc
class CsvTrainingApplicationTest {

    private static final String TRANSACTION = """
            {
              "transactionId": "csv-1", "amount": 42.10, "customerAvgAmount": 55.0, "hourOfDay": 13,
              "merchantCategory": "GROCERY", "distanceFromHomeKm": 3.2, "distanceFromLastTransactionKm": 2.5,
              "minutesSinceLastTransaction": 720.0, "transactionsLast24h": 2, "failedAttemptsLast24h": 0,
              "accountAgeDays": 1500, "cardNotPresent": false, "usedChip": true, "usedPin": true,
              "foreignTransaction": false, "newDevice": false, "newMerchant": false
            }""";

    @Autowired
    MockMvc mvc;

    @Test
    void trainsFromTheFileOnFirstStartWithTheSameFeatures() throws Exception {
        // 500 rows at a 60/15/25 split: the test split holds 125 rows, not the synthetic default of 3,000
        mvc.perform(get("/api/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.length()").value(27))
                .andExpect(jsonPath("$.trainingSamples").value(300))
                .andExpect(jsonPath("$.evaluation.testSamples").value(125));
    }

    @Test
    void scoresTransactionsWithTheCsvModel() throws Exception {
        mvc.perform(post("/api/transactions/assess").contentType(MediaType.APPLICATION_JSON).content(TRANSACTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("csv-1"))
                .andExpect(jsonPath("$.fraudScore").isNumber());
    }

    @Test
    void keepsTheExistingModelSafeguards() throws Exception {
        mvc.perform(post("/api/model/train"))
                .andExpect(status().isConflict());
    }
}
