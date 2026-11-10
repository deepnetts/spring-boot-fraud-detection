package com.example.frauddetection.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.frauddetection.domain.LabeledTransaction;
import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;

class CsvTransactionReaderTest {

    private static final FeatureExtractor FEATURES = new FeatureExtractor();


    private static final String HEADER = "amount,customerAvgAmount,hourOfDay,merchantCategory,distanceFromHomeKm,"
            + "distanceFromLastTransactionKm,minutesSinceLastTransaction,transactionsLast24h,failedAttemptsLast24h,"
            + "accountAgeDays,cardNotPresent,usedChip,usedPin,foreignTransaction,newDevice,newMerchant,fraud";
    private static final String ROW = "42.10,55.0,13,GROCERY,3.2,2.5,720.0,2,0,1500,false,true,true,false,false,false,false";
    private static final String FRAUD_ROW = "2499.99,45.0,3,ELECTRONICS,850,790,12,11,3,400,true,false,false,true,true,true,true";

    @TempDir
    Path dir;

    @Test
    void readsRowsIntoTransactions() throws IOException {
        List<LabeledTransaction> rows = CsvTransactionReader.read(csv(HEADER, ROW, FRAUD_ROW));

        assertEquals(2, rows.size());
        assertFalse(rows.get(0).fraud());
        assertTrue(rows.get(1).fraud());
        Transaction first = rows.get(0).transaction();
        assertEquals(42.10, first.amount());
        assertEquals(13, first.hourOfDay());
        assertEquals(MerchantCategory.GROCERY, first.merchantCategory());
        assertEquals(1500, first.accountAgeDays());
        assertTrue(first.usedChip());
        assertNull(first.transactionId());
    }

    @Test
    void columnsCanBeInAnyOrder() throws IOException {
        String[] header = HEADER.split(",");
        String[] row = ROW.split(",");
        String swappedHeader = "fraud," + String.join(",", List.of(header).subList(0, header.length - 1));
        String swappedRow = "false," + String.join(",", List.of(row).subList(0, row.length - 1));

        assertEquals(CsvTransactionReader.read(csv(HEADER, ROW)), CsvTransactionReader.read(csv(swappedHeader, swappedRow)));
    }

    @Test
    void metadataAndUnknownColumnsNeverAffectTheTransaction() throws IOException {
        List<LabeledTransaction> plain = CsvTransactionReader.read(csv(HEADER, ROW));
        List<LabeledTransaction> extra = CsvTransactionReader.read(csv(
                "timestamp,customerId,notes," + HEADER, "2026-10-30T14:05:00+01:00,cust-1,anything," + ROW));

        assertEquals(plain, extra);
    }

    @Test
    void keepsTheOptionalTransactionId() throws IOException {
        List<LabeledTransaction> rows = CsvTransactionReader.read(csv("transactionId," + HEADER, "tx-7," + ROW, "," + ROW));

        assertEquals("tx-7", rows.get(0).transaction().transactionId());
        assertNull(rows.get(1).transaction().transactionId());
    }

    @Test
    void toleratesAByteOrderMarkAndBlankLines() throws IOException {
        Path file = dir.resolve("excel.csv");
        Files.writeString(file, "﻿" + HEADER + "\r\n" + ROW + "\r\n\r\n");

        assertEquals(1, CsvTransactionReader.read(file).size());
    }

    @Test
    void producesTheSameTransactionsAndFeaturesAsTheSyntheticData() throws IOException {
        List<LabeledTransaction> generated = new SyntheticTransactionGenerator(3).generate(300, 0.1);
        List<String> lines = new ArrayList<>(List.of("transactionId," + HEADER));
        generated.forEach(row -> lines.add(toCsv(row)));

        List<LabeledTransaction> read = CsvTransactionReader.read(csv(lines.toArray(String[]::new)));

        assertEquals(generated, read);
        for (int i = 0; i < generated.size(); i++) {
            assertArrayEquals(FEATURES.encodeToTensor(generated.get(i).transaction()).getValues(),
                    FEATURES.encodeToTensor(read.get(i).transaction()).getValues());
        }
    }

    @Test
    void theSampleFileMatchesTheContract() {
        List<LabeledTransaction> rows = CsvTransactionReader.read(Path.of("data", "sample-transactions.csv"));

        assertEquals(500, rows.size());
        assertEquals(15, rows.stream().filter(LabeledTransaction::fraud).count());
    }

    @Test
    void reportsTheFileLineAndColumnOfABadNumber() throws IOException {
        Path file = csv(HEADER, ROW, ROW.replace("42.10", "abc"));

        String message = failure(file);

        assertThat(message).contains(file.toAbsolutePath().toString()).contains("line 3, column amount").contains("abc");
    }

    @Test
    void rejectsDecimalCommasAndNonFiniteNumbers() throws IOException {
        assertThat(failure(csv(HEADER, ROW.replace("3.2,2.5", "3;2,2.5")))).contains("line 2, column distanceFromHomeKm");
        assertThat(failure(csv(HEADER, ROW.replace("42.10", "NaN")))).contains("line 2, column amount");
        assertThat(failure(csv(HEADER, ROW.replace("42.10", "Infinity")))).contains("line 2, column amount");
    }

    @Test
    void reportsABlankRequiredValue() throws IOException {
        assertThat(failure(csv(HEADER, ROW.replace("720.0", "")))).contains("line 2, column minutesSinceLastTransaction").contains("missing");
    }

    @Test
    void reportsAnUnknownCategoryWithTheAllowedValues() throws IOException {
        assertThat(failure(csv(HEADER, ROW.replace("GROCERY", "CASINO")))).contains("line 2, column merchantCategory").contains("MONEY_TRANSFER");
    }

    @Test
    void acceptsOnlyTrueOrFalseForFlags() throws IOException {
        assertThat(failure(csv(HEADER, ROW.replace("true,true,false", "yes,true,false")))).contains("line 2, column usedChip");
        assertThat(failure(csv(HEADER, ROW.substring(0, ROW.length() - 5) + "1"))).contains("line 2, column fraud");
    }

    @Test
    void appliesTheSameValidationAsTheApi() throws IOException {
        assertThat(failure(csv(HEADER, ROW.replace(",13,", ",30,")))).contains("line 2, column hourOfDay").contains("must be less than or equal to 23");
        assertThat(failure(csv(HEADER, ROW.replace("42.10", "-5")))).contains("line 2, column amount").contains("must be greater than or equal to 0");
    }

    @Test
    void reportsARowWithTheWrongNumberOfColumns() throws IOException {
        assertThat(failure(csv(HEADER, ROW, "1,2,3"))).contains("line 3").contains("expected 17 columns").contains("found 3");
    }

    @Test
    void reportsEveryProblemNotJustTheFirst() throws IOException {
        String message = failure(csv(HEADER, ROW.replace("42.10", "abc"), ROW, ROW.replace("GROCERY", "CASINO")));

        assertThat(message).contains("2 problems").contains("line 2, column amount").contains("line 4, column merchantCategory");
    }

    @Test
    void limitsHowManyProblemsAreListed() throws IOException {
        List<String> lines = new ArrayList<>(List.of(HEADER));
        for (int i = 0; i < 30; i++) {
            lines.add(ROW.replace("42.10", "abc"));
        }

        assertThat(failure(csv(lines.toArray(String[]::new)))).contains("30 problems").contains("line 21,").doesNotContain("line 22,").contains("and 10 more");
    }

    @Test
    void namesMissingColumns() throws IOException {
        assertThat(failure(csv(HEADER.replace("amount,", "").replace(",fraud", ""), "x"))).contains("Missing required columns").contains("amount").contains("fraud");
    }

    @Test
    void rejectsADuplicateColumn() throws IOException {
        assertThat(failure(csv("amount," + HEADER, "1," + ROW))).contains("appears more than once").contains("amount");
    }

    @Test
    void rejectsAnEmptyFile() throws IOException {
        assertThat(failure(csv())).contains("empty");
    }

    @Test
    void rejectsAHeaderWithoutRows() throws IOException {
        assertThat(failure(csv(HEADER))).contains("no data rows");
    }

    @Test
    void reportsAMissingFile() {
        assertThat(failure(dir.resolve("nothing-here.csv"))).contains("not found").contains("nothing-here.csv");
    }

    private Path csv(String... lines) throws IOException {
        Path file = dir.resolve("transactions.csv");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String failure(Path file) {
        return assertThrows(TrainingDataException.class, () -> CsvTransactionReader.read(file)).getMessage();
    }

    private static String toCsv(LabeledTransaction row) {
        Transaction t = row.transaction();
        return String.format(Locale.ROOT, "%s,%s,%s,%d,%s,%s,%s,%s,%d,%d,%d,%b,%b,%b,%b,%b,%b,%b", t.transactionId(), t.amount(),
                t.customerAvgAmount(), t.hourOfDay(), t.merchantCategory(), t.distanceFromHomeKm(),
                t.distanceFromLastTransactionKm(), t.minutesSinceLastTransaction(), t.transactionsLast24h(),
                t.failedAttemptsLast24h(), t.accountAgeDays(), t.cardNotPresent(), t.usedChip(), t.usedPin(),
                t.foreignTransaction(), t.newDevice(), t.newMerchant(), row.fraud());
    }
}
