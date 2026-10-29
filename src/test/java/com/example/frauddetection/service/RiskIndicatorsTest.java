package com.example.frauddetection.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.frauddetection.domain.MerchantCategory;
import com.example.frauddetection.domain.Transaction;

class RiskIndicatorsTest {

    private Locale originalLocale;

    // a locale with a decimal comma, so the test fails if indicator text follows the system locale
    @BeforeEach
    void useDecimalCommaLocale() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("sr-Latn-RS"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void reportsRedFlagsOfSuspiciousTransactionIndependentOfLocale() {
        var tx = new Transaction("tx-1001", 2499.99, 45.0, 3, MerchantCategory.ELECTRONICS,
                850.0, 790.0, 12.0, 11, 3, 400, true, false, false, true, true, true);

        assertEquals(List.of(
                "Amount is 55.6x the customer's average",
                "High velocity: 11 transactions in 24h",
                "3 failed attempts in 24h",
                "Night-time transaction",
                "850 km from home",
                "Large distance from previous transaction in a short time",
                "Foreign transaction",
                "Card-not-present from a new device",
                "High-risk merchant category: ELECTRONICS"), RiskIndicators.of(tx));
    }

    @Test
    void reportsCardTestingAndNewAccount() {
        var tx = new Transaction("t2", 2.0, 60.0, 14, MerchantCategory.DIGITAL_GOODS,
                5.0, 1.0, 3.0, 9, 0, 12, true, false, false, false, false, true);

        List<String> flags = RiskIndicators.of(tx);

        assertTrue(flags.contains("Small amount with high velocity (possible card testing)"), flags::toString);
        assertTrue(flags.contains("New account (12 days)"), flags::toString);
    }

    @Test
    void reportsNothingForOrdinaryTransaction() {
        var tx = new Transaction("ordinary-1", 42.10, 55.0, 13, MerchantCategory.GROCERY,
                3.2, 2.5, 720.0, 2, 0, 1500, false, true, true, false, false, false);

        assertEquals(List.of(), RiskIndicators.of(tx));
    }
}
