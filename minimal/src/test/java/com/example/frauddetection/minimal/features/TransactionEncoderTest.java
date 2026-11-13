package com.example.frauddetection.minimal.features;

import com.example.frauddetection.minimal.domain.Transaction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionEncoderTest {

    private final TransactionEncoder encoder = new TransactionEncoder();

    @Test
    void encodesTheFieldsInFeatureOrder() {
        var transaction = new Transaction(950.0, 45.0, 3, 850.0, 11, 3, true, false, true);

        assertThat(encoder.encodeToTensor(transaction).getValues())
                .hasSize(TransactionEncoder.FEATURE_NAMES.size())
                .containsExactly(log(950), log(45), 3f, log(850), 11f, 3f, 1f, 0f, 1f);
    }

    @Test
    void keepsVeryLargeAmountsInARangeTheNetworkHasSeen() {
        var huge = new Transaction(1e12, 45.0, 3, 850.0, 11, 3, true, false, true);

        assertThat(encoder.encodeToTensor(huge).getValues()[0]).isLessThan(30f);
    }

    @Test
    void csvColumnsAreTheTransactionFieldsInOrder() throws IOException {
        List<String> fields = Stream.of(Transaction.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        for (String file : List.of("data/train.csv", "data/test.csv")) {
            String header = Files.readAllLines(Path.of(file)).get(0);
            assertThat(List.of(header.split(","))).containsExactlyElementsOf(
                    Stream.concat(fields.stream(), Stream.of("fraud")).toList());
        }
    }

    private static float log(double value) {
        return (float) Math.log1p(value);
    }
}
