package com.example.frauddetection.web;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.service.FraudAssessment;
import com.example.frauddetection.service.FraudDetectionService;
import com.example.frauddetection.service.ModelInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Validated
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
        if (request != null) {
            cfg = cfg.withOverrides(request.samples(), request.fraudRate(), request.maxEpochs(), request.seed());
        }
        return service.train(cfg);
    }
}
