# Fraud Detection with Deep Netts: Minimal Example

This example shows the essential workflow for detecting card fraud with a Deep Netts neural network inside a Spring
Boot application. It reads labelled transactions from a CSV file, trains a network on the first start, evaluates it,
saves it, and scores new transactions through one REST endpoint.

The application is a REST API. It has no graphical interface.

## Prerequisites

- JDK 25 (required by Deep Netts 4.x)
- Maven 3.9 or later

## Quick Start

1. Start the application:

   ```bash
   cd minimal
   mvn spring-boot:run
   ```

   On the first start the application trains the network, saves it to `model/fraud-model.dnet` and logs an
   evaluation. Training takes a few seconds. Later starts load the saved model.

2. Score a transaction:

   ```bash
   curl -H "Content-Type: application/json" \
        -d '{"amount": 950, "customerAvgAmount": 45, "hourOfDay": 3, "distanceFromHomeKm": 850, "transactionsLast24h": 11, "failedAttemptsLast24h": 3, "online": true, "newDevice": true, "foreign": true}' \
        http://localhost:8080/api/fraud/predict
   ```

   In Windows PowerShell:

   ```powershell
   Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/fraud/predict `
     -ContentType "application/json" `
     -Body '{"amount": 950, "customerAvgAmount": 45, "hourOfDay": 3, "distanceFromHomeKm": 850, "transactionsLast24h": 11, "failedAttemptsLast24h": 3, "online": true, "newDevice": true, "foreign": true}'
   ```

   Response:

   ```json
   {"fraudScore": 1.0, "fraud": true}
   ```

More requests are in [`requests.http`](requests.http). You can run them from IntelliJ IDEA or VS Code, or use them as a
reference for Postman or another HTTP client.

To retrain the model, stop the application, delete the `model` directory and start the application again.

## API Reference

### `POST /api/fraud/predict`

Returns a fraud score for one transaction.

**Request body.** All fields are required. A missing value is rejected, never replaced with a default.

| Field                   | Type    | Constraints | Description                                                 |
|-------------------------|---------|-------------|-------------------------------------------------------------|
| `amount`                | number  | ≥ 0         | Payment amount in EUR                                       |
| `customerAvgAmount`     | number  | > 0         | The customer's average payment amount in EUR                |
| `hourOfDay`             | integer | 0 to 23     | Hour of the payment                                         |
| `distanceFromHomeKm`    | number  | ≥ 0         | Distance between the customer's home and the payment        |
| `transactionsLast24h`   | integer | ≥ 0         | Payments on the card in the previous 24 hours               |
| `failedAttemptsLast24h` | integer | ≥ 0         | Declined or failed attempts in the previous 24 hours        |
| `online`                | boolean |             | Online payment (`false` for a payment in a store)           |
| `newDevice`             | boolean |             | A device the customer has not used before                   |
| `foreign`               | boolean |             | The merchant is in another country than the card's issuer   |

**Response body**

| Field        | Type    | Description                                                              |
|--------------|---------|--------------------------------------------------------------------------|
| `fraudScore` | number  | Score from 0 to 1. Higher means more suspicious                          |
| `fraud`      | boolean | `true` when `fraudScore` is at or above `fraud.threshold` in `application.properties` |

`fraudScore` ranks transactions and is compared with the threshold. It is not a calibrated probability: a score of
0.8 does not mean an 80 % chance of fraud.

**Errors**

| Status            | Cause                                        |
|-------------------|----------------------------------------------|
| `400 Bad Request` | A field is missing or outside its constraints |

**Example scenarios**, with the model trained on the included data:

| Transaction                                                                        | `fraud` |
|------------------------------------------------------------------------------------|---------|
| €950 online, 850 km from home at 3 AM, new device, 11 payments and 3 failed attempts today | `true`  |
| €42.10 in a store near home at 1 PM, known device, 2 payments today                | `false` |

## How It Works

All machine learning code is in [`FraudModel`](src/main/java/com/example/frauddetection/minimal/FraudModel.java), with
one method per step:

| Step                | Method          | Description                                                                                      |
|---------------------|-----------------|--------------------------------------------------------------------------------------------------|
| 1. Data             | `loadData`      | Reads a CSV file with Deep Netts `DataSets.readCsv`. Each row becomes the same `TransactionRequest` that the REST endpoint receives, with its 0/1 `fraud` label |
| 2. Training         | `train`         | Scales each input with a Deep Netts `Standardizer`, then trains a network 9 → 16 (ReLU) → 2 (softmax) with cross-entropy loss and the Adam optimizer for 30 epochs |
| 3. Evaluation       | `evaluate`      | Counts correct and wrong answers on test transactions the network has never seen                  |
| 4. Saving / loading | `save` / `load` | Writes and reads the network, including its scaler, with Deep Netts `FileIO`                       |
| 5. Prediction       | `predict`       | Scores one request for the REST endpoint                                                         |

On startup, `FraudModel` loads the saved network if the file exists. Otherwise it trains and saves a new one. In both
cases it then logs an evaluation.

**One encoder for every step.** [`TransactionEncoder`](src/main/java/com/example/frauddetection/minimal/TransactionEncoder.java)
turns a `TransactionRequest` into a Deep Netts `Tensor1D` with the nine inputs in CSV column order. It implements the
small generic `TensorEncoder` interface. Training, evaluation and prediction all use the same `FraudModel.ENCODER`, so
the inputs always line up. For training, each label also becomes two outputs, legitimate and fraud.

**Why scaling matters.** The inputs have very different ranges: an amount can be in the thousands, while `online` is 0
or 1. The `Standardizer` gives every input a mean of 0 and a standard deviation of 1, measured on the training data
only. It is stored in the network, so the saved file contains it and every prediction is scaled the same way as the
training data.

**Why two outputs.** The network has one output for "legitimate" and one for "fraud", and the softmax layer makes them
sum to 1. The fraud output is the `fraudScore`.

Project layout:

```text
data/
├── train.csv                        7,500 labelled transactions for training
└── test.csv                         2,500 labelled transactions for evaluation
src/main/java/com/example/frauddetection/minimal/
├── MinimalFraudApplication.java     Spring Boot entry point
├── FraudController.java             REST endpoint
├── TransactionRequest.java          Request record with validation
├── FraudPrediction.java             Response record
├── TensorEncoder.java               Generic interface: business record to Deep Netts tensor
├── TransactionEncoder.java          Turns a TransactionRequest into the network's nine inputs
└── FraudModel.java                  Machine learning workflow
```

Recommended reading order:
1. `TransactionRequest` and `FraudPrediction`
2. `FraudController`
3. `TransactionEncoder`
4. `FraudModel`
5. `data/train.csv`

## Training Data

The data is synthetic. It was generated once with the extended example's
[`SyntheticTransactionGenerator`](../extended/src/main/java/com/example/frauddetection/data/SyntheticTransactionGenerator.java),
using seed 1 for `train.csv` and seed 2 for `test.csv`, and only the nine columns this example uses were kept.

| File        | Rows  | Fraud | Fraud rate |
|-------------|-------|-------|------------|
| `train.csv` | 7,500 | 225   | 3 %        |
| `test.csv`  | 2,500 | 75    | 3 %        |

Fraud is rare, as in real payments, and the data is used as it is: fraud rows are not duplicated or reweighted. The
3 % rate is a demonstration choice. Real card fraud is usually well under 1 %.

`online`, `newDevice` and `foreign` are stored as 1 (true) or 0 (false), and the last column, `fraud`, is the label.

## Evaluation

The application logs this evaluation on startup. These results come from **synthetic test data**. They demonstrate the
workflow and are not estimates of real-world performance.

|                      | Flagged | Not flagged |
|----------------------|--------:|------------:|
| **Fraud**            |      36 |          39 |
| **Legitimate**       |       3 |       2,422 |

| Metric    | Value  | Meaning                                                    |
|-----------|-------:|------------------------------------------------------------|
| Precision | 0.92   | Of the flagged transactions, the share that really is fraud |
| Recall    | 0.48   | Of the fraud cases, the share that is flagged               |
| Accuracy  | 0.9832 | Share of all answers that are right                         |

Accuracy looks excellent, but a "model" that never flags anything would already score 0.9700, because 97 % of the
transactions are legitimate. That is why fraud detection is judged by precision and recall instead.

These numbers show a trade-off. The model's alerts are reliable (36 of 39 are fraud), but it catches about half of the
fraud. Lowering `fraud.threshold` catches more fraud at the cost of more false alarms. The right threshold depends on
what a missed fraud and a false alarm cost the business. The [extended example](../extended/README.md) adds more
signals, more metrics and a recommended threshold.

The training settings (16 hidden neurons, 30 epochs) were picked by comparing a few options on part of `train.csv`, not
on the test data. Training uses a fixed random seed and one thread, so each run gives the same results. On the
reference machine (Windows 11, JDK 25.0.4), training took about 4 seconds, and a start that loads the saved model
took about 3.5 seconds.

## Customization

Delete the `model` directory after any change to the data, inputs or training, so that a new network is trained.

- **Use your own data:** replace `data/train.csv` and `data/test.csv` with your own labelled transactions in the same
  format: numeric columns, 1/0 for yes/no, and `fraud` as the last column. Split by time if you can, with the most
  recent transactions in `test.csv`. Keep the test data out of training and tuning.
- **Change the inputs:**
  1. Add or remove the column in both CSV files.
  2. Update `TransactionRequest` and `TransactionEncoder` (`FEATURE_NAMES` and `encodeToTensor`), in the same order
     as the CSV columns. The network's input size and column names follow from `FEATURE_NAMES`.
- **Change the threshold:** set `fraud.threshold` in `application.properties`.
- **Tune training:** the hidden layer size, learning rate and number of epochs are set in `FraudModel.train`. Compare
  settings on part of the training data, not on `test.csv`.

## Testing

```bash
mvn test
```

The tests check that:
- the CSV files are read with the expected rows, values and labels;
- the encoder puts the fields in the order of the CSV columns;
- the model catches fraud with better than "flag nothing" accuracy on the test data;
- the network scales raw inputs with its stored scaler, and scoring leaves the caller's inputs unscaled;
- a saved and reloaded model gives identical scores;
- the REST endpoint flags the suspicious example, accepts the ordinary one and rejects invalid input.

The tests take about 30 seconds.

## Deep Netts Notes

Checked on Deep Netts 4.0.5. Models saved with 4.0.4 load and give identical scores.

- `network.setNormalizer(scaler)` stores the scaler in the network, and `network.predict` then scales its input
  itself, in place. Pass unscaled inputs to `predict`; scaling them first would scale them twice. `FraudModel.fraudScore`
  passes a copy, so the caller's tensor stays unscaled.
- `FeedForwardNetwork.predict(float[])` fails on a network loaded from a file. Use `network.predict(Tensor1D)`.
- In Deep Netts 4.0.4 a network with a single sigmoid output failed after it was loaded with `FileIO`; 4.0.5 fixes
  this. This example uses two softmax outputs, which load correctly in both versions.
- `TabularDataSet.setColumnNames(...)` is required before training.
- Training uses one thread (`DeepNetts.getInstance().setMaxThreads(1)`, a JVM-wide setting), which is faster for a
  network this small.

## Limitations

- **Synthetic data.** The data comes from a generator with simplified fraud patterns. Results on real data will differ.
- **Few signals.** Nine inputs are enough to show the workflow, not to catch subtle fraud. The extended example uses 27
  features.
- **No monitoring or retraining.** See the extended example.
- **No security.** Run it locally only.

## Learn More

- [Deep Netts Getting Started](https://www.deepnetts.com/cookbook/getting-started/)
- [Deep Netts Developer Guide](https://www.deepnetts.com/cookbook/developer-guide/): loading data, training, evaluation,
  saving and loading, predictions
- [Deep Netts API documentation](https://www.deepnetts.com/apidocs/index.html)
