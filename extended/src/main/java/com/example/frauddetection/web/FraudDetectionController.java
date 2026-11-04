package com.example.frauddetection.web;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.data.DatasetSummary;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.service.FraudAssessment;
import com.example.frauddetection.service.FraudDetectionService;
import com.example.frauddetection.service.FraudDetectionService.MonitoringReport;
import com.example.frauddetection.service.ModelInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class FraudDetectionController {

    private final FraudDetectionService service;
    private final FraudProperties properties;

    public FraudDetectionController(FraudDetectionService service, FraudProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/transactions/assess")
    public FraudAssessment assess(@Valid @RequestBody Transaction transaction) {
        return service.assess(transaction);
    }

    @PostMapping("/transactions/assess/batch")
    public List<FraudAssessment> assessBatch(
            @RequestBody @NotEmpty @Size(max = 1000) List<@Valid Transaction> transactions) {
        return service.assessAll(transactions);
    }

    @GetMapping("/data/summary")
    public DatasetSummary dataSummary() {
        return service.dataSummary();
    }

    /** Generates a random example transaction, handy for trying out the API. */
    @GetMapping("/transactions/sample")
    public Transaction sample(@RequestParam(defaultValue = "false") boolean fraud) {
        var generator = new SyntheticTransactionGenerator(ThreadLocalRandom.current().nextLong());
        return fraud ? generator.fraudulent() : generator.legitimate();
    }

    @GetMapping("/model")
    public ModelInfo model() {
        return service.modelInfo();
    }

    @PostMapping("/model/train")
    public ModelInfo train(@Valid @RequestBody(required = false) TrainingRequest request) {
        var cfg = properties.training();
        boolean overwrite = false;
        if (request != null) {
            cfg = cfg.withOverrides(request.samples(), request.fraudRate(), request.maxEpochs(), request.seed());
            overwrite = Boolean.TRUE.equals(request.overwrite());
        }
        return service.train(cfg, overwrite);
    }

    /** Reports the confirmed outcome of a scored transaction, for example after a chargeback or a review. */
    @PostMapping("/monitoring/outcomes")
    public ResponseEntity<Void> recordOutcome(@Valid @RequestBody OutcomeRequest outcome) {
        if (!service.recordOutcome(outcome.transactionId(), outcome.fraud())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No decision for transaction " + outcome.transactionId()
                    + " is waiting for an outcome. Only transactions scored with a transactionId by the active model can"
                    + " receive one, and each only once");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/monitoring")
    public MonitoringReport monitoring() {
        return service.monitoring();
    }
}
