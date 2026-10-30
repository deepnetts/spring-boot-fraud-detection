package com.example.frauddetection.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.Transaction;

/** The generator rules from docs/DATA_CONTRACT.md. */
class SyntheticTransactionGeneratorTest {

    private static final int ROWS = 20_000;

    private final SyntheticTransactionGenerator generator = new SyntheticTransactionGenerator(123);
    private final List<Transaction> legitimate = rows(generator::legitimate);
    private final List<Transaction> stolenCard = rows(generator::stolenCard);
    private final List<Transaction> cardTesting = rows(generator::cardTesting);
    private final List<Transaction> accountTakeover = rows(generator::accountTakeover);
    private final List<Transaction> stealthy = rows(generator::stealthy);
    private final List<Transaction> everything = Stream.of(legitimate, stolenCard, cardTesting, accountTakeover, stealthy)
            .flatMap(List::stream).toList();

    @Test
    void chipPinAndDeviceSignalsAreConsistentWithTheChannel() {
        assertEquals(0, count(everything, t -> t.usedChip() && t.cardNotPresent()), "chip read on a card-not-present payment");
        assertEquals(0, count(everything, t -> t.usedPin() && !t.usedChip()), "PIN without a chip read");
        assertEquals(0, count(everything, t -> t.newDevice() && !t.cardNotPresent()), "new device on a card-present payment");
    }

    @Test
    void timeSinceLastTransactionAgreesWithTheDailyCount() {
        assertEquals(0, count(everything, t -> (t.minutesSinceLastTransaction() < 1440) != (t.transactionsLast24h() >= 1)));
    }

    @Test
    void legitimateAccountsCanBeNew() {
        assertTrue(count(legitimate, t -> t.accountAgeDays() < 30) > 0);
    }

    @Test
    void accountTakeoverUsesEstablishedAccounts() {
        assertEquals(0, count(accountTakeover, t -> t.accountAgeDays() < 180));
    }

    @Test
    void stealthyFraudChangesExactlyOneOrTwoWeakSignalsOfTheOriginal() {
        for (Transaction original : legitimate) {
            Transaction fraud = generator.stealthy(original);

            int changed = changed(original.amount(), fraud.amount())
                    + changed(original.merchantCategory(), fraud.merchantCategory())
                    + changed(original.newDevice(), fraud.newDevice())
                    + changed(original.newMerchant(), fraud.newMerchant())
                    + changed(original.transactionsLast24h(), fraud.transactionsLast24h());

            assertTrue(changed == 1 || changed == 2, changed + " signals changed in " + fraud);
            assertEquals(sameFields(original), sameFields(fraud));
        }
    }

    @Test
    void stealthyFraudKeepsTheNormalHourDistribution() {
        double legitimateNightShare = (double) count(legitimate, t -> t.hourOfDay() < 6) / ROWS;
        double stealthyNightShare = (double) count(stealthy, t -> t.hourOfDay() < 6) / ROWS;

        assertEquals(legitimateNightShare, stealthyNightShare, 0.01);
    }

    @Test
    void cardTestingIsNotPerfectlyClean() {
        assertTrue(count(cardTesting, t -> t.transactionsLast24h() < 8) > 0, "always high velocity");
        assertTrue(count(cardTesting, t -> t.failedAttemptsLast24h() < 2) > 0, "always failed attempts");
        assertTrue(count(cardTesting, t -> t.amount() >= 5) > 0, "always a tiny amount");
    }

    @Test
    void generatedDataStaysImbalancedAtTheRequestedFraudRate() {
        List<LabeledTransaction> data = new SyntheticTransactionGenerator(5).generate(10_000, 0.03);

        assertEquals(10_000, data.size());
        assertEquals(300, data.stream().filter(LabeledTransaction::fraud).count());
    }

    @Test
    void sameSeedGivesTheSameData() {
        assertEquals(new SyntheticTransactionGenerator(5).generate(500, 0.03), new SyntheticTransactionGenerator(5).generate(500, 0.03));
    }

    private static int changed(Object before, Object after) {
        return before.equals(after) ? 0 : 1;
    }

    /** Everything stealthy fraud must leave as it was. */
    private static List<Object> sameFields(Transaction t) {
        return List.of(t.transactionId(), t.customerAvgAmount(), t.hourOfDay(), t.distanceFromHomeKm(),
                t.distanceFromLastTransactionKm(), t.failedAttemptsLast24h(), t.accountAgeDays(), t.cardNotPresent(),
                t.usedChip(), t.usedPin(), t.foreignTransaction());
    }

    private static List<Transaction> rows(Supplier<Transaction> supplier) {
        return Stream.generate(supplier).limit(ROWS).toList();
    }

    private static long count(List<Transaction> transactions, Predicate<Transaction> condition) {
        return transactions.stream().filter(condition).count();
    }
}
