package com.example.frauddetection.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.DatasetSummary;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.data.TrainingDataException;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.model.FraudModel;
import com.example.frauddetection.model.FraudModelTrainer;
import com.example.frauddetection.model.ModelLoadException;
import com.example.frauddetection.model.ModelLoadException.Reason;
import com.example.frauddetection.model.ModelRepository;
import com.example.frauddetection.service.FraudDetectionService.RetrainingResult;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class FraudDetectionServiceTest {

    private static final String MODEL_FILE = "fraud-model.bin";
    private static final Training TRAINING = new Training(2000, 0.05, 0.25, 0.15, List.of(8), 2, 0.001f, 0.0001f, 1);
    private static final Transaction TRANSACTION = new SyntheticTransactionGenerator(7).legitimate();

    private static FraudModel existingModel;

    @TempDir
    Path dir;

    private final List<FraudDetectionService> services = new ArrayList<>();
    private int trainings;

    @BeforeAll
    static void trainExistingModel() {
        existingModel = new FraudModelTrainer(properties(Path.of("unused"), false)).train(TRAINING);
    }

    @AfterAll
    static void closeExistingModel() throws Exception {
        existingModel.close();
    }

    @AfterEach
    void shutDownServices() {
        services.forEach(FraudDetectionService::shutdown);
    }

    @Test
    void trainsOnFirstRunWhenTheModelIsMissingAndTrainingIsEnabled() {
        FraudDetectionService service = service(true);

        service.run(null);

        assertEquals(1, trainings);
        assertTrue(Files.exists(file()));
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void staysUnavailableWhenTheModelIsMissingAndTrainingIsDisabled() {
        FraudDetectionService service = service(false);

        service.run(null);

        assertEquals(0, trainings);
        assertFalse(Files.exists(file()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::modelInfo).getStatusCode());
    }

    @Test
    void loadsAValidModelWithoutTraining() {
        saveExistingModel();
        FraudDetectionService service = service(true);

        service.run(null);

        assertEquals(0, trainings);
        assertEquals(existingModel.metadata().trainedAt(), service.modelInfo().trainedAt());
    }

    @Test
    void incompatibleModelStopsStartupWithoutTrainingOrTouchingTheFile() throws Exception {
        saveExistingModel();
        Files.write(file(), patch(Files.readAllBytes(file()), "failed_attempts_24h", "failed_attempts_25h"));
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = service(true);

        var failure = assertThrows(ModelLoadException.class, () -> service.run(null));

        assertEquals(Reason.INCOMPATIBLE, failure.reason());
        assertEquals(0, trainings);
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(List.of(MODEL_FILE), fileNames());
    }

    @Test
    void unreadableModelStopsStartupWithoutTrainingOrTouchingTheFile() throws Exception {
        saveExistingModel();
        byte[] whole = Files.readAllBytes(file());
        Files.write(file(), Arrays.copyOf(whole, whole.length / 2));
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = service(true);

        var failure = assertThrows(ModelLoadException.class, () -> service.run(null));

        assertEquals(Reason.UNREADABLE, failure.reason());
        assertEquals(0, trainings);
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(List.of(MODEL_FILE), fileNames());
    }

    @Test
    void explicitTrainingCreatesAModelWhenNoneExists() {
        FraudDetectionService service = service(false);
        service.run(null);

        service.train(TRAINING, false);

        assertEquals(1, trainings);
        assertTrue(Files.exists(file()));
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void trainingWithoutOverwriteIsRefusedBeforeItStartsWhenAModelExists() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = service(false);
        service.run(null);

        var failure = assertThrows(ResponseStatusException.class, () -> service.train(TRAINING, false));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals(0, trainings);
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void trainingWithOverwriteReplacesTheModelAndScoringStillWorks() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = service(false);
        service.run(null);

        service.train(TRAINING, true);

        assertEquals(1, trainings);
        assertNotEquals(existingModel.metadata().trainedAt(), service.modelInfo().trainedAt());
        assertFalse(Arrays.equals(before, Files.readAllBytes(file())));
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void failedTrainingKeepsTheExistingModel() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = new FraudDetectionService(properties(dir, false), new FraudModelTrainer(properties(dir, false)) {
            @Override
            public FraudModel train(Training cfg) {
                throw new IllegalStateException("training failed");
            }
        }, new ModelRepository(properties(dir, false)));
        services.add(service);
        service.run(null);
        Instant loadedAt = service.modelInfo().trainedAt();

        assertThrows(IllegalStateException.class, () -> service.train(TRAINING, true));

        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(loadedAt, service.modelInfo().trainedAt());
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void failedSaveKeepsTheExistingModel() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = new FraudDetectionService(properties(dir, false), countingTrainer(),
                new ModelRepository(properties(dir, false)) {
                    @Override
                    public void save(FraudModel model, boolean overwrite) {
                        throw new UncheckedIOException(new IOException("disk full"));
                    }
                });
        services.add(service);
        service.run(null);
        Instant loadedAt = service.modelInfo().trainedAt();

        assertThrows(UncheckedIOException.class, () -> service.train(TRAINING, true));

        assertEquals(1, trainings);
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(loadedAt, service.modelInfo().trainedAt());
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void summarizesTheConfiguredSyntheticDataWithoutTraining() {
        FraudDetectionService service = service(false);

        DatasetSummary summary = service.dataSummary();

        assertEquals("synthetic", summary.source());
        assertEquals(2000, summary.rows());
        assertEquals(100, summary.fraudRows());
        assertEquals(0, trainings);
    }

    @Test
    void summarizesTheConfiguredCsvFile() {
        var properties = new FraudProperties(new FraudProperties.Model(dir, false, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.CSV, Path.of("data", "sample-transactions.csv")), new FraudProperties.Retraining("-", 0.01));
        var service = new FraudDetectionService(properties, new FraudModelTrainer(properties), new ModelRepository(properties));
        services.add(service);

        DatasetSummary summary = service.dataSummary();

        assertEquals("csv", summary.source());
        assertEquals(500, summary.rows());
        assertEquals(15, summary.fraudRows());
    }

    @Test
    void countsAssessmentsByDecision() {
        saveExistingModel();
        FraudDetectionService service = service(false);
        service.run(null);
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            FraudAssessment assessment = service.assess(TRANSACTION);
            service.assess(TRANSACTION);

            assertEquals(2, registry.get("fraud.assessments").tag("fraud", String.valueOf(assessment.fraud())).counter().count());
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    void reportsHealthFromTheLoadedModel() {
        saveExistingModel();
        FraudDetectionService service = service(false);
        var health = new ModelHealthIndicator(service);

        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());

        service.run(null);
        assertEquals(Status.UP, health.health().getStatus());

        service.shutdown();
        assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
    }

    @Test
    void unusableCsvDataKeepsTheExistingModel() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        FraudDetectionService service = csvService(false);
        service.run(null);
        Instant loadedAt = service.modelInfo().trainedAt();

        assertThrows(TrainingDataException.class, () -> service.train(TRAINING, true));

        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(loadedAt, service.modelInfo().trainedAt());
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void unusableCsvDataOnFirstRunSavesNoModel() throws Exception {
        FraudDetectionService service = csvService(true);

        assertThrows(TrainingDataException.class, () -> service.run(null));

        assertFalse(Files.exists(file()));
    }

    @Test
    @Timeout(60)
    void scoringInProgressFinishesBeforeARetrainClosesTheOldModel() throws Exception {
        saveExistingModel();
        FraudModel served = new ModelRepository(properties(dir, false)).load().orElseThrow();
        var saved = new CountDownLatch(1);
        FraudDetectionService service = serving(served, saved);
        List<FutureTask<FraudAssessment>> scoring = new ArrayList<>();
        var retrain = new FutureTask<>(() -> service.train(TRAINING, true));

        synchronized (served.network()) {
            startBlockedScoring(service, scoring);
            Thread retraining = start(retrain);
            saved.await();
            awaitParked(retraining);
            // the swap has to wait for the scoring above, which is still in progress
            assertEquals(served.metadata().trainedAt(), service.modelInfo().trainedAt());
        }

        for (var result : scoring) {
            assertEquals(served.metadata().trainedAt(), result.get().modelTrainedAt());
        }
        Instant retrainedAt = retrain.get().trainedAt();
        assertNotEquals(served.metadata().trainedAt(), retrainedAt);
        assertEquals(retrainedAt, service.assess(TRANSACTION).modelTrainedAt());
    }

    @Test
    @Timeout(60)
    void scoringInProgressFinishesBeforeShutdownClosesTheModel() throws Exception {
        saveExistingModel();
        FraudModel served = new ModelRepository(properties(dir, false)).load().orElseThrow();
        FraudDetectionService service = serving(served, new CountDownLatch(1));
        List<FutureTask<FraudAssessment>> scoring = new ArrayList<>();
        var stop = new FutureTask<>(() -> {
            service.shutdown();
            return null;
        });

        synchronized (served.network()) {
            startBlockedScoring(service, scoring);
            awaitParked(start(stop));
            // shutdown has to wait for the scoring above, which is still in progress
            assertEquals(served.metadata().trainedAt(), service.modelInfo().trainedAt());
        }

        for (var result : scoring) {
            assertEquals(served.metadata().trainedAt(), result.get().modelTrainedAt());
        }
        stop.get();
        assertThrows(RejectedExecutionException.class, () -> served.predict(TRANSACTION));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::modelInfo).getStatusCode());
    }

    @Test
    @Timeout(120)
    void scoringKeepsWorkingAcrossRepeatedRetrains() throws Exception {
        saveExistingModel();
        FraudDetectionService service = service(false);
        service.run(null);
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        List<Thread> scorers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            scorers.add(start(() -> {
                while (!stop.get()) {
                    try {
                        service.assess(TRANSACTION);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }
            }));
        }

        for (int i = 0; i < 5; i++) {
            service.train(TRAINING, true);
        }
        stop.set(true);
        for (Thread scorer : scorers) {
            scorer.join();
        }

        assertNull(failure.get());
    }

    @Test
    @Timeout(60)
    void shutdownDuringATrainingRunClosesTheNewModelInsteadOfActivatingIt() throws Exception {
        saveExistingModel();
        var built = new AtomicReference<FraudModel>();
        var trainingDone = new CountDownLatch(1);
        var mayFinish = new CountDownLatch(1);
        var trainer = new FraudModelTrainer(properties(dir, false)) {
            @Override
            public FraudModel train(Training cfg) {
                built.set(super.train(cfg));
                trainingDone.countDown();
                try {
                    mayFinish.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return built.get();
            }
        };
        var service = new FraudDetectionService(properties(dir, false), trainer, new ModelRepository(properties(dir, false)));
        services.add(service);
        service.run(null);
        var retrain = new FutureTask<>(() -> service.train(TRAINING, true));
        start(retrain);

        trainingDone.await();
        service.shutdown();
        mayFinish.countDown();
        retrain.get();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::modelInfo).getStatusCode());
        assertThrows(RejectedExecutionException.class, () -> built.get().predict(TRANSACTION));
    }

    @Test
    void aModelLoadedAfterShutdownIsNotActivated() {
        saveExistingModel();
        FraudDetectionService service = service(false);

        service.shutdown();
        service.run(null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class, service::modelInfo).getStatusCode());
    }

    /** A service whose model is {@code served}, so a test can block scoring by holding the network's monitor. */
    private FraudDetectionService serving(FraudModel served, CountDownLatch saved) {
        FraudProperties properties = properties(dir, false);
        var service = new FraudDetectionService(properties, countingTrainer(), new ModelRepository(properties) {
            @Override
            public Optional<FraudModel> load() {
                return Optional.of(served);
            }

            @Override
            public void save(FraudModel model, boolean overwrite) {
                super.save(model, overwrite);
                saved.countDown();
            }
        });
        services.add(service);
        service.run(null);
        return service;
    }

    /** Must be called while holding the served network's monitor, so the scoring threads stop inside predict. */
    @Test
    void recordsOutcomesOfScoredTransactions() {
        saveExistingModel();
        FraudDetectionService service = service(false);
        service.run(null);
        FraudAssessment assessment = service.assess(withId(TRANSACTION, "tx-1"));

        assertTrue(service.recordOutcome("tx-1", true));
        assertFalse(service.recordOutcome("tx-1", true));
        assertFalse(service.recordOutcome("never-scored", false));

        PerformanceMonitor.Snapshot performance = service.monitoring().performance();
        assertEquals(1, performance.assessments());
        assertEquals(1, performance.labelledOutcomes());
        assertEquals(assessment.fraud() ? 1 : 0, performance.truePositives());
        assertEquals(assessment.fraud() ? 0 : 1, performance.falseNegatives());
    }

    @Test
    void scheduledRetrainingActivatesANewModelThatIsNotWorse() {
        saveExistingModel();
        // a tolerance this wide lets any new model pass
        FraudDetectionService service = service(new FraudProperties.Retraining("-", 0.99));
        service.run(null);
        service.assess(withId(TRANSACTION, "tx-1"));

        RetrainingResult result = service.retrainIfBetter();

        assertTrue(result.activated(), result.message());
        assertNotEquals(existingModel.metadata().trainedAt(), service.modelInfo().trainedAt());
        assertEquals(service.modelInfo().trainedAt(),
                new ModelRepository(properties(dir, false)).load().orElseThrow().metadata().trainedAt());
        // monitoring describes the active model, so it starts again
        assertEquals(0, service.monitoring().performance().assessments());
        assertEquals(result, service.monitoring().lastRetraining());
    }

    @Test
    void scheduledRetrainingKeepsTheActiveModelWhenTheNewOneIsWorse() throws Exception {
        saveExistingModel();
        byte[] before = Files.readAllBytes(file());
        // a negative tolerance demands an improvement no model can reach, so the new model always loses
        FraudDetectionService service = service(new FraudProperties.Retraining("-", -1));
        service.run(null);

        RetrainingResult result = service.retrainIfBetter();

        assertFalse(result.activated());
        assertTrue(result.message().startsWith("Kept the active model"), result.message());
        assertEquals(existingModel.metadata().trainedAt(), service.modelInfo().trainedAt());
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertDoesNotThrow(() -> service.assess(TRANSACTION));
    }

    @Test
    void scheduledRetrainingWithoutAModelIsSkipped() {
        FraudDetectionService service = service(false);
        service.run(null);

        RetrainingResult result = service.retrainIfBetter();

        assertFalse(result.activated());
        assertTrue(result.message().startsWith("Skipped"), result.message());
        assertFalse(Files.exists(file()));
    }

    private static Transaction withId(Transaction t, String transactionId) {
        return new Transaction(transactionId, t.amount(), t.customerAvgAmount(), t.hourOfDay(), t.merchantCategory(),
                t.distanceFromHomeKm(), t.distanceFromLastTransactionKm(), t.minutesSinceLastTransaction(),
                t.transactionsLast24h(), t.failedAttemptsLast24h(), t.accountAgeDays(), t.cardNotPresent(), t.usedChip(),
                t.usedPin(), t.foreignTransaction(), t.newDevice(), t.newMerchant());
    }

    private FraudDetectionService service(FraudProperties.Retraining retraining) {
        FraudProperties properties = new FraudProperties(new FraudProperties.Model(dir, false, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.SYNTHETIC, null), retraining);
        var service = new FraudDetectionService(properties, new FraudModelTrainer(properties), new ModelRepository(properties));
        services.add(service);
        return service;
    }

    private static void startBlockedScoring(FraudDetectionService service, List<FutureTask<FraudAssessment>> scoring)
            throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            var result = new FutureTask<>((Callable<FraudAssessment>) () -> service.assess(TRANSACTION));
            scoring.add(result);
            awaitParked(start(result));
        }
    }

    private static Thread start(Runnable task) {
        var thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Waits until the thread is stopped on a monitor or a lock. */
    private static void awaitParked(Thread thread) throws InterruptedException {
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.WAITING) {
            Thread.sleep(5);
        }
    }

    private FraudDetectionService service(boolean trainIfMissing) {
        FraudProperties properties = properties(dir, trainIfMissing);
        var service = new FraudDetectionService(properties, countingTrainer(), new ModelRepository(properties));
        services.add(service);
        return service;
    }

    private FraudDetectionService csvService(boolean trainIfMissing) throws IOException {
        Path csv = dir.resolve("transactions.csv");
        Files.writeString(csv, "this,is,not,the,expected,format\n");
        var properties = new FraudProperties(new FraudProperties.Model(dir, trainIfMissing, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.CSV, csv), new FraudProperties.Retraining("-", 0.01));
        var service = new FraudDetectionService(properties, new FraudModelTrainer(properties), new ModelRepository(properties));
        services.add(service);
        return service;
    }

    private FraudModelTrainer countingTrainer() {
        return new FraudModelTrainer(properties(dir, false)) {
            @Override
            public FraudModel train(Training cfg) {
                trainings++;
                return super.train(cfg);
            }
        };
    }

    private void saveExistingModel() {
        new ModelRepository(properties(dir, false)).save(existingModel, false);
    }

    private Path file() {
        return dir.resolve(MODEL_FILE);
    }

    private List<String> fileNames() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static FraudProperties properties(Path dir, boolean trainIfMissing) {
        return new FraudProperties(new FraudProperties.Model(dir, trainIfMissing, 0.5), TRAINING,
                new FraudProperties.Data(FraudProperties.Data.Source.SYNTHETIC, null), new FraudProperties.Retraining("-", 0.01));
    }

    private static byte[] patch(byte[] bytes, String from, String to) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains(from));
        return text.replace(from, to).getBytes(StandardCharsets.ISO_8859_1);
    }
}
