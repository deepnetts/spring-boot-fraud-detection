package com.example.frauddetection.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.frauddetection.data.DatasetSummary.CategorySummary;
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

    private static LabeledTransaction row(MerchantCategory category, boolean fraud) {
        return new LabeledTransaction(new Transaction(null, 10.0, 10.0, 12, category, 1.0, 1.0, 60.0, 1, 0, 100,
                false, true, true, false, false, false), fraud);
    }
}
