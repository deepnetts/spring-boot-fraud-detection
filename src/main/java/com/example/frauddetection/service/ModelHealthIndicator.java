package com.example.frauddetection.service;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Shows as {@code model} in {@code /actuator/health}, and readiness depends on it. */
@Component
class ModelHealthIndicator implements HealthIndicator {

    private final FraudDetectionService service;

    ModelHealthIndicator(FraudDetectionService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        return service.modelLoaded() ? Health.up().build() : Health.outOfService().build();
    }
}
