package com.example.frauddetection.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.frauddetection.config.FraudProperties;
import com.example.frauddetection.config.FraudProperties.Training;
import com.example.frauddetection.data.SyntheticTransactionGenerator;
import com.example.frauddetection.domain.Transaction;
import com.example.frauddetection.model.FraudModel;
import com.example.frauddetection.model.FraudModelTrainer;
import com.example.frauddetection.model.ModelLoadException;
import com.example.frauddetection.model.ModelLoadException.Reason;
import com.example.frauddetection.model.ModelRepository;

class FraudDetectionServiceTest {

    private static final String MODEL_FILE = "fraud-model.bin";
    private static final Training TRAINING = new Training(2000, 0.05, 0.25, 0.3, List.of(8), 2, 0.001f, 0.0001f, 1);
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

    private FraudDetectionService service(boolean trainIfMissing) {
        FraudProperties properties = properties(dir, trainIfMissing);
        var service = new FraudDetectionService(properties, countingTrainer(), new ModelRepository(properties));
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
        return new FraudProperties(new FraudProperties.Model(dir, trainIfMissing, 0.5), TRAINING);
    }

    private static byte[] patch(byte[] bytes, String from, String to) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains(from));
        return text.replace(from, to).getBytes(StandardCharsets.ISO_8859_1);
    }
}
