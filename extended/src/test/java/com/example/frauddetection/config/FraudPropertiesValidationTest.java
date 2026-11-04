package com.example.frauddetection.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

class FraudPropertiesValidationTest {

    @EnableConfigurationProperties(FraudProperties.class)
    static class Config {
    }

    // starts from the shipped application.yml, so each case changes one setting
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void shippedConfigurationIsValid() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @CsvSource({
            "fraud.model.threshold=0,                threshold",
            "fraud.model.threshold=1.5,              threshold",
            "fraud.training.samples=0,               samples",
            "fraud.training.fraud-rate=0,            fraudRate",
            "fraud.training.fraud-rate=1,            fraudRate",
            "fraud.training.test-fraction=0,         testFraction",
            "fraud.training.test-fraction=2,         testFraction",
            "fraud.training.validation-fraction=0,   validationFraction",
            "fraud.training.hidden-layers=,          hiddenLayers",
            "fraud.training.hidden-layers=0,         hiddenLayers[0]",
            "fraud.training.max-epochs=0,            maxEpochs",
            "fraud.training.learning-rate=0,         learningRate",
            "fraud.training.l2-regularization=-1,    l2Regularization",
    })
    void rejectsAnInvalidSettingAndNamesIt(String setting, String field) {
        runner.withPropertyValues(setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failure(context.getStartupFailure())).contains(field + "': rejected value");
        });
    }

    @Test
    void rejectsSplitsThatLeaveNoTrainingData() {
        runner.withPropertyValues("fraud.training.test-fraction=0.6", "fraud.training.validation-fraction=0.4")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failure(context.getStartupFailure())).contains("must add up to less than 1");
                });
    }

    @Test
    void requiresACsvPathWhenTheSourceIsCsv() {
        runner.withPropertyValues("fraud.data.source=csv", "fraud.data.csv-path=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failure(context.getStartupFailure())).contains("fraud.data.csv-path is required");
        });
    }

    private static String failure(Throwable startupFailure) {
        return NestedExceptionUtils.getMostSpecificCause(startupFailure).getMessage();
    }
}
