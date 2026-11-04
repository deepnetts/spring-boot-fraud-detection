package com.example.frauddetection.minimal;

import deepnetts.data.MLDataItem;
import deepnetts.data.TabularDataSet;
import deepnetts.data.norm.AbstractScaler;
import deepnetts.net.FeedForwardNetwork;
import deepnetts.tensor.Tensor1D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudModelTest {

    static final Path TRAINING_DATA = Path.of("data/train.csv");
    static final Path TEST_DATA = Path.of("data/test.csv");

    static final TransactionRequest SUSPICIOUS =
            new TransactionRequest(950.0, 45.0, 3, 850.0, 11, 3, true, true, true);
    static final TransactionRequest ORDINARY =
            new TransactionRequest(42.10, 55.0, 13, 3.2, 2, 0, false, false, false);

    static FeedForwardNetwork network;

    @BeforeAll
    static void trainOnce() throws IOException {
        network = FraudModel.train(FraudModel.loadData(TRAINING_DATA));
    }

    @Test
    void readsLabelledTransactionsFromCsv() throws IOException {
        TabularDataSet<MLDataItem> data = FraudModel.loadData(TRAINING_DATA);

        assertThat(data.size()).isEqualTo(7500);
        assertThat(data.stream().filter(row -> row.getTargetOutput().getValues()[FraudModel.FRAUD] == 1f)).hasSize(225);
        // first row of train.csv: 63.89,36.74,15,0.03,1,1,0,0,0,0
        assertThat(data.get(0).getInput().getValues()).containsExactly(63.89f, 36.74f, 15f, 0.03f, 1f, 1f, 0f, 0f, 0f);
        assertThat(data.get(0).getTargetOutput().getValues()).containsExactly(1f, 0f);
    }

    @Test
    void catchesFraudThatAccuracyAloneWouldHide() throws IOException {
        FraudModel.Evaluation evaluation = FraudModel.evaluate(network, FraudModel.loadData(TEST_DATA), 0.5);

        assertThat(evaluation.fraudCases()).isEqualTo(75);
        assertThat(evaluation.recall()).isGreaterThan(0.3);
        assertThat(evaluation.precision()).isGreaterThan(0.7);
        assertThat(evaluation.accuracy()).isGreaterThan(evaluation.accuracyWhenFlaggingNothing());
    }

    @Test
    void networkScalesUnscaledInputsWithItsStoredScaler() throws IOException {
        TabularDataSet<MLDataItem> raw = FraudModel.loadData(TEST_DATA);
        TabularDataSet<MLDataItem> scaled = FraudModel.loadData(TEST_DATA);
        AbstractScaler scaler = network.getNormalizer();
        scaler.apply(scaled);

        float[] withoutStoredScaler = new float[scaled.size()];
        network.setNormalizer(null);
        try {
            for (int i = 0; i < scaled.size(); i++) {
                withoutStoredScaler[i] = network.predict(Tensor1D.of(scaled.get(i).getInput().getValues().clone()))
                        .getValues()[FraudModel.FRAUD];
            }
        } finally {
            network.setNormalizer(scaler);
        }
        for (int i = 0; i < raw.size(); i++) {
            assertThat(FraudModel.fraudScore(network, raw.get(i).getInput().getValues())).isEqualTo(withoutStoredScaler[i]);
        }
    }

    @Test
    void savedModelGivesIdenticalScores(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("model/fraud-model.dnet");
        FraudModel.save(network, file);
        FeedForwardNetwork loaded = FraudModel.load(file);

        for (MLDataItem row : FraudModel.loadData(TEST_DATA)) {
            float[] inputs = row.getInput().getValues();
            assertThat(FraudModel.fraudScore(loaded, inputs)).isEqualTo(FraudModel.fraudScore(network, inputs));
        }
    }

    @Test
    void scoresASuspiciousTransactionAboveAnOrdinaryOne() {
        float suspicious = FraudModel.fraudScore(network, FraudModel.toInputs(SUSPICIOUS));
        float ordinary = FraudModel.fraudScore(network, FraudModel.toInputs(ORDINARY));

        assertThat(suspicious).isGreaterThan(0.5f);
        assertThat(ordinary).isLessThan(0.5f);
    }
}
