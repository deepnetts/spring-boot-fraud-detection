---
name: deep-netts-fraud-detection
description: Customize, extend or review the "Fraud Detection with Deep Netts" examples (Spring Boot and Deep Netts 4.x). Use when replacing transaction fields or training data, changing preprocessing, the network or the prediction API, integrating an example into another Spring Boot application, or checking that a change keeps the machine learning workflow correct in minimal/ or extended/.
---

# Fraud Detection with Deep Netts

This file describes how to change the two examples in this repository without breaking the machine learning workflow.
It is written for developers and for the AI coding assistants they work with. Read the README of the example you are
changing before you start.

## Repository map

| Concern                   | `minimal/` (package `com.example.frauddetection.minimal`) | `extended/` (package `com.example.frauddetection`)          |
|---------------------------|-----------------------------------------------------------|-------------------------------------------------------------|
| Request record            | `TransactionRequest`                                      | `domain/Transaction`                                        |
| Training and test data    | `data/train.csv`, `data/test.csv`                         | `data/SyntheticTransactionGenerator`, `data/CsvTransactionReader` |
| Preprocessing             | `TransactionEncoder` (a `TensorEncoder`), Deep Netts `Standardizer` | `features/FeatureExtractor` (a `TensorEncoder`), `features/FeatureScaler` |
| Training and evaluation   | `FraudModel.train`, `FraudModel.evaluate`                 | `model/FraudModelTrainer`, `model/ModelEvaluator`           |
| Saving and loading        | `FraudModel.save`, `FraudModel.load` (Deep Netts `FileIO`) | `model/ModelRepository`, `model/NetworkParameters`          |
| Prediction and REST API   | `FraudModel.predict`, `FraudController`                   | `service/FraudDetectionService`, `service/ActiveModel`, `web/FraudDetectionController` |
| Monitoring and retraining | not included                                              | `service/PerformanceMonitor`, `FraudDetectionService.retrainIfBetter` |
| Configuration             | `application.properties`                                  | `application.yml`, `config/FraudProperties`                 |
| Field definitions         | `TransactionRequest` Javadoc                              | `docs/DATA_CONTRACT.md`                                     |

## Rules

1. **Inputs must line up.** Every input must be in the same position for training and prediction.
   - Training, evaluation and prediction turn a record into a tensor with the same `TensorEncoder`. Do not build
     input tensors by hand.
   - Minimal: the CSV columns, `TransactionEncoder.FEATURE_NAMES`, `TransactionEncoder.encodeToTensor` and
     `TransactionRequest` must agree.
   - Extended: `FeatureExtractor.FEATURE_NAMES` and `FeatureExtractor.encodeToTensor` must agree. A saved model
     records its feature names and is rejected at startup if they changed.
2. **Keep the test data independent.** Never train on the test data, and never choose settings or a threshold by
   looking at test results.
   - Minimal: `data/test.csv` is for evaluation only. Compare settings on part of `data/train.csv`.
   - Extended: the validation split chooses the recommended threshold, and the test split gives the final metrics.
3. **Keep the data imbalanced.** Fraud is rare. Do not duplicate, drop or reweight rows to balance the classes unless
   a comparison on validation data shows a benefit, and report that comparison.
4. **Never invent values.** A missing field is rejected with `400`. Do not replace unknown values with `0`, `false` or
   another default, because the model would treat them as real data.
5. **Delete the saved model after changing inputs, outputs or training.**
   - Minimal: delete `minimal/model/`. The example does not detect an outdated model.
   - Extended: move `fraud-model.bin` out of `extended/model/`. Startup stops with a message if the saved features no
     longer match.
6. **Keep the Deep Netts workarounds** (checked on 4.0.5).
   - Predict with `network.predict(Tensor1D)`, not `predict(float[])`, which still fails on a loaded network.
   - Set `TabularDataSet.setColumnNames(...)` before training.
   - Minimal: the network stores its `Standardizer` (`setNormalizer`) and scales its input tensor itself, in place.
     Pass unscaled inputs, and give `predict` a copy so the caller's tensor stays unchanged (`FraudModel.fraudScore`).
   - Extended: keep the sigmoid output with `ModelRepository` and `NetworkParameters`. Do not switch it to `FileIO`:
     that changes the model file format, so saved models would no longer load.
7. **Report results honestly.** Results on synthetic data are simulation results, not real-world performance.
   `fraudScore` is not a calibrated probability. Every number in a README must be measured. Do not commit trained
   models (`*.dnet`, `fraud-model.bin`) or build output.

## Procedures

### Replace the transaction fields

1. List the customer's fields and decide, for each one, whether it is known at the moment of payment. Fields known
   only later, such as a chargeback, must not become inputs.
2. Minimal: update both CSV files, `TransactionRequest` and `TransactionEncoder`.
3. Extended: update `Transaction`, `FeatureExtractor`, `CsvTransactionReader`, `SyntheticTransactionGenerator` and
   `docs/DATA_CONTRACT.md`. Section 9 of the data contract lists every step.
4. Update `requests.http`, the README request tables and the tests. Delete the saved model.

### Train on real data

1. Export labelled transactions with the label known after the fact, for example from chargebacks.
2. Split by time: older transactions for training, the most recent for testing. A random split lets the model learn
   from the future.
3. Compute history fields, such as `transactionsLast24h`, from earlier events only.
4. Report the metrics as measured on the held-out real data, together with the fraud rate of that data.

### Change the prediction API

1. Keep request and response types as Java records with Bean Validation.
2. Keep the score and the decision separate: the model gives a score, the threshold turns it into a decision.
3. Update `requests.http`, the README and the controller tests.

### Integrate into an existing Spring Boot application

1. Copy the model class (minimal `FraudModel`, or extended `model/` and `features/`) and the request and response
   records. Keep the Deep Netts dependency version.
2. Train offline or on startup, and load the saved model where the application needs it.
3. Call the model from the existing service layer, not only from a new controller.
4. Make sure only one prediction runs on a network at a time, as both examples do.

## Verification

Run these steps in the directory of the changed example:

1. `mvn test`
2. Delete the saved model, run `mvn spring-boot:run`, and read the evaluation in the log (minimal) or at
   `GET /api/model` (extended).
3. Send the requests in `requests.http` and compare the results with the README.
4. Restart and confirm that the log reports a loaded model and no training.
5. Update README numbers only with newly measured values.
