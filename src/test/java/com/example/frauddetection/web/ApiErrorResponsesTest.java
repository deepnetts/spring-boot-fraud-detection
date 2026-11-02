package com.example.frauddetection.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.data.TrainingDataException;
import com.example.frauddetection.service.FraudDetectionService;

@WebMvcTest(FraudDetectionController.class)
@EnableConfigurationProperties(FraudProperties.class)
class ApiErrorResponsesTest {

    private static final String VALID = """
            {
              "amount": 42.10, "customerAvgAmount": 55.0, "hourOfDay": 13, "merchantCategory": "GROCERY",
              "distanceFromHomeKm": 3.2, "distanceFromLastTransactionKm": 2.5, "minutesSinceLastTransaction": 720.0,
              "transactionsLast24h": 2, "failedAttemptsLast24h": 0, "accountAgeDays": 1500, "cardNotPresent": false,
              "usedChip": true, "usedPin": true, "foreignTransaction": false, "newDevice": false, "newMerchant": false
            }""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FraudDetectionService service;

    @Test
    void namesEachInvalidField() throws Exception {
        post("/api/transactions/assess", VALID.replace("42.10", "-5").replace("\"hourOfDay\": 13", "\"hourOfDay\": 30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].message").value(hasItem("must be greater than or equal to 0")))
                .andExpect(jsonPath("$.errors[?(@.field == 'hourOfDay')].message").value(hasItem("must be less than or equal to 23")));
    }

    @Test
    void namesAMissingField() throws Exception {
        post("/api/transactions/assess", VALID.replace("\"accountAgeDays\": 1500, ", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountAgeDays"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be null"));
    }

    @Test
    void listsTheAllowedValuesForAnUnknownCategory() throws Exception {
        post("/api/transactions/assess", VALID.replace("GROCERY", "CASINO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("merchantCategory"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("GROCERY")));
    }

    @Test
    void namesAFieldWithTheWrongType() throws Exception {
        post("/api/transactions/assess", VALID.replace("\"hourOfDay\": 13", "\"hourOfDay\": \"noon\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("hourOfDay"));
    }

    @Test
    void explainsMalformedJson() throws Exception {
        post("/api/transactions/assess", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is not valid JSON"));
    }

    @Test
    void namesTheRowAndFieldInABatch() throws Exception {
        post("/api/transactions/assess/batch", "[" + VALID + "," + VALID.replace("42.10", "-5") + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("[1].amount"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
    }

    @Test
    void rejectsAnEmptyBatch() throws Exception {
        post("/api/transactions/assess/batch", "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transactions"));
    }

    @Test
    void rejectsABatchThatIsTooLarge() throws Exception {
        post("/api/transactions/assess/batch", "[" + String.join(",", Collections.nCopies(1001, VALID)) + "]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transactions"));
    }

    @Test
    void validatesTrainingRequests() throws Exception {
        post("/api/model/train", "{\"samples\": 5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("samples"));
    }

    @Test
    void explainsWhyNoModelIsAvailable() throws Exception {
        when(service.modelInfo()).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No fraud model is loaded yet"));

        mvc.perform(get("/api/model"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("No fraud model is loaded yet"));
    }

    @Test
    void explainsWhyTrainingWasRefused() throws Exception {
        when(service.train(any(), anyBoolean())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A model already exists"));

        post("/api/model/train", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A model already exists"));
    }

    @Test
    void explainsUnusableTrainingData() throws Exception {
        when(service.train(any(), anyBoolean())).thenThrow(new TrainingDataException("Invalid CSV file data.csv (1 problem)"));

        post("/api/model/train", "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Invalid CSV file data.csv (1 problem)"));
    }

    @Test
    void explainsWhyTheDataCannotBeSummarized() throws Exception {
        when(service.dataSummary()).thenThrow(new TrainingDataException("CSV file not found: data.csv"));

        mvc.perform(get("/api/data/summary"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("CSV file not found: data.csv"));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
