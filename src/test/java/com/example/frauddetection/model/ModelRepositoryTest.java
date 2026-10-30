package com.example.frauddetection.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.features.FeatureExtractor;
import com.example.frauddetection.model.ModelLoadException.Reason;

class ModelRepositoryTest {

    private static final Training TRAINING = new Training(2000, 0.05, 0.25, 0.3, List.of(8), 2, 0.001f, 0.0001f, 1);

    private static FraudModel trained;

    @TempDir
    Path dir;

    private ModelRepository repository;
    private Path file;

    @BeforeAll
    static void trainModel() {
        trained = new FraudModelTrainer(properties(Path.of("unused"))).train(TRAINING);
    }

    @AfterAll
    static void closeModel() throws Exception {
        trained.close();
    }

    @BeforeEach
    void createRepository() {
        repository = new ModelRepository(properties(dir));
        file = dir.resolve(ModelRepository.MODEL_FILE);
    }

    private static FraudProperties properties(Path dir) {
        return new FraudProperties(new FraudProperties.Model(dir, false, 0.5), TRAINING);
    }

    @Test
    void reloadedModelPredictsSameAsTrainedModel() throws Exception {
        var generator = new SyntheticTransactionGenerator(7);
        List<Transaction> transactions = List.of(generator.fraudulent(), generator.legitimate(), generator.fraudulent());

        repository.save(trained, false);
        try (FraudModel loaded = repository.load().orElseThrow()) {
            for (Transaction tx : transactions) {
                assertEquals(trained.predict(tx), loaded.predict(tx), 1e-6);
            }
        }
    }

    @Test
    void missingModelIsEmpty() {
        assertEquals(Optional.empty(), repository.load());
    }

    @Test
    void randomBytesAreUnreadable() throws Exception {
        Files.write(file, "this is not a model".getBytes(StandardCharsets.UTF_8));

        assertRejected(Reason.UNREADABLE, "could not be read");
    }

    @Test
    void truncatedModelIsUnreadable() throws Exception {
        byte[] bytes = validModelBytes();
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));

        assertRejected(Reason.UNREADABLE, "could not be read");
    }

    @Test
    void fileHoldingAnotherObjectIsUnreadable() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(List.of("not", "a", "model")));
        }
        Files.write(file, bytes.toByteArray());

        assertRejected(Reason.UNREADABLE, "not a fraud model file");
    }

    @Test
    void differentFeaturesAreIncompatible() throws Exception {
        Files.write(file, patch(validModelBytes(), "failed_attempts_24h", "failed_attempts_25h"));

        ModelLoadException failure = assertRejected(Reason.INCOMPATIBLE, "is incompatible with the current code");

        assertTrue(failure.getMessage().contains("Added: [failed_attempts_24h]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Removed: [failed_attempts_25h]"), failure.getMessage());
        assertTrue(failure.getMessage().contains(FeatureExtractor.NUM_FEATURES + " features"), failure.getMessage());
    }

    @Test
    void modelSavedUnderAnotherClassNameIsIncompatible() throws Exception {
        // what a renamed package looks like to the loader
        Files.write(file, patch(validModelBytes(), "ModelMetadata", "ModelMetadatX"));

        assertRejected(Reason.INCOMPATIBLE, "different version of the code");
    }

    @Test
    void saveWithoutOverwriteRefusesToReplaceAModel() throws Exception {
        Files.write(file, "existing".getBytes(StandardCharsets.UTF_8));

        var failure = assertThrows(UncheckedIOException.class, () -> repository.save(trained, false));

        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        assertEquals("existing", Files.readString(file));
        assertEquals(List.of(ModelRepository.MODEL_FILE), fileNames());
    }

    @Test
    void saveWithOverwriteReplacesTheModel() throws Exception {
        Files.write(file, "existing".getBytes(StandardCharsets.UTF_8));

        repository.save(trained, true);

        assertEquals(trained.metadata().trainedAt(), repository.load().orElseThrow().metadata().trainedAt());
        assertEquals(List.of(ModelRepository.MODEL_FILE), fileNames());
    }

    @Test
    void failedWriteKeepsTheExistingModelAndRemovesItsTemporaryFile() throws Exception {
        Files.write(file, "existing".getBytes(StandardCharsets.UTF_8));

        assertThrows(UncheckedIOException.class, () -> repository.save(unwritableModel(), true));

        assertEquals("existing", Files.readString(file));
        assertEquals(List.of(ModelRepository.MODEL_FILE), fileNames());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failedReplaceKeepsTheExistingModelAndRemovesItsTemporaryFile() throws Exception {
        Files.write(file, "existing".getBytes(StandardCharsets.UTF_8));

        // Windows refuses to replace a file that is open
        try (var ignored = new FileInputStream(file.toFile())) {
            assertThrows(UncheckedIOException.class, () -> repository.save(trained, true));
        }

        assertEquals("existing", Files.readString(file));
        assertEquals(List.of(ModelRepository.MODEL_FILE), fileNames());
    }

    @Test
    void saveNeverTouchesTemporaryFilesItDidNotCreate() throws Exception {
        Path sharedName = dir.resolve(ModelRepository.MODEL_FILE + ".tmp");
        Path otherSave = dir.resolve(ModelRepository.MODEL_FILE + ".other.tmp");
        Files.writeString(sharedName, "someone else's");
        Files.writeString(otherSave, "someone else's");

        repository.save(trained, false);
        assertThrows(UncheckedIOException.class, () -> repository.save(unwritableModel(), true));

        assertEquals("someone else's", Files.readString(sharedName));
        assertEquals("someone else's", Files.readString(otherSave));
        assertEquals(3, fileNames().size());
    }

    private ModelLoadException assertRejected(Reason reason, String expectedMessage) throws IOException {
        byte[] before = Files.readAllBytes(file);
        List<String> filesBefore = fileNames();

        var failure = assertThrows(ModelLoadException.class, repository::load);

        assertEquals(reason, failure.reason());
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
        assertTrue(failure.getMessage().contains(file.toAbsolutePath().toString()), failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(filesBefore, fileNames());
        return failure;
    }

    private byte[] validModelBytes() throws IOException {
        repository.save(trained, false);
        byte[] bytes = Files.readAllBytes(file);
        Files.delete(file);
        return bytes;
    }

    private static byte[] patch(byte[] bytes, String from, String to) {
        assertEquals(from.length(), to.length());
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains(from));
        return text.replace(from, to).getBytes(StandardCharsets.ISO_8859_1);
    }

    private List<String> fileNames() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** Its metadata can't be serialized, so saving fails part way through writing the file. */
    private static FraudModel unwritableModel() {
        ModelMetadata m = trained.metadata();
        List<String> names = new AbstractList<>() {
            @Override
            public String get(int index) {
                return m.featureNames().get(index);
            }

            @Override
            public int size() {
                return m.featureNames().size();
            }
        };
        return new FraudModel(trained.network(), new ModelMetadata(m.trainedAt(), names, m.scaler(), m.hiddenLayers(),
                m.trainingSamples(), m.epochs(), m.trainingSeconds(), m.evaluation()));
    }
}
