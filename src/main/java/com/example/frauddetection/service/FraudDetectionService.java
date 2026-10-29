package com.example.frauddetection.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.model.FraudModel;
import com.example.frauddetection.model.FraudModelTrainer;
import com.example.frauddetection.model.ModelRepository;

import jakarta.annotation.PreDestroy;

@Service
public class FraudDetectionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final FraudProperties properties;
    private final FraudModelTrainer trainer;
    private final ModelRepository repository;
    private final AtomicReference<FraudModel> model = new AtomicReference<>();
    private final ReentrantLock trainingLock = new ReentrantLock();

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
            model.set(loaded.get());
        } else if (properties.model().trainIfMissing()) {
            log.info("No fraud model found in {}, training a new one", repository.dir().toAbsolutePath());
            train(properties.training());
        } else {
            log.warn("No fraud model found in {}; scoring is unavailable until one is trained",
                    repository.dir().toAbsolutePath());
        }
    }

    public FraudAssessment assess(Transaction transaction) {
        FraudModel current = currentModel();
        double threshold = properties.model().threshold();
        double probability = current.predict(transaction);
        return new FraudAssessment(
                transaction.transactionId(),
                probability,
                probability >= threshold,
                FraudAssessment.RiskLevel.of(probability, threshold),
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

    public ModelInfo train(Training cfg) {
        if (!trainingLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Training is already in progress");
        }
        try {
            FraudModel trained = trainer.train(cfg);
            repository.save(trained);
            closeQuietly(model.getAndSet(trained));
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

    @PreDestroy
    void shutdown() {
        closeQuietly(model.getAndSet(null));
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
