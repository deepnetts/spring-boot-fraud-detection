package com.example.frauddetection.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.frauddetection.data.TrainingDataException;

import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Returns errors as RFC 9457 problem details. The base class covers the standard cases, including the
 * reason given in a {@code ResponseStatusException}. The overrides add which fields failed.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_FIELDS = "The request has invalid fields";

    record FieldProblem(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> problems = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, invalidFields(ex.getBody(), problems), headers, status, request);
    }

    /** Validation of method parameters, which here means batch requests. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> problems = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                String row = result.getContainerIndex() != null ? "[" + result.getContainerIndex() + "]." : "";
                errors.getFieldErrors().forEach(e -> problems.add(new FieldProblem(row + e.getField(), e.getDefaultMessage())));
            } else {
                String parameter = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(e -> problems.add(new FieldProblem(parameter, e.getDefaultMessage())));
            }
        }
        return handleExceptionInternal(ex, invalidFields(ex.getBody(), problems), headers, status, request);
    }

    /** A body that can't be read: broken JSON, or a value of the wrong type such as an unknown category. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "The request body is not valid JSON");
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            invalidFields(body, List.of(new FieldProblem(fieldOf(mismatch), expectedBy(mismatch))));
        }
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** The configured training data can't be used, for example a CSV file with invalid rows. */
    @ExceptionHandler(TrainingDataException.class)
    ProblemDetail handleTrainingData(TrainingDataException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), ex.getMessage());
    }

    private static ProblemDetail invalidFields(ProblemDetail body, List<FieldProblem> problems) {
        body.setDetail(INVALID_FIELDS);
        body.setProperty("errors", problems);
        return body;
    }

    private static String fieldOf(MismatchedInputException mismatch) {
        return mismatch.getPath().stream()
                .map(step -> step.getPropertyName() != null ? step.getPropertyName() : "[" + step.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String expectedBy(MismatchedInputException mismatch) {
        Class<?> type = mismatch.getTargetType();
        return type != null && type.isEnum() ? "must be one of " + Arrays.toString(type.getEnumConstants())
                : "has the wrong type";
    }
}
