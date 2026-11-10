package com.example.frauddetection.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ActiveModel activeModel = new ActiveModel();
    private final ReentrantLock trainingLock = new ReentrantLock();
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
        FraudAssessment assessment = activeModel.use(model -> {
            double score = model.predict(transaction);
            return new FraudAssessment(
                    transaction.transactionId(),
                    score,
                    score >= threshold,
                    FraudAssessment.RiskLevel.of(score, threshold),
                    threshold,
                    RiskIndicators.of(transaction),
                    model.metadata().trainedAt());
        });
        Metrics.counter("fraud.assessments", "risk", assessment.riskLevel().name()).increment();
        monitor.recordAssessment(transaction.transactionId(), assessment.fraud());
        return assessment;
    }

    public List<FraudAssessment> assessAll(List<Transaction> transactions) {
        return transactions.stream().map(this::assess).toList();
    }

    public ModelInfo modelInfo() {
        return ModelInfo.of(activeModel.get().metadata(), properties.model().threshold());
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
            if (!activeModel.isLoaded()) {
                return remember(new RetrainingResult(Instant.now(), false, null, null,
                        "Skipped: no model is loaded, so there is nothing to compare with"));
            }
            retrainings++;
            Training cfg = properties.training().withOverrides(null, null, null, properties.training().seed() + retrainings);
            TrainingRun run = trainer.trainWithTestSet(trainer.loadData(cfg), cfg);
            FraudModel candidate = run.model();
            double candidatePrAuc = candidate.metadata().evaluation().prAuc();
            // both models are scored on the candidate's test split, so the comparison uses the same rows
            Optional<Double> active = activeModel.useIfActive(model -> ModelEvaluator.prAuc(model, run.testSet()));
            if (active.isEmpty()) {
                ActiveModel.closeQuietly(candidate);
                return remember(new RetrainingResult(Instant.now(), false, candidatePrAuc, null, "Skipped: shutting down"));
            }
            double activePrAuc = active.get();
            if (candidatePrAuc < activePrAuc - properties.retraining().qualityTolerance()) {
                ActiveModel.closeQuietly(candidate);
                return remember(new RetrainingResult(Instant.now(), false, candidatePrAuc, activePrAuc, String.format(
                        Locale.ROOT, "Kept the active model: the new model's PR AUC %.4f is more than %.4f below %.4f",
                        candidatePrAuc, properties.retraining().qualityTolerance(), activePrAuc)));
            }
            try {
                repository.save(candidate, true);
            } catch (RuntimeException e) {
                ActiveModel.closeQuietly(candidate);
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
        return activeModel.isLoaded();
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
                ActiveModel.closeQuietly(trained);
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

    private void activate(FraudModel next) {
        if (activeModel.activate(next)) {
            // monitoring describes the active model, so it starts again with a new one
            monitor.reset();
        }
    }

    @PreDestroy
    void shutdown() {
        activeModel.stop();
    }
}
