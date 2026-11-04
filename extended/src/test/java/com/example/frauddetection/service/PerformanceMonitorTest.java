package com.example.frauddetection.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerformanceMonitorTest {

    private final PerformanceMonitor monitor = new PerformanceMonitor();

    @Test
    void comparesDecisionsWithConfirmedOutcomes() {
        monitor.recordAssessment("caught", true);
        monitor.recordAssessment("false-alarm", true);
        monitor.recordAssessment("missed", false);
        monitor.recordAssessment("fine", false);

        assertTrue(monitor.recordOutcome("caught", true));
        assertTrue(monitor.recordOutcome("false-alarm", false));
        assertTrue(monitor.recordOutcome("missed", true));
        assertTrue(monitor.recordOutcome("fine", false));

        PerformanceMonitor.Snapshot snapshot = monitor.snapshot();
        assertEquals(4, snapshot.assessments());
        assertEquals(2, snapshot.alerts());
        assertEquals(0.5, snapshot.alertRate());
        assertEquals(4, snapshot.labelledOutcomes());
        assertEquals(1, snapshot.truePositives());
        assertEquals(1, snapshot.falsePositives());
        assertEquals(1, snapshot.falseNegatives());
        assertEquals(1, snapshot.trueNegatives());
        assertEquals(0.5, snapshot.precision());
        assertEquals(0.5, snapshot.recall());
    }

    @Test
    void acceptsEachOutcomeOnlyOnceAndOnlyForScoredTransactions() {
        monitor.recordAssessment("tx-1", true);
        monitor.recordAssessment(null, true);

        assertTrue(monitor.recordOutcome("tx-1", true));
        assertFalse(monitor.recordOutcome("tx-1", true));
        assertFalse(monitor.recordOutcome("never-scored", true));
        assertEquals(1, monitor.snapshot().labelledOutcomes());
        // a transaction without an id is still counted as an assessment
        assertEquals(2, monitor.snapshot().assessments());
    }

    @Test
    void leavesRatesEmptyUntilThereIsSomethingToMeasure() {
        PerformanceMonitor.Snapshot empty = monitor.snapshot();
        assertNull(empty.alertRate());
        assertNull(empty.precision());
        assertNull(empty.recall());

        monitor.recordAssessment("tx-1", false);
        monitor.recordOutcome("tx-1", false);
        // no flagged transaction and no fraud case has an outcome yet
        assertNull(monitor.snapshot().precision());
        assertNull(monitor.snapshot().recall());
    }

    @Test
    void forgetsTheOldestDecisionsWaitingForAnOutcome() {
        for (int i = 0; i <= PerformanceMonitor.MAX_PENDING; i++) {
            monitor.recordAssessment("tx-" + i, false);
        }

        assertFalse(monitor.recordOutcome("tx-0", false));
        assertTrue(monitor.recordOutcome("tx-" + PerformanceMonitor.MAX_PENDING, false));
    }

    @Test
    void startsAgainAfterAReset() {
        monitor.recordAssessment("tx-1", true);
        monitor.reset();

        assertEquals(0, monitor.snapshot().assessments());
        assertFalse(monitor.recordOutcome("tx-1", true));
    }
}
