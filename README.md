# Fraud Detection (Spring Boot + Deep Netts)

A REST service that scores card transactions for fraud with a feed-forward neural network
trained using [Deep Netts](https://www.deepnetts.com) (`com.deepnetts:deepnetts-core`).

- Java 25, Spring Boot 4.0, Deep Netts 4.0.4
- On first start it trains a model on synthetic transactions (about 1–2 minutes), saves it to `./model`,
  and loads it on later starts.

## Run

```bash
mvn spring-boot:run
# or
mvn package && java -jar target/fraud-detection-0.1.0-SNAPSHOT.jar
```

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
- Class imbalance: in the training split, fraud examples are oversampled up to 30%. The test split keeps the
  natural fraud rate (3%), so the reported precision and recall reflect production conditions.
- Evaluation: ROC AUC, precision, recall, F1, false-positive rate, confusion matrix, and the
  threshold that gives the best F1
- Synthetic data: `SyntheticTransactionGenerator` mixes legitimate traffic with four fraud patterns:
  stolen card, card testing, account takeover, and a "stealthy" pattern with only weak signals.
  To train on real data, replace it with a loader that returns `List<LabeledTransaction>` and call
  `FraudModelTrainer.train(data, cfg)`.

**Provisional results.** With the default settings the held-out results are ROC AUC ≈ 0.98 and precision ≈ recall ≈ 0.84 at threshold 0.5.
Treat these as a demonstration only: the current synthetic data contains patterns that make fraud too easy to detect, and they say nothing about real-world performance. See the [data contract](docs/DATA_CONTRACT.md).

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/transactions/assess` | Score one transaction |
| POST | `/api/transactions/assess/batch` | Score up to 1000 transactions |
| GET | `/api/transactions/sample?fraud=true` | Generate an example transaction |
| GET | `/api/model` | Model info and evaluation metrics |
| POST | `/api/model/train` | Train a model; optional body `{"samples":50000,"fraudRate":0.02,"maxEpochs":20,"seed":7,"overwrite":true}`. Returns `409` if a model already exists and `overwrite` is not `true` |

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
  "fraudProbability": 0.9999,
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

## Configuration (`application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `fraud.model.dir` | `./model` | Where the trained model (`fraud-model.bin`: weights, scaler, metrics) is stored |
| `fraud.model.train-if-missing` | `true` | Train on startup when no model exists |
| `fraud.model.threshold` | `0.5` | Probability at or above which a transaction is flagged |
| `fraud.training.*` | | Number of samples, fraud rate, test fraction, oversampling target, hidden layers, epochs, learning rate, L2, seed |

To retrain after changing training settings, call `POST /api/model/train` with `{"overwrite":true}`, or move the model out of `./model` and restart.

## Saved models

- **Missing:** the first start trains and saves a model when `fraud.model.train-if-missing` is `true`. Otherwise the application starts and scoring returns `503` until a model is trained.
- **Incompatible or unreadable:** startup stops with a message explaining what is wrong and what to do. The file is never changed, moved or retrained over.
- **Replacing a model:** only `POST /api/model/train` with `{"overwrite":true}` replaces one, and the previous model is **not kept**. There is no backup. If training or saving fails, the existing model stays in place and keeps serving.

Replacement is atomic: if the filesystem cannot swap the file atomically, the save fails and the existing model is kept. Run one application instance per model folder.
