package com.example.frauddetection.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.data.TrainingDataException;
import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.model.FraudModelTrainer.Split;

class FraudModelTrainerTest {

    private static final Training TRAINING = new Training(2000, 0.05, 0.25, 0.15, List.of(8), 2, 0.001f, 0.0001f, 1);

    private final List<LabeledTransaction> data = new SyntheticTransactionGenerator(11).generate(4000, 0.05);

    @Test
    void splitUsesEveryRowExactlyOnce() {
        Split split = FraudModelTrainer.split(data, TRAINING);

        Set<LabeledTransaction> all = new HashSet<>(split.train());
        all.addAll(split.validation());
        all.addAll(split.test());
        assertEquals(data.size(), split.train().size() + split.validation().size() + split.test().size());
        assertEquals(new HashSet<>(data), all);
    }

    @Test
    void splitHasTheRequestedSizesAndKeepsTheFraudRateInEveryPart() {
        Split split = FraudModelTrainer.split(data, TRAINING);

        assertEquals(1000, split.test().size());
        assertEquals(600, split.validation().size());
        assertEquals(2400, split.train().size());
        assertEquals(0.05, fraudRate(split.train()), 1e-9);
        assertEquals(0.05, fraudRate(split.validation()), 1e-9);
        assertEquals(0.05, fraudRate(split.test()), 1e-9);
    }

    @Test
    void splitIsRepeatableForTheSameSeed() {
        assertEquals(FraudModelTrainer.split(data, TRAINING), FraudModelTrainer.split(data, TRAINING));
    }

    @Test
    void splitDoesNotDependOnTheOrderOfTheInput() {
        // sorted data, like a file ordered by date: taking the first rows would put only the lowest amounts in test
        List<LabeledTransaction> sorted = new ArrayList<>(data);
        sorted.sort(Comparator.comparingDouble(t -> t.transaction().amount()));

        Split split = FraudModelTrainer.split(sorted, TRAINING);

        double median = sorted.get(sorted.size() / 2).transaction().amount();
        long aboveMedian = split.test().stream().filter(t -> t.transaction().amount() > median).count();
        assertTrue(aboveMedian > 400 && aboveMedian < 600, "rows above the median in test: " + aboveMedian);
    }

    @Test
    void recommendedThresholdIsChosenOnValidationDataAndMetricsComeFromTheTestSplit() throws Exception {
        var properties = new FraudProperties(new FraudProperties.Model(Path.of("unused"), false, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.SYNTHETIC, null));

        try (FraudModel model = new FraudModelTrainer(properties).train(data, TRAINING)) {
            Split split = FraudModelTrainer.split(data, TRAINING);
            EvaluationReport report = model.metadata().evaluation();
            double onValidation = ModelEvaluator.recommendThreshold(
                    FraudModelTrainer.scores(model, split.validation()), FraudModelTrainer.labels(split.validation()));

            assertEquals(onValidation, report.recommendedThreshold(), 1e-9);
            assertEquals(1000, report.testSamples());
            assertEquals(50, report.fraudSamples());
        }
    }

    @Test
    void refusesDataWithoutEnoughFraudToEvaluateAModel() {
        var properties = new FraudProperties(new FraudProperties.Model(Path.of("unused"), false, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.SYNTHETIC, null));
        List<LabeledTransaction> rare = new SyntheticTransactionGenerator(11).generate(1000, 0.002);

        var failure = assertThrows(TrainingDataException.class, () -> new FraudModelTrainer(properties).train(rare, TRAINING));

        assertThat(failure.getMessage()).contains("needs both fraud and legitimate rows").contains("1000 rows, 2 of them fraud");
    }

    private static double fraudRate(List<LabeledTransaction> rows) {
        return (double) rows.stream().filter(LabeledTransaction::fraud).count() / rows.size();
    }
}
