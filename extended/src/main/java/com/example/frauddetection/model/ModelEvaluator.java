package com.example.frauddetection.model;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.model.EvaluationReport.ConfusionMatrix;

/**
 * Binary classification metrics suited to rare-event problems like fraud, where plain accuracy
 * is misleading (predicting "legit" for everything is already ~97% accurate).
 */
public final class ModelEvaluator {

    private ModelEvaluator() {
    }

    public static EvaluationReport evaluate(float[] scores, boolean[] labels, double threshold,
            double recommendedThreshold) {
        ConfusionMatrix cm = confusionMatrix(scores, labels, threshold);
        ConfusionMatrix recommended = confusionMatrix(scores, labels, recommendedThreshold);
        return new EvaluationReport(
                labels.length,
                cm.truePositives() + cm.falseNegatives(),
                threshold,
                rocAuc(scores, labels),
                averagePrecision(scores, labels),
                ratio(cm.truePositives() + cm.trueNegatives(), labels.length),
                precision(cm),
                recall(cm),
                f1(cm),
                ratio(cm.falsePositives(), cm.falsePositives() + cm.trueNegatives()),
                cm,
                recommendedThreshold,
                precision(recommended),
                recall(recommended),
                f1(recommended));
    }

    /**
     * The threshold with the best F1. Use validation data: choosing it on the test set would make the
     * reported test results look better than they are.
     */
    public static double recommendThreshold(float[] scores, boolean[] labels) {
        double bestThreshold = 0.5;
        double bestF1 = -1;
        for (int i = 1; i < 100; i++) {
            double t = i / 100.0;
            double f1 = f1(confusionMatrix(scores, labels, t));
            if (f1 > bestF1) {
                bestF1 = f1;
                bestThreshold = t;
            }
        }
        return bestThreshold;
    }

    /** PR AUC of a model on labelled transactions, for comparing two models on the same rows. */
    public static double prAuc(FraudModel model, List<LabeledTransaction> rows) {
        return averagePrecision(FraudModelTrainer.scores(model, rows), FraudModelTrainer.labels(rows));
    }

    /**
     * PR AUC as average precision: the precision reached at each recall step, averaged over all fraud
     * cases. Unlike ROC AUC it reacts to false positives even when legitimate traffic vastly outnumbers
     * fraud. A model with no skill scores about the fraud rate.
     */
    static double averagePrecision(float[] scores, boolean[] labels) {
        Integer[] order = new Integer[labels.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, Comparator.comparingDouble(i -> -scores[i]));

        long positives = 0;
        for (boolean label : labels) {
            if (label) {
                positives++;
            }
        }
        if (positives == 0) {
            return Double.NaN;
        }

        double sum = 0;
        long truePositives = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && scores[order[j + 1]] == scores[order[i]]) {
                j++;
            }
            long found = 0;
            for (int k = i; k <= j; k++) {
                if (labels[order[k]]) {
                    found++;
                }
            }
            truePositives += found;
            sum += found * ((double) truePositives / (j + 1));
            i = j + 1;
        }
        return sum / positives;
    }

    static ConfusionMatrix confusionMatrix(float[] scores, boolean[] labels, double threshold) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < labels.length; i++) {
            boolean predicted = scores[i] >= threshold;
            if (predicted && labels[i]) tp++;
            else if (predicted) fp++;
            else if (labels[i]) fn++;
            else tn++;
        }
        return new ConfusionMatrix(tp, fp, tn, fn);
    }

    /** ROC AUC via the Mann-Whitney U statistic, with ties given half credit. */
    static double rocAuc(float[] scores, boolean[] labels) {
        Integer[] order = new Integer[labels.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));

        double rankSumPositives = 0;
        long positives = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && scores[order[j + 1]] == scores[order[i]]) {
                j++;
            }
            double averageRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                if (labels[order[k]]) {
                    rankSumPositives += averageRank;
                    positives++;
                }
            }
            i = j + 1;
        }
        long negatives = labels.length - positives;
        if (positives == 0 || negatives == 0) {
            return Double.NaN;
        }
        return (rankSumPositives - positives * (positives + 1) / 2.0) / ((double) positives * negatives);
    }

    private static double precision(ConfusionMatrix cm) {
        return ratio(cm.truePositives(), cm.truePositives() + cm.falsePositives());
    }

    private static double recall(ConfusionMatrix cm) {
        return ratio(cm.truePositives(), cm.truePositives() + cm.falseNegatives());
    }

    private static double f1(ConfusionMatrix cm) {
        double p = precision(cm);
        double r = recall(cm);
        return p + r == 0 ? 0 : 2 * p * r / (p + r);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
