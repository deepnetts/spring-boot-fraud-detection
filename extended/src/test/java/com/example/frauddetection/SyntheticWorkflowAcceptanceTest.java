package com.example.frauddetection;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "fraud.model.dir=target/test-model-synthetic-${random.uuid}",
        "fraud.training.samples=3000",
        "fraud.training.max-epochs=3"
})
class SyntheticWorkflowAcceptanceTest extends WorkflowAcceptanceTest {

    @Override
    String expectedSource() {
        return "synthetic";
    }

    @Override
    int expectedRows() {
        return 3000;
    }

    @Override
    int expectedFraudRows() {
        return 90;
    }
}
