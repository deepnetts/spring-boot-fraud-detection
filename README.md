# Fraud Detection (Spring Boot + Deep Netts)

A REST service that scores card transactions for fraud with a feed-forward neural network
trained using [Deep Netts](https://www.deepnetts.com) (`com.deepnetts:deepnetts-core`).

- Java 25 and Maven 3.9 or newer. Built on Spring Boot 4.0 and Deep Netts 4.0.4
- On first start it trains a model on synthetic transactions (1–2 minutes), saves it to `./model`,
  and loads it on later starts.

## Run

```bash
mvn spring-boot:run
# or
mvn package && java -jar target/fraud-detection-0.1.0-SNAPSHOT.jar
```

## Demo walkthrough

This shows the full workflow with the default settings. Use bash or Git Bash, and start in an empty `./model` folder so the first run trains a new model.

**1. Start the application.** Leave it running, and use a second terminal for the other steps.

```bash
mvn spring-boot:run
```

**2. Wait until it is ready.** While the first model trains, this returns `OUT_OF_SERVICE`. It takes 1–2 minutes.

```bash
curl localhost:8080/actuator/health/readiness
```

**3. Look at the data.**

```bash
curl localhost:8080/api/data/summary
```

Expect `"rows":12000,"fraudRows":360,"fraudRate":0.03`, then the fraud rate by category. Electronics, for example, is about 8% fraud and groceries under 1%.

**4. See how the model did.**

```bash
curl localhost:8080/api/model
```

`evaluation` holds the test results. A first run gives ROC AUC 0.939, PR AUC 0.796, precision 0.91 and recall 0.68 at the 0.5 threshold, with a `recommendedThreshold` of 0.73.

**5. Score a suspicious transaction.**

```bash
curl -X POST localhost:8080/api/transactions/assess -H "Content-Type: application/json" -d '{"transactionId":"tx-1001","amount":2499.99,"customerAvgAmount":45.0,"hourOfDay":3,"merchantCategory":"ELECTRONICS","distanceFromHomeKm":850,"distanceFromLastTransactionKm":790,"minutesSinceLastTransaction":12,"transactionsLast24h":11,"failedAttemptsLast24h":3,"accountAgeDays":400,"cardNotPresent":true,"usedChip":false,"usedPin":false,"foreignTransaction":true,"newDevice":true,"newMerchant":true}'
```

Expect `"fraudScore":1.0,"fraud":true,"riskLevel":"CRITICAL"` and 9 risk indicators.

**6. Score an ordinary transaction.**

```bash
curl -X POST localhost:8080/api/transactions/assess -H "Content-Type: application/json" -d '{"transactionId":"tx-1002","amount":42.10,"customerAvgAmount":55.0,"hourOfDay":13,"merchantCategory":"GROCERY","distanceFromHomeKm":3.2,"distanceFromLastTransactionKm":2.5,"minutesSinceLastTransaction":720,"transactionsLast24h":2,"failedAttemptsLast24h":0,"accountAgeDays":1500,"cardNotPresent":false,"usedChip":true,"usedPin":true,"foreignTransaction":false,"newDevice":false,"newMerchant":false}'
```

Expect a score close to 0, `"fraud":false`, `"riskLevel":"LOW"` and no risk indicators.

**7. Send an invalid request.** The response names every field that failed.

```bash
curl -X POST localhost:8080/api/transactions/assess -H "Content-Type: application/json" -d '{"amount":-5}'
```

**8. Check monitoring.** Scoring is counted by risk level, and health shows whether a model is loaded.

```bash
curl "localhost:8080/actuator/metrics/fraud.assessments?tag=risk:CRITICAL"
```

```bash
curl localhost:8080/actuator/health
```

**9. Try to retrain.** A model already exists, so this is refused with a `409`.

```bash
curl -X POST localhost:8080/api/model/train
```

**10. Retrain on purpose.** This replaces `./model/fraud-model.bin` and the old model is not kept. It takes about a minute, scoring carries on meanwhile, and the metrics will differ slightly from the first run.

```bash
curl -X POST localhost:8080/api/model/train -H "Content-Type: application/json" -d '{"overwrite":true}'
```

**11. Train on a CSV file.** Stop the application, then start it with the sample file and a separate model folder.

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--fraud.data.source=csv --fraud.model.dir=./model/csv"
```

Once it is ready, the summary describes the file, and steps 4 to 8 work the same way:

```bash
curl localhost:8080/api/data/summary
```

Expect `"source":"csv","rows":500,"fraudRows":15`. The sample is small, so this trains in seconds, and its metrics mean nothing because the test split holds only 4 fraud cases.

## Features

Each transaction carries the signals most commonly used in card fraud detection:

| Group | Input fields | Engineered features |
|---|---|---|
| Amount | `amount`, `customerAvgAmount` | log amount, log(amount ÷ customer average) |
| Time | `hourOfDay` | cyclic sin/cos of the hour, night flag (00–06) |
| Geography | `distanceFromHomeKm`, `distanceFromLastTransactionKm`, `foreignTransaction` | log distances, foreign flag |
| Velocity | `transactionsLast24h`, `failedAttemptsLast24h`, `minutesSinceLastTransaction` | counts, log time gap |
| Account | `accountAgeDays` | log account age |
| Channel and authentication | `cardNotPresent`, `usedChip`, `usedPin` | flags |
| Device and merchant | `newDevice`, `newMerchant`, `merchantCategory` | flags, one-hot category (10 values) |

That gives 27 inputs, z-score standardized. The scaler is fitted on the training split and saved with the model.

## Model

- Network: 27 inputs → 32 ReLU → 16 ReLU → 1 sigmoid, binary cross-entropy loss, Adam, L2 regularization
- Data split: 60% training, 15% validation, 25% test, all at the natural fraud rate (3%). A seeded random
  split suits this synthetic data. Real chronological transactions may need a time-based or customer-aware split.
- Class imbalance: the model trains at the natural fraud rate. Oversampling is available with
  `fraud.training.target-fraud-share`, but it gave worse validation results, so it is off by default.
- Evaluation: ROC AUC, PR AUC, precision, recall, F1, false-positive rate and confusion matrix, all on the test
  split. Transactions are flagged at `fraud.model.threshold`, 0.5 by default. A recommended threshold (best F1, 0.73
  for the default model) is chosen on the validation split and reported, never applied automatically. Neither is
  universally optimal: the right threshold depends on business costs.
- Fraud scores: `fraudScore` is for ranking transactions and comparing them with a threshold. It is **not a
  calibrated probability**.
- Synthetic data: `SyntheticTransactionGenerator` mixes legitimate traffic with four fraud patterns:
  stolen card, card testing, account takeover, and a "stealthy" pattern with only weak signals.
  To use your own data, see [Train on your own data](#train-on-your-own-data).

**Results on synthetic data** with the default settings, at threshold 0.5, on 3,000 test transactions containing 90 fraud cases:

| ROC AUC | PR AUC | Precision | Recall | False-positive rate | Fraud caught | Fraud missed | False alarms |
|---|---|---|---|---|---|---|---|
| 0.939 | 0.796 | 0.91 | 0.68 | 0.21% | 61 | 29 | 6 |

These numbers show the pipeline working. They are not evidence of real-world performance, and with only 90 fraud cases they are approximate: a larger fresh sample gave ROC AUC 0.93. They come from the same synthetic generator, so they are not an independent validation. The model catches most stolen-card, card-testing and account-takeover fraud, and almost none of the stealthy pattern, by design. See the [data contract](docs/DATA_CONTRACT.md#10-evaluation-and-fraud-scores) for the method and details.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/transactions/assess` | Score one transaction |
| POST | `/api/transactions/assess/batch` | Score up to 1000 transactions |
| GET | `/api/transactions/sample?fraud=true` | Generate an example transaction |
| GET | `/api/data/summary` | Rows, fraud rate and fraud rate by category for the configured training data |
| GET | `/api/model` | Model info and evaluation metrics |
| POST | `/api/model/train` | Train a model; optional body `{"samples":50000,"fraudRate":0.02,"maxEpochs":20,"seed":7,"overwrite":true}`. Returns `409` if a model already exists and `overwrite` is not `true`. `samples` and `fraudRate` apply to synthetic data only |

Monitoring uses Spring Boot Actuator: `/actuator/health` includes a `model` status, readiness depends on a model being loaded, and `/actuator/metrics/fraud.assessments` counts scoring by risk level.

```bash
curl -X POST localhost:8080/api/transactions/assess -H "Content-Type: application/json" -d '{
  "transactionId": "tx-1001",
  "amount": 2499.99, "customerAvgAmount": 45.0, "hourOfDay": 3,
  "merchantCategory": "ELECTRONICS",
  "distanceFromHomeKm": 850, "distanceFromLastTransactionKm": 790,
  "minutesSinceLastTransaction": 12, "transactionsLast24h": 11,
  "failedAttemptsLast24h": 3, "accountAgeDays": 400,
  "cardNotPresent": true, "usedChip": false, "usedPin": false,
  "foreignTransaction": true, "newDevice": true, "newMerchant": true
}'
```

```json
{
  "transactionId": "tx-1001",
  "fraudScore": 1.0,
  "fraud": true,
  "riskLevel": "CRITICAL",
  "threshold": 0.5,
  "riskIndicators": ["Amount is 55.6x the customer's average", "High velocity: 11 transactions in 24h", "..."],
  "modelTrainedAt": "2026-10-21T11:09:51Z"
}
```

`riskIndicators` are rule-based red flags meant to help analysts. They do not explain the network's score.
All fields are required. A request that leaves one out, or has a value out of range, gets a `400` response.
This strict input is a demonstration limitation, not a recommended production design: unknown values cannot be sent, and a `400` means the transaction was not scored, never that it was approved. See the [data contract](docs/DATA_CONTRACT.md).

Errors use the standard problem details format. A `400` lists each invalid field, and a batch request names the row:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "The request has invalid fields",
  "instance": "/api/transactions/assess/batch",
  "errors": [{"field": "[1].amount", "message": "must be greater than or equal to 0"}]
}
```

## Configuration (`application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `fraud.data.source` | `synthetic` | Training data: `synthetic` or `csv` |
| `fraud.data.csv-path` | `data/sample-transactions.csv` | The file to train on when the source is `csv` |
| `fraud.model.dir` | `./model` | Where the trained model (`fraud-model.bin`: weights, scaler, metrics) is stored |
| `fraud.model.train-if-missing` | `true` | Train on startup when no model exists |
| `fraud.model.threshold` | `0.5` | Fraud score at or above which a transaction is flagged |
| `fraud.training.*` | | Number of samples, fraud rate, test and validation fractions, oversampling target (`0` means none), hidden layers, epochs, learning rate, L2, seed |

Settings are checked at startup. An invalid value, such as a `test-fraction` of 2, stops the application with a message naming the setting.

To retrain after changing training settings, call `POST /api/model/train` with `{"overwrite":true}`, or move the model out of `./model` and restart.

## Train on your own data

Synthetic data is the default. To train on a CSV file instead:

**1. Create a CSV file** with a header row and these columns, in any order:

| Column | Required | Notes |
|---|---|---|
| The 16 transaction fields, `amount` to `newMerchant` | Yes | Same names and rules as the API request above |
| `fraud` | Yes | The label, `true` or `false` |
| `transactionId` | No | Kept with the row. Not a model input |
| `timestamp`, `customerId` | No | Accepted but not read, so they can never become features. `customerId` must be a pseudonym, not a card or account number |

[`data/sample-transactions.csv`](data/sample-transactions.csv) shows the format. Its 500 synthetic rows are enough to try this path, but far too few for meaningful metrics.

**2. Switch the source.** Use a separate model folder, because an existing model is loaded instead of training a new one:

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--fraud.data.source=csv --fraud.data.csv-path=data/sample-transactions.csv --fraud.model.dir=./model/csv"
```

To retrain later, call `POST /api/model/train` with `{"overwrite":true}`. CSV rows go through the same validation and feature engineering as API requests, so the model gets the same 27 features.

**3. Fix any reported problems.** Training stops if any row is invalid, because skipping rows could change the fraud rate. An existing model stays in place. The error names the file, line and column:

```text
Invalid CSV file /data/transactions.csv (2 problems):
  line 7, column amount: "abc" is not a number
  line 9, column hourOfDay: must be less than or equal to 23
```

**Adapting this to real data:**

- **Compute the history columns first.** `customerAvgAmount`, `transactionsLast24h`, `failedAttemptsLast24h`, `minutesSinceLastTransaction`, `distanceFromLastTransactionKm`, `newDevice` and `newMerchant` must be calculated from earlier events only. The [data contract](docs/DATA_CONTRACT.md) defines every field.
- **Use real values.** There is no way to mark a value as unknown, so don't use `0` or another placeholder for it.
- **Map your merchant codes** to the 10 categories in `MerchantCategory`.
- **Keep values simple.** Use dots for decimals and UTF-8. Quoted values and commas inside values are not supported, and the whole file is loaded into memory.
- **Evaluate for your data.** The split is random. Real chronological transactions may need a time-based or customer-aware evaluation, and the threshold should reflect your own costs.
- **To change the columns,** edit `Transaction`, `FeatureExtractor` and `CsvTransactionReader`, then retrain.

## Saved models

- **Missing:** the first start trains and saves a model when `fraud.model.train-if-missing` is `true`. Otherwise the application starts and scoring returns `503` until a model is trained.
- **Incompatible or unreadable:** startup stops with a message explaining what is wrong and what to do. The file is never changed, moved or retrained over.
- **Replacing a model:** only `POST /api/model/train` with `{"overwrite":true}` replaces one, and the previous model is **not kept**. There is no backup. If training or saving fails, the existing model stays in place and keeps serving.
- **Shutdown during a retrain (PoC limitation):** shutdown does not cancel a retrain. It either finishes and saves a complete model, which is not activated until the next start, or is lost if the JVM halts first. The saved model is never left partly written.

Replacement is atomic: if the filesystem cannot swap the file atomically, the save fails and the existing model is kept. Run one application instance per model folder.

## Known limitations

This is a PoC for local use, not a production service.

- **No security.** There is no authentication, so anyone who can reach the application can call `POST /api/model/train`. Don't expose it to a network.
- **Model files.** They use Java serialization, so only load files you trust.
- **Metrics describe synthetic data.** They are not evidence of real-world performance, and fraud scores are not calibrated probabilities.
- **Retraining is synchronous.** The request stays open until training finishes.
- **Monitoring is minimal.** Counters are kept in memory and reset on restart, and there is no drift detection.
- **Error details.** Validation errors list every invalid field. A value of the wrong JSON type, such as text for `hourOfDay`, reports only that one field, because parsing stops there.
- **CSV options.** `samples` and `fraudRate` are ignored without a warning when the source is `csv`, and `timestamp` is not validated because it is never read.
- **A test gap.** The `422` from `POST /api/model/train` for unusable training data is tested with a mocked service. A real unusable file is tested through the service and at startup, not through the full HTTP path.

## Customizing and extending

Run `grep -rn "TODO(customize)" src` to find the main places to adapt. The [data contract](docs/DATA_CONTRACT.md) explains how to change the features.

Kafka, Kafka Streams and Flink are not part of this project. `FraudDetectionService.assess(Transaction)` is where a stream consumer would call the model, and a stream processor is one way to compute the history fields that each transaction needs.
