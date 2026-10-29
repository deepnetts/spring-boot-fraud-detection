package com.example.frauddetection.domain;

public record LabeledTransaction(Transaction transaction, boolean fraud) {
}
