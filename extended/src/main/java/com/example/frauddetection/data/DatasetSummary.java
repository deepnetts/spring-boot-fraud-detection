package com.example.frauddetection.data;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;

/** A quick look at the training data: how much there is, how imbalanced it is, and where fraud falls. */
public record DatasetSummary(String source, int rows, int fraudRows, double fraudRate, List<CategorySummary> categories) {

    public record CategorySummary(MerchantCategory category, int rows, int fraudRows, double fraudRate) {
    }

    public static DatasetSummary of(String source, List<LabeledTransaction> data) {
        Map<MerchantCategory, List<LabeledTransaction>> byCategory = data.stream().collect(Collectors.groupingBy(
                row -> row.transaction().merchantCategory(), () -> new EnumMap<>(MerchantCategory.class), Collectors.toList()));
        List<CategorySummary> categories = byCategory.entrySet().stream()
                .map(entry -> new CategorySummary(entry.getKey(), entry.getValue().size(), frauds(entry.getValue()),
                        fraudRate(entry.getValue())))
                .toList();
        return new DatasetSummary(source, data.size(), frauds(data), fraudRate(data), categories);
    }

    private static int frauds(List<LabeledTransaction> rows) {
        return (int) rows.stream().filter(LabeledTransaction::fraud).count();
    }

    private static double fraudRate(List<LabeledTransaction> rows) {
        return (double) frauds(rows) / rows.size();
    }
}
