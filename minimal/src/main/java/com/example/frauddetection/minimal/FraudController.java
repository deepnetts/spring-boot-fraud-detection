package com.example.frauddetection.minimal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

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

    /** Names every invalid field in the {@code 400} response, for example "hourOfDay must be less than or equal to 23". */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException e) {
        String problems = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid fields: " + problems);
    }
}
