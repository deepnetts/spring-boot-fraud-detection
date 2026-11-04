# Fraud Detection with Deep Netts

This repository contains two Spring Boot examples that use a [Deep Netts](https://www.deepnetts.com) neural network to
detect card fraud. Both train on synthetic data, run locally and need no external services.

| Example                           | Purpose                                                                      |
|-----------------------------------|------------------------------------------------------------------------------|
| [`minimal/`](minimal/README.md)   | The smallest complete integration of a Deep Netts fraud model in Spring Boot |
| [`extended/`](extended/README.md) | A fraud scoring service that covers the complete machine learning lifecycle  |

Start with the minimal example. The extended example builds on the same ideas.

> [!IMPORTANT]
> Both examples are proofs of concept for local use, not production services. All results come from synthetic data:
> they show that the workflow works, not how well it would perform on real transactions.

## Why Fraud Detection Matters

Card fraud costs a business in several ways: the lost goods or money, chargeback fees, the time analysts spend
reviewing suspicious payments, and customers who leave after a blocked card or a fraudulent charge. Fixed rules, such
as "block payments above €1,000 abroad", are easy to explain but miss new patterns and stop many good customers.

A predictive model learns from past transactions that are labelled as fraud or legitimate. For each new transaction it
returns a **fraud score**. A business can use that score to:

- send the most suspicious payments to an analyst first, instead of reviewing at random;
- ask for extra authentication when the score is high, and let ordinary payments through without friction;
- compare the model's alerts with confirmed outcomes and see when it needs retraining.

The model supports these decisions; it does not replace them. Where to draw the line between "approve", "review" and
"decline" is a business decision about costs.

## Judging a Fraud Model

Fraud is rare. In the example data 3 % of transactions are fraud, and in real card data it is usually well under 1 %.
That changes how a model has to be judged:

- **Accuracy is misleading.** A model that never flags anything is 97 % accurate on the example data and catches no
  fraud at all.
- **Precision** answers: of the transactions the model flags, how many are really fraud? Low precision means many
  false alarms, annoyed customers and wasted review time.
- **Recall** answers: of all fraud, how much does the model catch? Low recall means losses.
- **The threshold** turns a score into a decision. Lowering it catches more fraud and raises more false alarms.

The extended example measured this trade-off on synthetic data, per 10,000 transactions at a 3 % fraud rate:

| Threshold | Alerts | Fraud found | Precision |
|-----------|-------:|------------:|----------:|
| 0.5       |    221 |         210 |      0.95 |
| 0.1       |    311 |         228 |      0.74 |
| 0.02      |    587 |         242 |      0.41 |

Going from 0.5 to 0.02 finds 32 more fraud cases at the cost of 366 more alerts. Whether that is worth it depends on
what a missed fraud and a review cost. These are simulation results from the extended example's
[data contract](extended/docs/DATA_CONTRACT.md#threshold-trade-off), not a prediction of real performance or savings.

## Comparison

|                    | Minimal                                         | Extended                                                                 |
|--------------------|-------------------------------------------------|--------------------------------------------------------------------------|
| Training data      | Two CSV files: 7,500 + 2,500 transactions       | Synthetic generator (12,000 transactions) or your own CSV file           |
| Model inputs       | 9 fields                                        | 27 features derived from 16 fields                                      |
| Preprocessing      | Deep Netts `Standardizer`                       | Log transforms, cyclic hour, one-hot categories, z-score scaling        |
| Network            | 9 → 16 → 2 (softmax)                            | 27 → 32 → 16 → 1 (sigmoid)                                               |
| Evaluation         | Confusion matrix, precision, recall, accuracy   | Also ROC AUC, PR AUC, F1 and a recommended threshold from validation data |
| Saving and loading | Deep Netts `FileIO`                             | Own file format with atomic replacement and compatibility checks        |
| REST endpoints     | 1                                               | 8, plus Spring Boot Actuator                                             |
| Data analysis      | The CSV files                                   | Summary endpoint that compares fraud with legitimate transactions       |
| Monitoring         | —                                               | Health, readiness, a scoring counter and PoC performance monitoring     |
| Retraining         | Delete the model and restart                    | On request, or scheduled with a quality check                           |
| Tests              | 9                                               | 142                                                                      |
| First start        | About 9 s, of which training about 4 s          | About 65 s, of which training about 60 s                                 |

First-start times were measured once on the reference machine (Windows 11, JDK 25.0.4), after Maven had compiled the
project. Later starts load the saved model in about 4 seconds.

## Prerequisites

- JDK 25 (required by Deep Netts 4.x)
- Maven 3.9 or later

## Quick Start

Each example is an independent Maven project. Run it from its own directory:

```bash
cd minimal
mvn spring-boot:run
```

```bash
cd extended
mvn spring-boot:run
```

Both applications listen on port 8080, so run one at a time. They are REST APIs without a graphical interface. Each
example contains a `requests.http` file with sample requests that you can run from IntelliJ IDEA or VS Code, or send
with `curl`, Postman or another HTTP client.

## How the Workflow Works

Both examples follow the same steps. The minimal example puts them in one class, `FraudModel`, with one method per step.

1. **Data:** labelled transactions, each marked as fraud or legitimate.
2. **Preprocessing:** turn every transaction into numbers on a similar scale.
3. **Training:** a Deep Netts feed-forward network learns to separate fraud from legitimate transactions.
4. **Evaluation:** measure precision and recall on transactions the network has never seen.
5. **Saving and loading:** save the trained network, so later starts skip training.
6. **Prediction:** a REST endpoint scores new transactions.

The extended example adds data analysis, a recommended threshold, performance monitoring from confirmed outcomes, and
scheduled retraining that only replaces the model when the new one is at least as good.

## Repository Layout

```text
.
├── minimal/      Minimal example: 9 inputs, one endpoint, CSV data
├── extended/     Extended example: 27 features, monitoring, retraining
├── SKILL.md      Rules and procedures for customizing the examples, for developers and AI coding assistants
├── prompts/      Ready-to-use prompts for common customization tasks
└── LICENSE       Apache License 2.0
```

Trained models and build output are not part of the repository. Each example trains its model on the first start.

## Customizing the Examples

Each README explains how to use your own data and change the inputs. [`SKILL.md`](SKILL.md) summarizes the rules that
keep the machine learning workflow correct, for example that training and prediction must use the same inputs in the
same order, and that test data must never be used for training.

The examples are meant as a starting template. If you work with an AI coding assistant, [`prompts/`](prompts) contains
prompts for common tasks. Each one asks the assistant to explain its plan before it changes code and to explain the
changes afterwards, so that you can review them rather than accept them blindly.

- [Explain the example](prompts/explain-the-example.md)
- [Replace the transaction fields](prompts/replace-transaction-fields.md) with the fields of your own system
- [Adapt preprocessing](prompts/adapt-preprocessing.md) to a different dataset
- [Modify the prediction API](prompts/modify-the-prediction-api.md)
- [Integrate the example into an existing Spring Boot application](prompts/integrate-into-an-existing-app.md)
- [Review a change](prompts/review-a-change.md)

## Deep Netts Resources

- [Deep Netts](https://www.deepnetts.com): product overview
- [Getting Started](https://www.deepnetts.com/cookbook/getting-started/)
- [Developer Guide](https://www.deepnetts.com/cookbook/developer-guide/): loading and preparing data, creating,
  training and evaluating a model, saving and loading, making predictions
- [Classification example](https://www.deepnetts.com/cookbook/examples/classification/)
- [Machine Learning Course](https://www.deepnetts.com/cookbook/course/) for developers new to machine learning
- [API documentation](https://www.deepnetts.com/apidocs/index.html)
- [Use cases](https://www.deepnetts.com/use-cases/), including real-time fraud detection
- [Deployment and pricing](https://www.deepnetts.com/pricing/) and [contact](https://www.deepnetts.com/contact/)
- [Deep Netts on GitHub](https://github.com/deepnetts) and
  [`deepnetts-core` on Maven Central](https://central.sonatype.com/artifact/com.deepnetts/deepnetts-core)

## Limitations

Both examples use synthetic data, so every reported result is a simulation result. Fraud scores are not calibrated
probabilities. The applications have no authentication and are meant to run locally. Each README lists the limitations
of its example.

## License

This repository is licensed under the [Apache License 2.0](LICENSE).
