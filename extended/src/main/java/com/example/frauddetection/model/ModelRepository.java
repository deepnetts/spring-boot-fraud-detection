package com.example.frauddetection.model;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.model.ModelLoadException.Reason;

import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.Mode;

/**
 * Persists a model as one file holding the Deep Netts network and its metadata (feature names, scaler, metrics). The
 * network is written the way Deep Netts {@code FileIO.writeToFile} writes it, in inference mode with Java
 * serialization, but in the same file as the metadata, so the two can only be replaced together.
 */
@Component
public class ModelRepository {

    static final String MODEL_FILE = "fraud-model.bin";

    private static final Logger log = LoggerFactory.getLogger(ModelRepository.class);

    private final Path dir;
    private final Path file;

    public ModelRepository(FraudProperties properties) {
        this.dir = properties.model().dir();
        this.file = dir.resolve(MODEL_FILE);
    }

    /** True unless the model is confirmed missing, so a file that can't be checked is never replaced. */
    public boolean exists() {
        return !Files.notExists(file);
    }

    /**
     * Saves the model, leaving any existing model untouched unless the new one is complete and
     * {@code overwrite} is set. The new file is only swapped in after it is on disk and reads back.
     */
    public void save(FraudModel model, boolean overwrite) {
        Path tmp = null;
        boolean moved = false;
        try {
            Files.createDirectories(dir);
            // a unique name, so this file can't be shared with a concurrent save
            tmp = Files.createTempFile(dir, MODEL_FILE + ".", ".tmp");
            write(model, tmp);
            checkFeatures(read(tmp), tmp);
            if (overwrite) {
                // never fall back to REPLACE_EXISTING: it isn't atomic and could lose the existing model
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(tmp, file);
            }
            moved = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save model to " + dir.toAbsolutePath(), e);
        } finally {
            if (tmp != null && !moved) {
                deleteTemporaryFile(tmp);
            }
        }
    }

    public Optional<FraudModel> load() {
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        SavedModel saved = read(file);
        checkFeatures(saved, file);
        return Optional.of(new FraudModel(saved.network(), saved.metadata()));
    }

    public Path dir() {
        return dir;
    }

    private static void write(FraudModel model, Path tmp) throws IOException {
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE);
                var out = new ObjectOutputStream(Channels.newOutputStream(channel))) {
            // as Deep Netts FileIO.writeToFile does: the network is saved ready for predictions
            model.network().setMode(Mode.INFERENCE);
            out.writeObject(new SavedModel(model.metadata(), model.network()));
            out.flush();
            // on disk before the swap, so a power cut can't leave a damaged model in place
            channel.force(true);
        }
    }

    private static SavedModel read(Path path) {
        try (var in = new ObjectInputStream(Files.newInputStream(path))) {
            if (in.readObject() instanceof SavedModel saved && saved.metadata() != null) {
                if (saved.network() == null) {
                    // written by the earlier version of this example, which saved only the weights
                    throw new ModelLoadException(Reason.INCOMPATIBLE, path,
                            "it was saved in an older format without the network; train a new model", null);
                }
                return saved;
            }
            throw new StreamCorruptedException("not a fraud model file");
        } catch (ModelLoadException e) {
            throw e;
        } catch (ClassNotFoundException | InvalidClassException e) {
            throw new ModelLoadException(Reason.INCOMPATIBLE, path,
                    "it was written by a different version of the code (" + e + ")", e);
        } catch (IOException | RuntimeException e) {
            throw new ModelLoadException(Reason.UNREADABLE, path, e.toString(), e);
        }
    }

    private static void checkFeatures(SavedModel saved, Path path) {
        try {
            FraudModel.requireCurrentFeatures(saved.metadata().featureNames());
        } catch (IllegalArgumentException e) {
            throw new ModelLoadException(Reason.INCOMPATIBLE, path, e.getMessage(), null);
        }
    }

    private static void deleteTemporaryFile(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            log.warn("Could not delete temporary model file {}", tmp.toAbsolutePath(), e);
        }
    }

    private record SavedModel(ModelMetadata metadata, FeedForwardNetwork network) implements Serializable {
    }
}
