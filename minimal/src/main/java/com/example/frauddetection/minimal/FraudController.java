package com.example.frauddetection.minimal;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudModel model;

    public FraudController(FraudModel model) {
        this.model = model;
    }

    /** Scores one transaction. An invalid request gets a {@code 400} response and no score. */
    @PostMapping("/predict")
    public FraudPrediction predict(@Valid @RequestBody TransactionRequest transaction) {
        return model.predict(transaction);
    }
}
