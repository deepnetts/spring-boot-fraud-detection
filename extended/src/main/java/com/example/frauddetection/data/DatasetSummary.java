package com.example.frauddetection.data;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;

/**
 * A quick look at the training data: how much there is, how imbalanced it is, where fraud falls,
 * and how fraud differs from legitimate transactions.
 */
public record DatasetSummary(String source, int rows, int fraudRows, double fraudRate, List<CategorySummary> categories,
        List<FeatureComparison> features) {

    public record CategorySummary(MerchantCategory category, int rows, int fraudRows, double fraudRate) {
    }

    /**
     * Average of one field in legitimate and in fraudulent rows. For a yes/no field the average is the share of rows
     * where it is true. An average is {@code null} when there are no rows of that kind.
     */
    public record FeatureComparison(String feature, Double legitimateAverage, Double fraudAverage) {
    }

    private record Field(String name, ToDoubleFunction<Transaction> value) {
    }

    private static final List<Field> COMPARED_FIELDS = List.of(
            new Field("amount", Transaction::amount),
            new Field("amountToCustomerAverage", t -> t.amount() / t.customerAvgAmount()),
            new Field("night (hours 0 to 5)", t -> FeatureExtractor.isNight(t.hourOfDay()) ? 1 : 0),
            new Field("distanceFromHomeKm", Transaction::distanceFromHomeKm),
            new Field("transactionsLast24h", Transaction::transactionsLast24h),
            new Field("failedAttemptsLast24h", Transaction::failedAttemptsLast24h),
            new Field("cardNotPresent", t -> t.cardNotPresent() ? 1 : 0),
            new Field("newDevice", t -> t.newDevice() ? 1 : 0),
            new Field("foreignTransaction", t -> t.foreignTransaction() ? 1 : 0));

    public static DatasetSummary of(String source, List<LabeledTransaction> data) {
        Map<MerchantCategory, List<LabeledTransaction>> byCategory = data.stream().collect(Collectors.groupingBy(
                row -> row.transaction().merchantCategory(), () -> new EnumMap<>(MerchantCategory.class), Collectors.toList()));
        List<CategorySummary> categories = byCategory.entrySet().stream()
                .map(entry -> new CategorySummary(entry.getKey(), entry.getValue().size(), frauds(entry.getValue()),
                        fraudRate(entry.getValue())))
                .toList();
        List<FeatureComparison> features = COMPARED_FIELDS.stream()
                .map(field -> new FeatureComparison(field.name(), average(data, field, false), average(data, field, true)))
                .toList();
        return new DatasetSummary(source, data.size(), frauds(data), fraudRate(data), categories, features);
    }

    private static Double average(List<LabeledTransaction> data, Field field, boolean fraud) {
        var values = data.stream().filter(row -> row.fraud() == fraud).mapToDouble(row -> field.value().applyAsDouble(row.transaction()));
        var average = values.average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    private static int frauds(List<LabeledTransaction> rows) {
        return (int) rows.stream().filter(LabeledTransaction::fraud).count();
    }

    private static double fraudRate(List<LabeledTransaction> rows) {
        return (double) frauds(rows) / rows.size();
    }
}
