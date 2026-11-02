package com.example.frauddetection.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Reads labelled transactions from a CSV file in the format described in docs/DATA_CONTRACT.md. Rows
 * become the same {@link Transaction} records the API receives, so they go through the same validation
 * and the same feature engineering.
 * <p>
 * This is a plain reader for files with simple values, not a general CSV parser: it splits on commas and
 * doesn't support quoted values. Any invalid row fails the whole load, because silently dropping rows
 * could change the fraud rate.
 */
public final class CsvTransactionReader {

    private static final Logger log = LoggerFactory.getLogger(CsvTransactionReader.class);

    private static final int MAX_LISTED_PROBLEMS = 20;
    private static final String LABEL = "fraud";
    private static final String ID = "transactionId";
    // TODO(customize): keep these in step with Transaction if you add or remove fields
    private static final List<String> REQUIRED_COLUMNS = List.of("amount", "customerAvgAmount", "hourOfDay",
            "merchantCategory", "distanceFromHomeKm", "distanceFromLastTransactionKm", "minutesSinceLastTransaction",
            "transactionsLast24h", "failedAttemptsLast24h", "accountAgeDays", "cardNotPresent", "usedChip", "usedPin",
            "foreignTransaction", "newDevice", "newMerchant", LABEL);
    // accepted for future evaluation strategies, never read: they must not become model features
    private static final Set<String> METADATA_COLUMNS = Set.of("timestamp", "customerId");
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private CsvTransactionReader() {
    }

    public static List<LabeledTransaction> read(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                throw new TrainingDataException("CSV file is empty: " + file.toAbsolutePath());
            }
            Map<String, Integer> columns = columns(header, file);

            List<LabeledTransaction> rows = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            int lineNumber = 1;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lineNumber++;
                if (!line.isBlank()) {
                    readRow(line, lineNumber, columns, rows, problems);
                }
            }
            if (!problems.isEmpty()) {
                throw new TrainingDataException(describe(problems, file));
            }
            if (rows.isEmpty()) {
                throw new TrainingDataException("CSV file has no data rows: " + file.toAbsolutePath());
            }
            return rows;
        } catch (NoSuchFileException e) {
            throw new TrainingDataException("CSV file not found: " + file.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new TrainingDataException("Could not read CSV file " + file.toAbsolutePath() + ": " + e, e);
        }
    }

    private static Map<String, Integer> columns(String header, Path file) {
        Map<String, Integer> columns = new HashMap<>();
        // Excel saves UTF-8 files with a byte order mark
        String[] names = split(header.replace("﻿", ""));
        for (int i = 0; i < names.length; i++) {
            if (columns.put(names[i], i) != null) {
                throw new TrainingDataException("Column " + names[i] + " appears more than once in " + file.toAbsolutePath());
            }
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(name -> !columns.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new TrainingDataException("Missing required columns " + missing + " in " + file.toAbsolutePath()
                    + ". Found " + Arrays.toString(names));
        }
        List<String> ignored = columns.keySet().stream()
                .filter(name -> !REQUIRED_COLUMNS.contains(name) && !METADATA_COLUMNS.contains(name) && !ID.equals(name))
                .sorted().toList();
        if (!ignored.isEmpty()) {
            log.warn("Ignoring unknown columns {} in {}", ignored, file.toAbsolutePath());
        }
        return columns;
    }

    private static void readRow(String line, int lineNumber, Map<String, Integer> columns,
            List<LabeledTransaction> rows, List<String> problems) {
        String[] values = split(line);
        if (values.length != columns.size()) {
            problems.add("line " + lineNumber + ": expected " + columns.size() + " columns, found " + values.length);
            return;
        }
        int problemsBefore = problems.size();
        Row row = new Row(values, columns, lineNumber, problems);
        Transaction transaction = new Transaction(
                row.id(),
                row.number("amount"),
                row.number("customerAvgAmount"),
                row.wholeNumber("hourOfDay"),
                row.category("merchantCategory"),
                row.number("distanceFromHomeKm"),
                row.number("distanceFromLastTransactionKm"),
                row.number("minutesSinceLastTransaction"),
                row.wholeNumber("transactionsLast24h"),
                row.wholeNumber("failedAttemptsLast24h"),
                row.wholeNumber("accountAgeDays"),
                row.flag("cardNotPresent"),
                row.flag("usedChip"),
                row.flag("usedPin"),
                row.flag("foreignTransaction"),
                row.flag("newDevice"),
                row.flag("newMerchant"));
        Boolean fraud = row.flag(LABEL);
        if (problems.size() == problemsBefore) {
            VALIDATOR.validate(transaction)
                    .forEach(violation -> row.problem(violation.getPropertyPath().toString(), violation.getMessage()));
        }
        if (problems.size() == problemsBefore) {
            rows.add(new LabeledTransaction(transaction, fraud));
        }
    }

    private static String[] split(String line) {
        return Arrays.stream(line.split(",", -1)).map(String::trim).toArray(String[]::new);
    }

    private static String describe(List<String> problems, Path file) {
        StringBuilder message = new StringBuilder("Invalid CSV file " + file.toAbsolutePath() + " (" + problems.size()
                + (problems.size() == 1 ? " problem):" : " problems):"));
        problems.stream().limit(MAX_LISTED_PROBLEMS).forEach(problem -> message.append("\n  ").append(problem));
        if (problems.size() > MAX_LISTED_PROBLEMS) {
            message.append("\n  ... and ").append(problems.size() - MAX_LISTED_PROBLEMS).append(" more");
        }
        return message.toString();
    }

    /** One line of the file. A value that can't be read is reported and returned as null. */
    private record Row(String[] values, Map<String, Integer> columns, int lineNumber, List<String> problems) {

        String id() {
            Integer column = columns.get(ID);
            return column == null || values[column].isEmpty() ? null : values[column];
        }

        Double number(String column) {
            String value = required(column);
            if (value == null) {
                return null;
            }
            try {
                double number = Double.parseDouble(value);
                if (Double.isFinite(number)) {
                    return number;
                }
            } catch (NumberFormatException e) {
                // reported below
            }
            problem(column, "\"" + value + "\" is not a number");
            return null;
        }

        Integer wholeNumber(String column) {
            String value = required(column);
            if (value == null) {
                return null;
            }
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException e) {
                problem(column, "\"" + value + "\" is not a whole number");
                return null;
            }
        }

        Boolean flag(String column) {
            String value = required(column);
            if (value == null) {
                return null;
            }
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                return Boolean.valueOf(value);
            }
            problem(column, "\"" + value + "\" must be true or false");
            return null;
        }

        MerchantCategory category(String column) {
            String value = required(column);
            if (value == null) {
                return null;
            }
            try {
                return MerchantCategory.valueOf(value);
            } catch (IllegalArgumentException e) {
                problem(column, "\"" + value + "\" must be one of " + Arrays.toString(MerchantCategory.values()));
                return null;
            }
        }

        void problem(String column, String message) {
            problems.add("line " + lineNumber + ", column " + column + ": " + message);
        }

        private String required(String column) {
            String value = values[columns.get(column)];
            if (value.isEmpty()) {
                problem(column, "value is missing");
                return null;
            }
            return value;
        }
    }
}
