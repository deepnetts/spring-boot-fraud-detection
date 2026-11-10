package com.example.frauddetection.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.model.FraudModel;

/**
 * The model that answers scoring requests. It can be replaced while requests are running: a request in progress
 * keeps the model it started with, and a replaced model is closed only once those requests have finished.
 */
final class ActiveModel {

    private static final Logger log = LoggerFactory.getLogger(ActiveModel.class);

    private final AtomicReference<FraudModel> current = new AtomicReference<>();
    // work with a model holds the read lock, so a model can't be closed while a request is using it;
    // fair, so a waiting swap can't be starved by constant scoring traffic
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private boolean stopped; // guarded by lock

    /** Runs {@code work} with the active model, which stays open until it returns. Without a model: 503. */
    <T> T use(Function<FraudModel, T> work) {
        lock.readLock().lock();
        try {
            return work.apply(get());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Like {@link #use}, but empty when no model is active, for example while the application stops. */
    <T> Optional<T> useIfActive(Function<FraudModel, T> work) {
        lock.readLock().lock();
        try {
            FraudModel model = current.get();
            return stopped || model == null ? Optional.empty() : Optional.of(work.apply(model));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The active model, for reading its metadata. Without a model: 503. */
    FraudModel get() {
        FraudModel model = current.get();
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No fraud model is loaded yet");
        }
        return model;
    }

    boolean isLoaded() {
        return current.get() != null;
    }

    /**
     * Makes {@code next} the active model and closes the one it replaces, after the requests using it finish.
     *
     * @return {@code true} if {@code next} became active; after {@link #stop} it is closed instead
     */
    boolean activate(FraudModel next) {
        FraudModel retired;
        lock.writeLock().lock();
        try {
            // nothing would ever close a model activated after stop
            retired = stopped ? next : current.getAndSet(next);
        } finally {
            lock.writeLock().unlock();
        }
        closeQuietly(retired);
        return retired != next;
    }

    /** Closes the active model once the requests using it finish. No model is activated afterwards. */
    void stop() {
        FraudModel retired;
        lock.writeLock().lock();
        try {
            stopped = true;
            retired = current.getAndSet(null);
        } finally {
            lock.writeLock().unlock();
        }
        closeQuietly(retired);
    }

    static void closeQuietly(FraudModel model) {
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
