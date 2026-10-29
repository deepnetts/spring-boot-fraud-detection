package com.example.frauddetection.model;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.frauddetection.config.FraudProperties;

import deepnetts.net.FeedForwardNetwork;

/**
 * Persists a model as one file holding its metadata (scaler, metrics, architecture) and the
 * network's trained parameters. On load the network is rebuilt from the architecture and the
 * parameters are copied in (see {@link NetworkParameters} for why Deep Netts' own save/load isn't used).
 */
@Component
public class ModelRepository {

    static final String MODEL_FILE = "fraud-model.bin";

    private final Path dir;

    public ModelRepository(FraudProperties properties) {
        this.dir = properties.model().dir();
    }

    public void save(FraudModel model) {
        try {
            Files.createDirectories(dir);
            // write to a temp file first so a crash never leaves a half-written model behind
            Path tmp = dir.resolve(MODEL_FILE + ".tmp");
            try (var out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(new SavedModel(model.metadata(), NetworkParameters.capture(model.network())));
            }
            Files.move(tmp, dir.resolve(MODEL_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save model to " + dir.toAbsolutePath(), e);
        }
    }

    public Optional<FraudModel> load() {
        Path file = dir.resolve(MODEL_FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (var in = new ObjectInputStream(Files.newInputStream(file))) {
            SavedModel saved = (SavedModel) in.readObject();
            FeedForwardNetwork network = FraudModelTrainer.buildNetwork(saved.metadata().hiddenLayers(), 0);
            saved.parameters().restoreInto(network);
            return Optional.of(new FraudModel(network, saved.metadata()));
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Could not load model from " + file.toAbsolutePath(), e);
        }
    }

    public Path dir() {
        return dir;
    }

    private record SavedModel(ModelMetadata metadata, NetworkParameters parameters) implements Serializable {
    }
}
