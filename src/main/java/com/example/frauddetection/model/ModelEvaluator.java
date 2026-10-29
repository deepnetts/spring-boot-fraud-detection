package com.example.frauddetection.model;

import java.util.Arrays;
import java.util.Comparator;

import com.example.frauddetection.model.EvaluationReport.ConfusionMatrix;

/**
 * Binary classification metrics suited to rare-event problems like fraud, where plain accuracy
 * is misleading (predicting "legit" for everything is already ~97% accurate).
 */
public final class ModelEvaluator {

    private ModelEvaluator() {
    }

    public static EvaluationReport evaluate(float[] probabilities, boolean[] labels, double threshold) {
        ConfusionMatrix cm = confusionMatrix(probabilities, labels, threshold);
        int frauds = cm.truePositives() + cm.falseNegatives();

        double bestThreshold = threshold;
        double bestF1 = -1;
        for (int i = 1; i < 100; i++) {
            double t = i / 100.0;
            double f1 = f1(confusionMatrix(probabilities, labels, t));
            if (f1 > bestF1) {
                bestF1 = f1;
                bestThreshold = t;
            }
        }

        return new EvaluationReport(
                labels.length,
                frauds,
                threshold,
                rocAuc(probabilities, labels),
                ratio(cm.truePositives() + cm.trueNegatives(), labels.length),
                precision(cm),
                recall(cm),
                f1(cm),
                ratio(cm.falsePositives(), cm.falsePositives() + cm.trueNegatives()),
                cm,
                bestThreshold,
                bestF1);
    }

    static ConfusionMatrix confusionMatrix(float[] probabilities, boolean[] labels, double threshold) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < labels.length; i++) {
            boolean predicted = probabilities[i] >= threshold;
            if (predicted && labels[i]) tp++;
            else if (predicted) fp++;
            else if (labels[i]) fn++;
            else tn++;
        }
        return new ConfusionMatrix(tp, fp, tn, fn);
    }

    /** ROC AUC via the Mann-Whitney U statistic, with ties given half credit. */
    static double rocAuc(float[] probabilities, boolean[] labels) {
        Integer[] order = new Integer[labels.length];
        Arrays.setAll(order, i -> i);
        Arrays.sort(order, Comparator.comparingDouble(i -> probabilities[i]));

        double rankSumPositives = 0;
        long positives = 0;
        int i = 0;
        while (i < order.length) {
            int j = i;
            while (j + 1 < order.length && probabilities[order[j + 1]] == probabilities[order[i]]) {
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
