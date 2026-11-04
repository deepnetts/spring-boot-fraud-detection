package com.example.frauddetection.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.frauddetection.data.DatasetSummary.CategorySummary;
import com.example.frauddetection.data.DatasetSummary.FeatureComparison;
import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

class DatasetSummaryTest {

    @Test
    void countsRowsFraudAndCategories() {
        List<LabeledTransaction> data = List.of(
                row(MerchantCategory.TRAVEL, true),
                row(MerchantCategory.GROCERY, false),
                row(MerchantCategory.GROCERY, false),
                row(MerchantCategory.GROCERY, true));

        DatasetSummary summary = DatasetSummary.of("csv", data);

        assertEquals("csv", summary.source());
        assertEquals(4, summary.rows());
        assertEquals(2, summary.fraudRows());
        assertEquals(0.5, summary.fraudRate(), 1e-9);
        // in the order of the enum, leaving out categories with no rows
        assertEquals(List.of(new CategorySummary(MerchantCategory.GROCERY, 3, 1, 1.0 / 3),
                new CategorySummary(MerchantCategory.TRAVEL, 1, 1, 1.0)), summary.categories());
    }

    @Test
    void comparesFraudWithLegitimateRows() {
        List<LabeledTransaction> data = List.of(
                row(MerchantCategory.GROCERY, false, 10.0, 14, false),
                row(MerchantCategory.GROCERY, false, 30.0, 15, false),
                row(MerchantCategory.ELECTRONICS, true, 500.0, 3, true));

        DatasetSummary summary = DatasetSummary.of("synthetic", data);

        assertEquals(new FeatureComparison("amount", 20.0, 500.0), feature(summary, "amount"));
        assertEquals(new FeatureComparison("amountToCustomerAverage", 2.0, 50.0), feature(summary, "amountToCustomerAverage"));
        // a yes/no field is averaged as the share of rows where it is true
        assertEquals(new FeatureComparison("night (hours 0 to 5)", 0.0, 1.0), feature(summary, "night (hours 0 to 5)"));
        assertEquals(new FeatureComparison("newDevice", 0.0, 1.0), feature(summary, "newDevice"));
    }

    @Test
    void leavesAnAverageEmptyWhenThereAreNoRowsOfThatKind() {
        DatasetSummary summary = DatasetSummary.of("csv", List.of(row(MerchantCategory.GROCERY, false)));

        assertEquals(new FeatureComparison("amount", 10.0, null), feature(summary, "amount"));
    }

    private static FeatureComparison feature(DatasetSummary summary, String name) {
        return summary.features().stream().filter(f -> f.feature().equals(name)).findFirst().orElseThrow();
    }

    private static LabeledTransaction row(MerchantCategory category, boolean fraud) {
        return row(category, fraud, 10.0, 12, false);
    }

    private static LabeledTransaction row(MerchantCategory category, boolean fraud, double amount, int hour, boolean newDevice) {
        return new LabeledTransaction(new Transaction(null, amount, 10.0, hour, category, 1.0, 1.0, 60.0, 1, 0, 100,
                true, false, false, false, newDevice, false), fraud);
    }
}
