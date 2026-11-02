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
 * To train on your own labelled history instead, set {@code fraud.data.source} to {@code csv}.
 */
public class SyntheticTransactionGenerator {

    private static final MerchantCategory[] CATEGORIES = MerchantCategory.values();
    // weights follow the declaration order of MerchantCategory
    private static final double[] LEGIT_CATEGORY_WEIGHTS = {25, 15, 12, 18, 5, 4, 8, 8, 2, 3};
    private static final double[] FRAUD_CATEGORY_WEIGHTS = {4, 3, 3, 10, 20, 10, 20, 5, 10, 15};
    private static final double[] CATEGORY_AMOUNT_FACTOR = {1.0, 0.7, 0.9, 1.3, 4.0, 5.0, 0.5, 0.8, 1.5, 3.0};
    private static final double MINUTES_PER_DAY = 1440;

    private enum WeakSignal { AMOUNT, CATEGORY, NEW_DEVICE, NEW_MERCHANT, VELOCITY }

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
        int transactions = poisson(3);
        return new Transaction(
                id(),
                round2(amount),
                round2(customerAvg),
                hour,
                category,
                round2(distanceHome),
                round2(travelling && chance(0.3) ? distanceHome : exponential(5)),
                minutesSinceLast(transactions, 600),
                transactions,
                poisson(0.1),
                accountAge(),
                cardNotPresent,
                chip,
                chip && chance(0.6),
                travelling ? chance(0.5) : chance(0.01),
                cardNotPresent && chance(0.05),
                chance(0.15));
    }

    // TODO(customize): this mix of fraud patterns is an assumption. Adapt it to the fraud you expect to see
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

    Transaction stolenCard() {
        MerchantCategory category = pick(FRAUD_CATEGORY_WEIGHTS);
        double customerAvg = logNormal(Math.log(60), 0.6);
        double amount = customerAvg * CATEGORY_AMOUNT_FACTOR[category.ordinal()] * logNormal(Math.log(4), 0.7);
        boolean cardNotPresent = chance(0.5);
        boolean chip = !cardNotPresent && chance(0.4);
        double distanceHome = chance(0.6) ? logNormal(Math.log(300), 1.0) : exponential(20);
        int transactions = poisson(7);
        return new Transaction(
                id(), round2(amount), round2(customerAvg), nightOrAnyHour(0.45), category,
                round2(distanceHome), round2(chance(0.6) ? logNormal(Math.log(150), 1.0) : exponential(10)),
                minutesSinceLast(transactions, 60), transactions, poisson(0.8), accountAge(),
                cardNotPresent, chip, chip && chance(0.1), chance(0.35),
                cardNotPresent && chance(0.4), chance(0.7));
    }

    Transaction cardTesting() {
        double customerAvg = logNormal(Math.log(60), 0.6);
        MerchantCategory category = chance(0.6) ? MerchantCategory.DIGITAL_GOODS : pick(FRAUD_CATEGORY_WEIGHTS);
        int transactions = poisson(10);
        return new Transaction(
                id(), round2(0.5 + random.nextDouble() * 9.5), round2(customerAvg), nightOrAnyHour(0.4), category,
                round2(exponential(40)), round2(exponential(30)),
                minutesSinceLast(transactions, 4), transactions, poisson(2.5), accountAge(),
                true, false, false, chance(0.4), chance(0.7), chance(0.8));
    }

    Transaction accountTakeover() {
        MerchantCategory category = chance(0.5) ? MerchantCategory.MONEY_TRANSFER : pick(FRAUD_CATEGORY_WEIGHTS);
        double customerAvg = logNormal(Math.log(60), 0.6);
        double amount = customerAvg * CATEGORY_AMOUNT_FACTOR[category.ordinal()] * logNormal(Math.log(3), 0.8);
        int transactions = poisson(5);
        return new Transaction(
                id(), round2(amount), round2(customerAvg), nightOrAnyHour(0.3), category,
                round2(exponential(30)), round2(exponential(25)),
                minutesSinceLast(transactions, 120), transactions, poisson(1.5),
                180 + random.nextInt(3470),
                true, false, false, chance(0.2), chance(0.8), chance(0.75));
    }

    Transaction stealthy() {
        return stealthy(legitimate());
    }

    /** Starts from a normal transaction and changes one or two weak signals that can change on this row. */
    Transaction stealthy(Transaction t) {
        double amount = t.amount();
        MerchantCategory category = t.merchantCategory();
        double minutes = t.minutesSinceLastTransaction();
        int transactions = t.transactionsLast24h();
        boolean newDevice = t.newDevice();
        boolean newMerchant = t.newMerchant();
        List<WeakSignal> signals = new ArrayList<>(List.of(WeakSignal.AMOUNT, WeakSignal.CATEGORY, WeakSignal.VELOCITY));
        if (t.cardNotPresent() && !t.newDevice()) {
            signals.add(WeakSignal.NEW_DEVICE);
        }
        if (!t.newMerchant()) {
            signals.add(WeakSignal.NEW_MERCHANT);
        }
        Collections.shuffle(signals, random);
        for (WeakSignal signal : signals.subList(0, 1 + random.nextInt(2))) {
            switch (signal) {
                case AMOUNT -> {
                    do {
                        amount = round2(t.amount() * logNormal(Math.log(1.8), 0.5));
                    } while (amount == t.amount());
                }
                case CATEGORY -> {
                    do {
                        category = pick(FRAUD_CATEGORY_WEIGHTS);
                    } while (category == t.merchantCategory());
                }
                case NEW_DEVICE -> newDevice = true;
                case NEW_MERCHANT -> newMerchant = true;
                case VELOCITY -> {
                    transactions += 1 + poisson(1);
                    minutes = minutesSinceLast(transactions, 120);
                }
            }
        }
        return new Transaction(
                t.transactionId(), amount, t.customerAvgAmount(), t.hourOfDay(), category,
                t.distanceFromHomeKm(), t.distanceFromLastTransactionKm(), minutes, transactions,
                t.failedAttemptsLast24h(), t.accountAgeDays(), t.cardNotPresent(), t.usedChip(), t.usedPin(),
                t.foreignTransaction(), newDevice, newMerchant);
    }

    /** New accounts are rare, but they are not a sign of fraud on their own. */
    private int accountAge() {
        return 1 + random.nextInt(3650);
    }

    /** Agrees with the 24-hour count, which excludes the current transaction. */
    private double minutesSinceLast(int transactionsLast24h, double meanMinutes) {
        if (transactionsLast24h == 0) {
            return round2(MINUTES_PER_DAY + exponential(meanMinutes));
        }
        double minutes;
        do {
            minutes = exponential(meanMinutes);
        } while (minutes >= MINUTES_PER_DAY);
        return Math.min(round2(minutes), MINUTES_PER_DAY - 0.01);
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
