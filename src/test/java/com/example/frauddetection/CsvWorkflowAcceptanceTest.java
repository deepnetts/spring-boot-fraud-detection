package com.example.frauddetection;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "fraud.data.source=csv",
        "fraud.data.csv-path=data/sample-transactions.csv",
        "fraud.model.dir=target/test-model-csv-workflow-${random.uuid}",
        "fraud.training.max-epochs=3"
})
class CsvWorkflowAcceptanceTest extends WorkflowAcceptanceTest {

    @Override
    String expectedSource() {
        return "csv";
    }

    @Override
    int expectedRows() {
        return 500;
    }

    @Override
    int expectedFraudRows() {
        return 15;
    }
}
