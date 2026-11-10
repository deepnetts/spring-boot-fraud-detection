# Data Contract

Contract version 1 (MVP). Last reviewed 2026-10-30.

This document describes the data the fraud model expects, what the code does today, and what real-data
integrations must provide. It separates those three things on purpose: a definition here is not a claim
that the code already computes it.

## 1. Status and scope

| Label | Meaning |
|---|---|
| **Implemented** | The code does this today |
| **Contract requirement** | A rule for whoever supplies real data. The application cannot check it |
| **Not in the MVP** | Deliberately left out |

| Capability | Status |
|---|---|
| Scoring API that requires all 16 model inputs and checks their ranges | Implemented |
| 27 model features and z-score standardization | Implemented |
| Rejecting a saved model trained with different feature names | Implemented. Startup stops with a clear message and the file is left unchanged |
| Field definitions in [section 2.2](#22-definitions-contract-and-current-synthetic-behavior) | Contract requirement. Not computed or verified by the application |
| Synthetic generator rules | Implemented ([section 8](#8-synthetic-data)) |
| Validation split, PR AUC and recommended threshold | Implemented ([section 10](#10-evaluation-and-fraud-scores)) |
| CSV training data | Implemented ([section 7](#7-csv-training-format)) |
| `timestamp` and `customerId` metadata columns | Implemented: accepted, not read |
| Time-based or customer-based evaluation | Not in the MVP |
| Sending an unknown value | Not in the MVP ([section 3](#3-unknown-not-applicable-and-false)) |

The schema is fixed at 27 features. There is no dynamic feature registration. Developers customize the
model by changing Java code and retraining ([section 9](#9-customizing-features)).

## 2. Scoring input

### 2.1 Fields and implemented validation

`Transaction` has 17 fields: 16 model inputs and the optional `transactionId`. The application checks
presence and ranges only. It cannot tell whether a value was computed correctly.

| Field | Type | Implemented validation | Source group |
|---|---|---|---|
| `transactionId` | String | Optional. Not a model input | Direct |
| `amount` | Double | Required, ≥ 0 | Direct |
| `merchantCategory` | Enum | Required, one of 10 values | Direct |
| `cardNotPresent` | Boolean | Required | Direct |
| `usedChip` | Boolean | Required | Direct |
| `usedPin` | Boolean | Required | Direct |
| `hourOfDay` | Integer | Required, 0–23 | Lookup |
| `foreignTransaction` | Boolean | Required | Lookup |
| `accountAgeDays` | Integer | Required, ≥ 0 | Lookup |
| `distanceFromHomeKm` | Double | Required, ≥ 0 | Lookup |
| `customerAvgAmount` | Double | Required, > 0 | History |
| `transactionsLast24h` | Integer | Required, ≥ 0 | History |
| `failedAttemptsLast24h` | Integer | Required, ≥ 0 | History |
| `minutesSinceLastTransaction` | Double | Required, ≥ 0 | History |
| `distanceFromLastTransactionKm` | Double | Required, ≥ 0 | History |
| `newDevice` | Boolean | Required | History |
| `newMerchant` | Boolean | Required | History |

**Source groups** describe where a real integration gets each value:

- **Direct (5 model inputs):** available from the payment itself. `merchantCategory` needs a mapping from
  real merchant category codes to the 10 values.
- **Lookup (4):** the payment plus static reference data, such as the cardholder's time zone, the card's
  issuing country, the account opening date, and a home location with a transaction location.
- **History (7):** requires precomputed customer history. This application stores no history, so the
  caller must compute these values before scoring, typically in a low-latency profile store.

### 2.2 Definitions: contract and current synthetic behavior

The middle column is a **contract requirement for real data**. The right column is what
`SyntheticTransactionGenerator` **actually does today**. The generator has no customers, merchants,
locations, time zones or timeline. It samples each value, so none of the windows or exclusions in the
middle column exist in the synthetic data.

| Field | Contract requirement for real data | Synthetic generator today |
|---|---|---|
| `amount` | Amount in the deployment's single currency | Sampled as customer average × category factor × random factor. No currency concept |
| `customerAvgAmount` | Same currency. Mean over the prior 90 days, excluding this transaction and known fraud | Sampled directly from a log-normal distribution. No history and no 90-day window |
| `hourOfDay` | Hour in the cardholder's home time zone | Sampled directly. Legitimate and stealthy traffic center on 14:00, other fraud is weighted toward night. No time zones |
| `merchantCategory` | Real merchant code mapped to one of the 10 values | Sampled from weighted lists |
| `distanceFromHomeKm` | Distance between the cardholder's home and the transaction location, from a named location source | Sampled directly. No locations. Online transactions get values as if a location were known |
| `distanceFromLastTransactionKm` | Distance between the previous transaction and this one | Sampled directly. No previous transaction exists |
| `minutesSinceLastTransaction` | Minutes since the previous transaction on the card | Sampled so it agrees with `transactionsLast24h`: under 1,440 exactly when the count is at least 1 |
| `transactionsLast24h` | Transactions on the card in the 24 hours before this one, excluding this one | Sampled from a Poisson distribution, not counted |
| `failedAttemptsLast24h` | Declined or failed attempts in the 24 hours before this one, excluding this one | Sampled from a Poisson distribution, not counted |
| `accountAgeDays` | Days since the account was opened, not since the card was issued | Sampled directly, 1–3,650 days, so new accounts occur in legitimate traffic. No account or card distinction |
| `cardNotPresent` | `true` for online, phone or mail order | Sampled, with a higher chance for digital goods |
| `usedChip` | EMV chip was read, including contactless | Sampled, only for card-present payments. No contactless concept |
| `usedPin` | PIN was verified | Sampled, and only when `usedChip` is `true` |
| `foreignTransaction` | Merchant country differs from the card's issuing country | Sampled. No countries |
| `newDevice` | Device or browser fingerprint never seen for this customer | Sampled, only for card-not-present payments. No devices |
| `newMerchant` | Customer never transacted with this merchant before | Sampled. No customers or merchants |

`amount` and `customerAvgAmount` assume one currency per deployment. There is no currency field.

## 3. Unknown, not applicable and false

> The strict 16-field input is a **demonstration limitation, not a recommended production design**.

Real payment data has three different states that this schema cannot fully represent:

- **False:** the condition was checked and did not happen.
- **Not applicable:** the condition cannot exist for this transaction.
- **Unknown:** the condition could apply, but the value is not available.

| Field | Not applicable when | Unknown when | What the schema can do today |
|---|---|---|---|
| `usedChip`, `usedPin` | Card-not-present payment | Terminal data is missing | Not applicable is sent as `false`, the same as a real "no". Unknown cannot be sent |
| `newDevice` | Card-present payment | No device fingerprinting | Not applicable is sent as `false`, the same as a real "no". Unknown cannot be sent |
| `newMerchant`, `foreignTransaction` | Never | Merchant history or country data is missing | Unknown cannot be sent |
| `distanceFromHomeKm` | Never | No transaction location | Unknown cannot be sent |
| `distanceFromLastTransactionKm`, `minutesSinceLastTransaction` | First transaction on the card | History is unavailable | Neither state can be sent |
| `customerAvgAmount` | New customer with no transactions | History is unavailable | Neither state can be sent. The value must be above 0 |
| `transactionsLast24h`, `failedAttemptsLast24h` | Never | History is unavailable | Unknown cannot be sent. `0` is a real count |
| `accountAgeDays`, `hourOfDay` | Never | Reference data is missing | Unknown cannot be sent |

**Rules for the MVP:**

- The application never fills in a missing value.
- Do not send `0`, `false` or another placeholder to mean "unknown". `distanceFromHomeKm: 0` means the
  transaction happened at home. The application cannot detect a placeholder, and the model would treat it
  as real data.
- The model sees the same value for "no PIN was used" and "PIN does not apply". That is a known loss of
  information.

**Production alternatives (not implemented):** nullable fields with matching "is known" features, or
separate models per channel. Both change the feature schema.

## 4. When a transaction cannot be scored

**Implemented:** a request with a missing or out-of-range field gets a `400` response that lists each failed
field, and no score.

**Contract requirement:** a `400` means **not scored**. It never means approved. The caller needs its own
fallback, such as rules or manual review.

Transactions that cannot be scored under this contract include:

- A new customer's first transaction, which has no average, previous transaction or time gap.
- Any transaction with no location source, because both distance fields are unknown. This can affect a
  large share of online payments.
- Any transaction where the caller's history store is unavailable.

If a value will never be available, either supply a real value from a named source (for example IP
geolocation or a shipping address) or remove the feature and retrain ([section 9](#9-customizing-features)).

## 5. The 27 model features

**Implemented** in `FeatureExtractor`. Every feature is a `float`. After the calculation below, all of
them, including flags, are standardized with `(x - mean) / std` using statistics from the training split.
`log1p(x)` means `ln(1 + x)`, with negative values treated as 0.

| # | Feature name | Source field | Calculation |
|---|---|---|---|
| 0 | `log_amount` | `amount` | `log1p(amount)` |
| 1 | `log_amount_to_customer_avg` | `amount`, `customerAvgAmount` | `log1p(amount / max(customerAvgAmount, 1.0))` |
| 2 | `hour_sin` | `hourOfDay` | `sin(2π × hour / 24)` |
| 3 | `hour_cos` | `hourOfDay` | `cos(2π × hour / 24)` |
| 4 | `is_night` | `hourOfDay` | 1 if `hour < 6`, else 0 |
| 5 | `log_distance_from_home` | `distanceFromHomeKm` | `log1p(km)` |
| 6 | `log_distance_from_last_txn` | `distanceFromLastTransactionKm` | `log1p(km)` |
| 7 | `log_minutes_since_last_txn` | `minutesSinceLastTransaction` | `log1p(minutes)` |
| 8 | `txn_count_24h` | `transactionsLast24h` | Raw count |
| 9 | `failed_attempts_24h` | `failedAttemptsLast24h` | Raw count |
| 10 | `log_account_age_days` | `accountAgeDays` | `log1p(days)` |
| 11 | `card_not_present` | `cardNotPresent` | 1 or 0 |
| 12 | `used_chip` | `usedChip` | 1 or 0 |
| 13 | `used_pin` | `usedPin` | 1 or 0 |
| 14 | `foreign` | `foreignTransaction` | 1 or 0 |
| 15 | `new_device` | `newDevice` | 1 or 0 |
| 16 | `new_merchant` | `newMerchant` | 1 or 0 |
| 17 | `category_grocery` | `merchantCategory` | 1 if `GROCERY`, else 0 |
| 18 | `category_restaurant` | `merchantCategory` | 1 if `RESTAURANT`, else 0 |
| 19 | `category_fuel` | `merchantCategory` | 1 if `FUEL`, else 0 |
| 20 | `category_retail` | `merchantCategory` | 1 if `RETAIL`, else 0 |
| 21 | `category_electronics` | `merchantCategory` | 1 if `ELECTRONICS`, else 0 |
| 22 | `category_travel` | `merchantCategory` | 1 if `TRAVEL`, else 0 |
| 23 | `category_digital_goods` | `merchantCategory` | 1 if `DIGITAL_GOODS`, else 0 |
| 24 | `category_entertainment` | `merchantCategory` | 1 if `ENTERTAINMENT`, else 0 |
| 25 | `category_gambling` | `merchantCategory` | 1 if `GAMBLING`, else 0 |
| 26 | `category_money_transfer` | `merchantCategory` | 1 if `MONEY_TRANSFER`, else 0 |

Feature names stay as they are for the MVP. Note that `foreign` comes from `foreignTransaction`.

## 6. Point-in-time rules

**Contract requirement** for real data, in training files and at scoring time. The application cannot
check these. They do not apply to today's synthetic data, which has no timeline.

- History fields use only events before the transaction. A daily batch job that sees the whole day
  includes the future.
- Training and scoring must compute each field the same way.
- `customerAvgAmount` must not include the current transaction or known fraud.
- Be careful with `failedAttemptsLast24h`. Once a card is blocked for fraud, later attempts fail, so the
  field can echo the label. Declines also depend on the fraud rules running at the time.
- Fraud labels arrive late, often through chargebacks. Recent rows labelled legitimate may be undiscovered
  fraud.

## 7. CSV training format

**Implemented** in `CsvTransactionReader`. Set `fraud.data.source` to `csv` and `fraud.data.csv-path` to the file.
Synthetic data stays the default. The README shows how to switch.

| Column | Required | Purpose |
|---|---|---|
| The 16 model inputs | Yes | Same names and rules as the API |
| `fraud` | Yes | Label, `true` or `false` |
| `transactionId` | No | Identifier. Not a model input |
| `timestamp` | No | Metadata. ISO-8601 with a time zone offset, for example `2026-10-30T14:05:00+01:00` |
| `customerId` | No | Metadata. A pseudonymized customer reference |

**File rules:** a header row is required, column order is free, decimals use a dot, and encoding is UTF-8.
Quoted values and commas inside values are not supported. The whole file is loaded into memory.

**Validation rules:**

- Rows follow the same validation as the API, and unknown `merchantCategory` values are rejected.
- A missing or blank required value fails the load. Problems are reported by file, line and column, up to 20
  at a time. Rows are not silently skipped, because dropping them could change the fraud rate.
- The data must leave both fraud and legitimate rows in every split, or training stops with a clear message.
- If training fails, no model is saved and any existing model stays in place.
- Columns not listed above are ignored with a logged warning.

**Metadata rules:**

- `timestamp` and `customerId` are **never model features** and must never reach `FeatureExtractor`.
- `customerId` must be a stable pseudonym, not a card or account number. Hashing a card number without a
  secret key is not enough, because card numbers can be guessed by brute force.
- Card numbers and other personal data must not appear in the file.

**Limitation:** the reader accepts these columns but does not read them, so they are not used to split data.
Time-based and customer-based evaluation can build on them later. Until then, metrics on real CSV data may look better
than real performance, because transactions from the same customer can appear in both training and test
data.

## 8. Synthetic data

**Implemented:** `SyntheticTransactionGenerator` is the default demonstration source. It produces
imbalanced data (3% fraud by default) from legitimate traffic and four fraud patterns: stolen card, card
testing, account takeover and stealthy.

**Generator rules (Implemented, enforced by `SyntheticTransactionGeneratorTest`):**

1. `usedChip` requires card-present, `usedPin` requires `usedChip`, and `newDevice` requires
   card-not-present.
2. `minutesSinceLastTransaction` is under 1,440 exactly when `transactionsLast24h` is at least 1.
3. Legitimate accounts can be new, so an account under 30 days old doesn't identify fraud.
4. Account takeover uses established accounts only.
5. Stealthy fraud keeps a normal hour distribution and changes exactly one or two weak signals that can
   change on that row.
6. Card testing isn't perfectly clean: velocity, failed attempts and amounts can all look ordinary.

**Remaining simplifications:** rows are independent, with no customers or timeline, and there is no label
noise, change over time, or label delay. The generator was corrected to remove contradictions and giveaways,
not to reach a particular score.

The data stays imbalanced, and there is no balanced-dataset example. Results from this generator show that
the pipeline works. They are not evidence of real-world performance.

## 9. Customizing features

To change the features, edit `FeatureExtractor` and retrain:

1. Change `FEATURE_NAMES` and `toTensor()` together. They must stay in the same order, because the vector
   is built by position and nothing checks the alignment.
2. If you add or remove a `Transaction` field, update `Transaction`, `SyntheticTransactionGenerator`,
   `CsvTransactionReader` and
   any tests that build a `Transaction`.
3. Update the feature count in `FraudDetectionApplicationTest`, which expects 27.
4. Retrain.

Removing a feature you cannot supply, such as the distance features, is a valid choice.

**Behavior with an existing model (Implemented):** a saved model records its feature names, and a model
trained with different names is rejected. Startup stops with a message naming the features that changed,
and the file is left unchanged. The application never retrains over it, renames it or deletes it. To
continue, move `fraud-model.bin` out of `fraud.model.dir`, or point `fraud.model.dir` at an empty folder.
With `fraud.model.train-if-missing: true` the next start trains a new model.

## 10. Evaluation and fraud scores

**Implemented** in `FraudModelTrainer` and `ModelEvaluator`.

### Data splits

The data is shuffled with a fixed seed, then split by class into 60% training, 15% validation and 25% test,
so each part has the same fraud rate.

| Split | Used for |
|---|---|
| Training | Fitting the network and the scaler |
| Validation | Choosing the recommended threshold |
| Test | Final metrics only |

A seeded random split suits this synthetic data, where rows are independent. **Real chronological
transactions may need a time-based or customer-aware evaluation strategy**, because a random split lets the
model train on later transactions and see the same customers in training and test.

### Fraud scores are not probabilities

`fraudScore` is a value in [0, 1] for ranking transactions and comparing them with a threshold. It is not a
calibrated probability: a score of 0.8 does not mean an 80% chance of fraud. The project does no probability
calibration.

### Thresholds

There are two different thresholds, and neither is universally optimal:

| Threshold | Value | What it is |
|---|---|---|
| Decision threshold, `fraud.model.threshold` | 0.5 by default | Decides what gets flagged. A simple default, not a tuned value |
| Recommended threshold, `recommendedThreshold` | 0.73 for the default model | The best F1 on validation data. Reported only, never applied automatically |

The recommended threshold varied noticeably between runs, and F1 treats missed fraud and false alarms as equally
costly, which is rarely true. Use it as a starting point. A real threshold depends on business costs: what missed
fraud costs against what false alarms and reviews cost.

### Why a 3% fraud rate

3% is a demonstration choice, not a realistic rate: real card fraud is usually well under 1%. It gives enough
fraud examples to train and evaluate in about a minute, and it is still imbalanced enough that always answering
"legitimate" would be 97% accurate.

**Precision and PR AUC only hold at the fraud rate they were measured at.** With the recall and false-positive
rate below, precision of 0.91 at a 3% fraud rate would be about 0.40 at a 0.2% fraud rate, with the same model.

### Why no oversampling

Oversampling duplicates fraud rows in the training split only. Training at the natural fraud rate was chosen by the highest mean PR AUC
on validation data, with models trained on development seeds and compared on a separately generated validation
set of 6,000 rows. The test split below played no part.

| Fraud share in training | Mean ROC AUC | Mean PR AUC | Precision at 0.5 | Recall at 0.5 |
|---|---|---|---|---|
| 0 (natural 3%) | 0.934 | 0.828 | 0.91–0.94 | 0.72–0.76 |
| 0.10 | 0.926 | 0.812 | 0.74–0.87 | 0.76–0.78 |
| 0.30 | 0.931 | 0.808 | 0.69–0.77 | 0.76–0.82 |

Three seeds per setting, 12,000 samples. No oversampling had the best PR AUC on every seed. Oversampling gave
slightly higher recall at a fixed threshold of 0.5, but lower precision. The setting was later removed, so the
example trains only on imbalanced data. On real data the comparison may come out differently.

### Results

Default settings, seed 42, threshold 0.5, measured once on the final generator after every setting was fixed:

| Test rows | Fraud cases | ROC AUC | PR AUC | Precision | Recall | False-positive rate |
|---|---|---|---|---|---|---|
| 3,000 | 90 | 0.939 | 0.796 | 0.91 | 0.68 | 0.21% |

That is 61 fraud cases caught, 29 missed and 6 false alarms. The recommended threshold from validation data was
0.73, which gave precision 0.98 and recall 0.67 on the test split.

Recall by fraud pattern, measured on separately generated data:

| Pattern | Flagged at 0.5 |
|---|---|
| Card testing | 95% |
| Account takeover | 90% |
| Stolen card | 82% |
| Stealthy | 2% |
| Legitimate traffic (false alarms) | 0.13% |

How to read these numbers:

- **The test set is small.** 90 fraud cases make these numbers approximate. On a larger fresh sample, ROC AUC was
  0.93.
- **This is not an independent validation.** The test data comes from the same generator the project was built
  around. An earlier generator version, with a bug that left some stealthy rows unchanged, was measured once with
  the same seed (ROC AUC 0.949, recall 0.77). Those numbers are superseded and not comparable. The bug was fixed
  because it broke rule 5, not to change a score.
- **The fraud mix is an assumption.** Stealthy fraud is set to 20% of fraud, which effectively caps overall recall
  near 80%. Quote recall by pattern, not only the overall number.
- **A simple baseline comes close.** Counting the rule-based red flags reached ROC AUC 0.92 on the same fresh
  sample, against 0.93 for the network. On this data the network's advantage is modest.

### Stealthy fraud

Stealthy rows are legitimate transactions with exactly one or two weak signals changed: a higher amount, a riskier
category, a new device, a new merchant, or slightly more velocity. Being caught only about 2% of the time is the
expected result of overlapping data, not a fault in training, features or evaluation.

- **The signals are weak.** The strongest single feature, `new_merchant`, reaches AUC 0.65 against legitimate rows,
  and every signal also occurs in normal traffic.
- **The model does react.** Adding one signal to a legitimate row raises its mean score about two to four times,
  but rarely past the threshold. Its ROC AUC for stealthy against legitimate rows is 0.70.
- **More data does not fix it.** Four times the data gave ROC AUC 0.68 for the same comparison.
- **A dedicated model does not fix it.** A network trained only on stealthy and legitimate rows, on 50/50 data as a
  diagnostic, reached ROC AUC 0.82. Its most confident flags would still be only about 15% precise at real traffic
  proportions, because stealthy fraud is just 0.6% of transactions.

Catching this fraud takes information these features do not contain, such as a customer's full history, links
between merchants and devices, or confirmed outcomes.

### Threshold trade-off

Lowering the threshold finds more fraud at a rising cost. At the 3% mix, per 10,000 transactions, on fresh data:

| Threshold | Stealthy recall | Overall recall | False positives | Precision | Alerts | Fraud found |
|---|---|---|---|---|---|---|
| 0.5 | 1.9% | 70.1% | 0.11% | 0.95 | 221 | 210 |
| 0.2 | 4.3% | 74.0% | 0.40% | 0.85 | 260 | 222 |
| 0.1 | 6.9% | 76.1% | 0.85% | 0.74 | 311 | 228 |
| 0.02 | 17.9% | 80.7% | 3.55% | 0.41 | 587 | 242 |
| 0.005 | 33.2% | 84.9% | 10.34% | 0.20 | 1,258 | 255 |
| 0.002 | 46.2% | 88.0% | 17.99% | 0.13 | 2,009 | 264 |

Going from 0.5 to 0.02 costs about 10 false alarms for each extra fraud found, and going from 0.02 to 0.005 costs
about 50. These costs would be much higher at real fraud rates. No threshold makes stealthy fraud practical to
catch. Choosing one is a business decision, so the default stays at 0.5.
