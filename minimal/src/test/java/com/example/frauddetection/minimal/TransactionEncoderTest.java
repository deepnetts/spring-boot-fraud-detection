package com.example.frauddetection.minimal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionEncoderTest {

    private final TransactionEncoder encoder = new TransactionEncoder();

    @Test
    void encodesTheFieldsInFeatureOrder() {
        var transaction = new TransactionRequest(950.0, 45.0, 3, 850.0, 11, 3, true, false, true);

        assertThat(encoder.encodeToTensor(transaction).getValues())
                .hasSize(TransactionEncoder.FEATURE_NAMES.size())
                .containsExactly(950f, 45f, 3f, 850f, 11f, 3f, 1f, 0f, 1f);
    }

    @Test
    void featureNamesMatchTheCsvColumns() throws IOException {
        for (String file : List.of("data/train.csv", "data/test.csv")) {
            String header = Files.readAllLines(Path.of(file)).get(0);
            assertThat(List.of(header.split(","))).containsExactlyElementsOf(
                    Stream.concat(TransactionEncoder.FEATURE_NAMES.stream(), Stream.of("fraud")).toList());
        }
    }
}
