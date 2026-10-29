package com.example.frauddetection.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.Transaction;

class ModelRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void reloadedModelPredictsSameAsTrainedModel() throws Exception {
        var properties = new FraudProperties(
                new FraudProperties.Model(dir, false, 0.5),
                new FraudProperties.Training(2000, 0.05, 0.25, 0.3, List.of(8), 2, 0.001f, 0.0001f, 1));
        var repository = new ModelRepository(properties);
        var generator = new SyntheticTransactionGenerator(7);
        List<Transaction> transactions = List.of(generator.fraudulent(), generator.legitimate(), generator.fraudulent());

        try (FraudModel trained = new FraudModelTrainer(properties).train(properties.training())) {
            repository.save(trained);
            try (FraudModel loaded = repository.load().orElseThrow()) {
                for (Transaction tx : transactions) {
                    assertEquals(trained.predict(tx), loaded.predict(tx), 1e-6);
                }
            }
        }
    }
}
