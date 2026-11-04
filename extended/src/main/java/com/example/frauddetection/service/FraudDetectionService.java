package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.DatasetSummary;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.model.FraudModel;
import com.example.frauddetection.model.FraudModelTrainer;
import com.example.frauddetection.model.FraudModelTrainer.TrainingRun;
import com.example.frauddetection.model.ModelEvaluator;
import com.example.frauddetection.model.ModelRepository;

import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;

@Service
public class FraudDetectionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final FraudProperties properties;
    private final FraudModelTrainer trainer;
    private final ModelRepository repository;
    private final AtomicReference<FraudModel> model = new AtomicReference<>();
    private final ReentrantLock trainingLock = new ReentrantLock();
    // scoring holds the read lock, so a model can't be closed while a request is using it;
    // fair, so a waiting swap can't be starved by constant scoring traffic
    private final ReadWriteLock modelLock = new ReentrantReadWriteLock(true);
    private boolean stopped; // guarded by modelLock
    private final PerformanceMonitor monitor = new PerformanceMonitor();
    private int retrainings; // guarded by trainingLock
    private volatile RetrainingResult lastRetraining;

    public FraudDetectionService(FraudProperties properties, FraudModelTrainer trainer, ModelRepository repository) {
        this.properties = properties;
        this.trainer = trainer;
        this.repository = repository;
    }

    /** Loads the persisted model on startup, training a new one if none exists. */
    @Override
    public void run(ApplicationArguments args) {
        Optional<FraudModel> loaded = repository.load();
        if (loaded.isPresent()) {
            log.info("Loaded fraud model trained at {} from {}", loaded.get().metadata().trainedAt(),
                    repository.dir().toAbsolutePath());
            activate(loaded.get());
        } else if (properties.model().trainIfMissing()) {
            log.info("No fraud model found in {}, training a new one", repository.dir().toAbsolutePath());
            train(properties.training(), false);
        } else {
            log.warn("No fraud model found in {}; scoring is unavailable until one is trained",
                    repository.dir().toAbsolutePath());
        }
    }

    public FraudAssessment assess(Transaction transaction) {
        double threshold = properties.model().threshold();
        FraudModel current;
        double score;
        modelLock.readLock().lock();
        try {
            current = currentModel();
            score = current.predict(transaction);
        } finally {
            modelLock.readLock().unlock();
        }
        FraudAssessment.RiskLevel riskLevel = FraudAssessment.RiskLevel.of(score, threshold);
        Metrics.counter("fraud.assessments", "risk", riskLevel.name()).increment();
        monitor.recordAssessment(transaction.transactionId(), score >= threshold);
        return new FraudAssessment(
                transaction.transactionId(),
                score,
                score >= threshold,
                riskLevel,
                threshold,
                RiskIndicators.of(transaction),
                current.metadata().trainedAt());
    }

    public List<FraudAssessment> assessAll(List<Transaction> transactions) {
        return transactions.stream().map(this::assess).toList();
    }

    public ModelInfo modelInfo() {
        return ModelInfo.of(currentModel().metadata(), properties.model().threshold());
    }

    /**
     * Records the confirmed outcome of a transaction the active model scored.
     *
     * @return {@code false} if no decision for this transaction is waiting for an outcome
     */
    public boolean recordOutcome(String transactionId, boolean fraud) {
        return monitor.recordOutcome(transactionId, fraud);
    }

    public MonitoringReport monitoring() {
        return new MonitoringReport(monitor.snapshot(), lastRetraining);
    }

    /**
     * @param performance    PoC monitoring of the active model, see {@link PerformanceMonitor}
     * @param lastRetraining the latest scheduled retraining since the application started, or {@code null}
     */
    public record MonitoringReport(PerformanceMonitor.Snapshot performance, RetrainingResult lastRetraining) {
    }

    /**
     * Outcome of a scheduled retraining run. Both PR AUC values are measured on the new model's test split.
     *
     * @param activated      whether the new model replaced the active one
     * @param candidatePrAuc PR AUC of the new model, or {@code null} if it was not trained
     * @param activePrAuc    PR AUC of the model that was active before, on the same rows, or {@code null}
     */
    public record RetrainingResult(Instant finishedAt, boolean activated, Double candidatePrAuc, Double activePrAuc,
            String message) {
    }

    /** Runs on the schedule in {@code fraud.retraining.cron}, which is off by default. */
    @Scheduled(cron = "${fraud.retraining.cron:-}")
    void scheduledRetraining() {
        try {
            retrainIfBetter();
        } catch (RuntimeException e) {
            log.error("Scheduled retraining failed; the active model keeps serving", e);
            lastRetraining = new RetrainingResult(Instant.now(), false, null, null, "Retraining failed: " + e.getMessage());
        }
    }

    /**
     * Trains a new model on the configured data and activates it only if it is not worse than the active model.
     * <p>
     * Retraining only helps when new labelled data has arrived. With CSV data it reads the file again, so new
     * labelled rows must be added to it first. With synthetic data each run generates a new data set with the next
     * seed, which stands in for newly collected data. Both models are scored on the new model's test split, so the
     * comparison uses the same rows.
     */
    RetrainingResult retrainIfBetter() {
        if (!trainingLock.tryLock()) {
            return remember(new RetrainingResult(Instant.now(), false, null, null, "Skipped: training is already in progress"));
        }
        try {
            if (model.get() == null) {
                return remember(new RetrainingResult(Instant.now(), false, null, null,
                        "Skipped: no model is loaded, so there is nothing to compare with"));
            }
            retrainings++;
            Training cfg = properties.training().withOverrides(null, null, null, properties.training().seed() + retrainings);
            TrainingRun run = trainer.trainWithTestSet(trainer.loadData(cfg), cfg);
            FraudModel candidate = run.model();
            double candidatePrAuc = candidate.metadata().evaluation().prAuc();
            double activePrAuc;
            modelLock.readLock().lock();
            try {
                activePrAuc = stopped ? Double.NaN : ModelEvaluator.prAuc(model.get(), run.testSet());
            } finally {
                modelLock.readLock().unlock();
            }
            if (Double.isNaN(activePrAuc)) {
                closeQuietly(candidate);
                return remember(new RetrainingResult(Instant.now(), false, candidatePrAuc, null, "Skipped: shutting down"));
            }
            if (candidatePrAuc < activePrAuc - properties.retraining().qualityTolerance()) {
                closeQuietly(candidate);
                return remember(new RetrainingResult(Instant.now(), false, candidatePrAuc, activePrAuc, String.format(
                        Locale.ROOT, "Kept the active model: the new model's PR AUC %.4f is more than %.4f below %.4f",
                        candidatePrAuc, properties.retraining().qualityTolerance(), activePrAuc)));
            }
            try {
                repository.save(candidate, true);
            } catch (RuntimeException e) {
                closeQuietly(candidate);
                throw e;
            }
            activate(candidate);
            return remember(new RetrainingResult(Instant.now(), true, candidatePrAuc, activePrAuc, String.format(
                    Locale.ROOT, "Activated the new model: PR AUC %.4f against %.4f for the previous model",
                    candidatePrAuc, activePrAuc)));
        } finally {
            trainingLock.unlock();
        }
    }

    private RetrainingResult remember(RetrainingResult result) {
        log.info("Scheduled retraining: {}", result.message());
        lastRetraining = result;
        return result;
    }

    boolean modelLoaded() {
        return model.get() != null;
    }

    /** Describes the data a training run would use, from the configured source. */
    public DatasetSummary dataSummary() {
        String source = properties.data().source().name().toLowerCase(Locale.ROOT);
        return DatasetSummary.of(source, trainer.loadData(properties.training()));
    }

    /**
     * Trains and activates a new model. An existing model is only replaced when {@code overwrite} is
     * set, and if training or saving fails it stays in place and keeps serving.
     */
    public ModelInfo train(Training cfg, boolean overwrite) {
        if (!trainingLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Training is already in progress");
        }
        try {
            boolean replacing = repository.exists();
            if (replacing && !overwrite) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A model already exists in "
                        + repository.dir().toAbsolutePath() + "; retrain with overwrite=true to replace it");
            }
            FraudModel trained = trainer.train(cfg);
            try {
                repository.save(trained, overwrite);
            } catch (RuntimeException e) {
                closeQuietly(trained);
                throw e;
            }
            if (replacing) {
                log.warn("Replaced the fraud model in {}; the previous model was not kept",
                        repository.dir().toAbsolutePath());
            }
            activate(trained);
            return ModelInfo.of(trained.metadata(), properties.model().threshold());
        } finally {
            trainingLock.unlock();
        }
    }

    private FraudModel currentModel() {
        FraudModel current = model.get();
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No fraud model is loaded yet");
        }
        return current;
    }

    /** Waits for scoring in progress, so the model that gets closed can't still be in use. */
    private void activate(FraudModel next) {
        FraudModel retired;
        modelLock.writeLock().lock();
        try {
            // nothing would ever close a model activated after shutdown
            retired = stopped ? next : model.getAndSet(next);
        } finally {
            modelLock.writeLock().unlock();
        }
        if (retired != next) {
            // monitoring describes the active model, so it starts again with a new one
            monitor.reset();
        }
        closeQuietly(retired);
    }

    @PreDestroy
    void shutdown() {
        FraudModel retired;
        modelLock.writeLock().lock();
        try {
            stopped = true;
            retired = model.getAndSet(null);
        } finally {
            modelLock.writeLock().unlock();
        }
        closeQuietly(retired);
    }

    private static void closeQuietly(FraudModel model) {
        if (model == null) {
            return;
        }
        try {
            model.close();
        } catch (Exception e) {
            log.warn("Failed to release model resources", e);
        }
    }
}
