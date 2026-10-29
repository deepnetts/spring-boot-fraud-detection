package com.example.frauddetection.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

/**
 * Generates labelled card transactions with realistic, overlapping distributions for legitimate
 * and fraudulent behaviour. Fraud is a mix of typical patterns:
 * <ul>
 *     <li><b>stolen card</b> – large purchases of resellable goods, far from home, often at night</li>
 *     <li><b>card testing</b> – bursts of tiny card-not-present charges with failed attempts</li>
 *     <li><b>account takeover</b> – new device, new merchants, money transfers / digital goods</li>
 *     <li><b>stealthy</b> – mostly normal-looking, with only one or two weak signals</li>
 * </ul>
 * Replace this with a loader for your own labelled history to train on real data.
 */
public class SyntheticTransactionGenerator {

    private static final MerchantCategory[] CATEGORIES = MerchantCategory.values();
    // weights follow the declaration order of MerchantCategory
    private static final double[] LEGIT_CATEGORY_WEIGHTS = {25, 15, 12, 18, 5, 4, 8, 8, 2, 3};
    private static final double[] FRAUD_CATEGORY_WEIGHTS = {4, 3, 3, 10, 20, 10, 20, 5, 10, 15};
    private static final double[] CATEGORY_AMOUNT_FACTOR = {1.0, 0.7, 0.9, 1.3, 4.0, 5.0, 0.5, 0.8, 1.5, 3.0};

    private final Random random;

    public SyntheticTransactionGenerator(long seed) {
        this.random = new Random(seed);
    }

    public List<LabeledTransaction> generate(int count, double fraudRate) {
        int frauds = (int) Math.round(count * fraudRate);
        List<LabeledTransaction> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean fraud = i < frauds;
            result.add(new LabeledTransaction(fraud ? fraudulent() : legitimate(), fraud));
        }
        Collections.shuffle(result, random);
        return result;
    }

    public Transaction legitimate() {
        MerchantCategory category = pick(LEGIT_CATEGORY_WEIGHTS);
        double customerAvg = logNormal(Math.log(60), 0.6);
        double amount = customerAvg * CATEGORY_AMOUNT_FACTOR[category.ordinal()] * logNormal(0, 0.5);
        int hour = Math.floorMod((int) Math.round(14 + random.nextGaussian() * 4), 24);
        boolean travelling = chance(0.04);
        double distanceHome = travelling ? 200 + random.nextDouble() * 3000 : exponential(8);
        boolean cardNotPresent = chance(category == MerchantCategory.DIGITAL_GOODS ? 0.9 : 0.2);
        boolean chip = !cardNotPresent && chance(0.92);
        return new Transaction(
                id(),
                round2(amount),
                round2(customerAvg),
                hour,
                category,
                round2(distanceHome),
                round2(travelling && chance(0.3) ? distanceHome : exponential(5)),
                round2(exponential(600)),
                poisson(3),
                poisson(0.1),
                30 + random.nextInt(3600),
                cardNotPresent,
                chip,
                chip && chance(0.6),
                travelling ? chance(0.5) : chance(0.01),
                cardNotPresent && chance(0.05),
                chance(0.15));
    }

    public Transaction fraudulent() {
        double pattern = random.nextDouble();
        if (pattern < 0.35) {
            return stolenCard();
        } else if (pattern < 0.55) {
            return cardTesting();
        } else if (pattern < 0.80) {
            return accountTakeover();
        }
        return stealthy();
    }

    private Transaction stolenCard() {
        MerchantCategory category = pick(FRAUD_CATEGORY_WEIGHTS);
        double customerAvg = logNormal(Math.log(60), 0.6);
        double amount = customerAvg * CATEGORY_AMOUNT_FACTOR[category.ordinal()] * logNormal(Math.log(4), 0.7);
        boolean cardNotPresent = chance(0.5);
        boolean chip = !cardNotPresent && chance(0.4);
        double distanceHome = chance(0.6) ? logNormal(Math.log(300), 1.0) : exponential(20);
        return new Transaction(
                id(), round2(amount), round2(customerAvg), nightOrAnyHour(0.45), category,
                round2(distanceHome), round2(chance(0.6) ? logNormal(Math.log(150), 1.0) : exponential(10)),
                round2(exponential(60)), poisson(7), poisson(0.8), 30 + random.nextInt(3600),
                cardNotPresent, chip, chip && chance(0.1), chance(0.35),
                cardNotPresent && chance(0.4), chance(0.7));
    }

    private Transaction cardTesting() {
        double customerAvg = logNormal(Math.log(60), 0.6);
        MerchantCategory category = chance(0.6) ? MerchantCategory.DIGITAL_GOODS : pick(FRAUD_CATEGORY_WEIGHTS);
        return new Transaction(
                id(), round2(0.5 + random.nextDouble() * 4.5), round2(customerAvg), nightOrAnyHour(0.4), category,
                round2(exponential(40)), round2(exponential(30)),
                round2(exponential(4)), 8 + poisson(10), 2 + poisson(3), 30 + random.nextInt(3600),
                true, false, false, chance(0.4), chance(0.7), chance(0.8));
    }

    private Transaction accountTakeover() {
        MerchantCategory category = chance(0.5) ? MerchantCategory.MONEY_TRANSFER : pick(FRAUD_CATEGORY_WEIGHTS);
        double customerAvg = logNormal(Math.log(60), 0.6);
        double amount = customerAvg * CATEGORY_AMOUNT_FACTOR[category.ordinal()] * logNormal(Math.log(3), 0.8);
        return new Transaction(
                id(), round2(amount), round2(customerAvg), nightOrAnyHour(0.3), category,
                round2(exponential(30)), round2(exponential(25)),
                round2(exponential(120)), poisson(5), poisson(1.5),
                chance(0.4) ? 1 + random.nextInt(60) : 30 + random.nextInt(3600),
                true, false, false, chance(0.2), chance(0.8), chance(0.75));
    }

    private Transaction stealthy() {
        // starts from a normal-looking transaction and flips a couple of weak signals
        Transaction t = legitimate();
        double amount = t.amount() * logNormal(Math.log(1.8), 0.5);
        return new Transaction(
                t.transactionId(), round2(amount), t.customerAvgAmount(), nightOrAnyHour(0.2),
                chance(0.5) ? pick(FRAUD_CATEGORY_WEIGHTS) : t.merchantCategory(),
                round2(t.distanceFromHomeKm() * (chance(0.3) ? 5 : 1)), t.distanceFromLastTransactionKm(),
                round2(t.minutesSinceLastTransaction() / 3), t.transactionsLast24h() + poisson(1),
                t.failedAttemptsLast24h() + poisson(0.3), t.accountAgeDays(),
                t.cardNotPresent() || chance(0.4), t.usedChip(), t.usedPin() && chance(0.5),
                t.foreignTransaction() || chance(0.1), t.newDevice() || chance(0.25), t.newMerchant() || chance(0.4));
    }

    private int nightOrAnyHour(double nightProbability) {
        return chance(nightProbability) ? random.nextInt(6) : random.nextInt(24);
    }

    private MerchantCategory pick(double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r <= 0) {
                return CATEGORIES[i];
            }
        }
        return CATEGORIES[CATEGORIES.length - 1];
    }

    private boolean chance(double p) {
        return random.nextDouble() < p;
    }

    private double logNormal(double mu, double sigma) {
        return Math.exp(mu + sigma * random.nextGaussian());
    }

    private double exponential(double mean) {
        return -mean * Math.log(1 - random.nextDouble());
    }

    private int poisson(double lambda) {
        double limit = Math.exp(-lambda);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    private String id() {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
