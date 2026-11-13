package com.example.frauddetection.minimal.model;

import com.example.frauddetection.minimal.domain.Transaction;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudModelTest {

    static final Path TRAINING_DATA = Path.of("data/train.csv");
    static final Path TEST_DATA = Path.of("data/test.csv");

    static final Transaction SUSPICIOUS =
            new Transaction(950.0, 45.0, 3, 850.0, 11, 3, true, true, true);
    static final Transaction ORDINARY =
            new Transaction(42.10, 55.0, 13, 3.2, 2, 0, false, false, false);

    static FeedForwardNetwork network;

    @BeforeAll
    static void trainOnce() throws IOException {
        network = FraudModel.train(FraudModel.loadData(TRAINING_DATA));
    }

    @Test
    void readsLabelledTransactionsFromCsv() throws IOException {
        List<FraudModel.LabeledTransaction> data = FraudModel.loadData(TRAINING_DATA);

        assertThat(data).hasSize(7500);
        assertThat(data.stream().filter(FraudModel.LabeledTransaction::fraud)).hasSize(225);
        // first row of train.csv: 63.89,36.74,15,0.03,1,1,0,0,0,0
        assertThat(data.get(0).transaction()).isEqualTo(
                new Transaction((double) 63.89f, (double) 36.74f, 15, (double) 0.03f, 1, 1, false, false, false));
        assertThat(data.get(0).fraud()).isFalse();
    }

    @Test
    void trainingDataHasTwoOutputsPerTransaction() throws IOException {
        TabularDataSet<MLDataItem> data = FraudModel.toDataSet(FraudModel.loadData(TRAINING_DATA));

        assertThat(data.size()).isEqualTo(7500);
        assertThat(data.get(0).getInput().getValues()).hasSize(FraudModel.INPUTS);
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
        List<FraudModel.LabeledTransaction> raw = FraudModel.loadData(TEST_DATA);
        TabularDataSet<MLDataItem> scaled = FraudModel.toDataSet(raw);
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
            Tensor1D inputs = FraudModel.ENCODER.encodeToTensor(raw.get(i).transaction());
            assertThat(FraudModel.fraudScore(network, inputs)).isEqualTo(withoutStoredScaler[i]);
        }
    }

    @Test
    void scoringLeavesTheInputsUnscaled() {
        Tensor1D inputs = FraudModel.ENCODER.encodeToTensor(SUSPICIOUS);
        float[] before = inputs.getValues().clone();

        float first = FraudModel.fraudScore(network, inputs);

        assertThat(inputs.getValues()).containsExactly(before);
        assertThat(FraudModel.fraudScore(network, inputs)).isEqualTo(first);
    }

    @Test
    void savedModelGivesIdenticalScores(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("model/fraud-model.dnet");
        FraudModel.save(network, file);
        FeedForwardNetwork loaded = FraudModel.load(file);

        for (FraudModel.LabeledTransaction t : FraudModel.loadData(TEST_DATA)) {
            Tensor1D inputs = FraudModel.ENCODER.encodeToTensor(t.transaction());
            assertThat(FraudModel.fraudScore(loaded, inputs)).isEqualTo(FraudModel.fraudScore(network, inputs));
        }
    }

    @Test
    void scoresASuspiciousTransactionAboveAnOrdinaryOne() {
        float suspicious = FraudModel.fraudScore(network, FraudModel.ENCODER.encodeToTensor(SUSPICIOUS));
        float ordinary = FraudModel.fraudScore(network, FraudModel.ENCODER.encodeToTensor(ORDINARY));

        assertThat(suspicious).isGreaterThan(0.5f);
        assertThat(ordinary).isLessThan(0.5f);
    }

    @Test
    void aVeryLargeSuspiciousPaymentStaysSuspicious() {
        for (double amount : new double[] {10_000, 100_000, 1_000_000, 1e9}) {
            var payment = new Transaction(amount, 45.0, 3, 850.0, 11, 3, true, true, true);
            assertThat(FraudModel.fraudScore(network, FraudModel.ENCODER.encodeToTensor(payment)))
                    .as("amount %.0f", amount).isGreaterThan(0.5f);
        }
    }
}
