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
| **Planned** | Agreed for the MVP, not built yet |
| **Not in the MVP** | Deliberately left out |

| Capability | Status |
|---|---|
| Scoring API that requires all 16 model inputs and checks their ranges | Implemented |
| 27 model features and z-score standardization | Implemented |
| Rejecting a saved model trained with different feature names | Implemented. Startup stops with a clear message and the file is left unchanged |
| Field definitions in [section 2.2](#22-definitions-contract-and-current-synthetic-behavior) | Contract requirement. Not computed or verified by the application |
| Synthetic generator that follows the contract | Planned ([section 8](#8-synthetic-data)) |
| CSV training data | Planned ([section 7](#7-csv-training-format-planned)) |
| `timestamp` and `customerId` metadata columns | Planned |
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
| `hourOfDay` | Hour in the cardholder's home time zone | Sampled directly. Legitimate traffic centers on 14:00, fraud is weighted toward night. No time zones |
| `merchantCategory` | Real merchant code mapped to one of the 10 values | Sampled from weighted lists |
| `distanceFromHomeKm` | Distance between the cardholder's home and the transaction location, from a named location source | Sampled directly. No locations. Online transactions get values as if a location were known |
| `distanceFromLastTransactionKm` | Distance between the previous transaction and this one | Sampled directly. No previous transaction exists |
| `minutesSinceLastTransaction` | Minutes since the previous transaction on the card | Sampled independently of `transactionsLast24h`, so the two can contradict each other |
| `transactionsLast24h` | Transactions on the card in the 24 hours before this one, excluding this one | Sampled from a Poisson distribution, not counted |
| `failedAttemptsLast24h` | Declined or failed attempts in the 24 hours before this one, excluding this one | Sampled from a Poisson distribution, not counted |
| `accountAgeDays` | Days since the account was opened, not since the card was issued | Sampled directly. Legitimate values are 30–3,629. No account or card distinction |
| `cardNotPresent` | `true` for online, phone or mail order | Sampled, with a higher chance for digital goods |
| `usedChip` | EMV chip was read, including contactless | Sampled. No contactless concept |
| `usedPin` | PIN was verified | Sampled, and only when `usedChip` is `true` |
| `foreignTransaction` | Merchant country differs from the card's issuing country | Sampled. No countries |
| `newDevice` | Device or browser fingerprint never seen for this customer | Sampled. No devices |
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

**Implemented:** a request with a missing or out-of-range field gets a `400` response and no score.

**Contract requirement:** a `400` means **not scored**. It never means approved. The caller needs its own
fallback, such as rules or manual review.

Transactions that cannot be scored under this contract include:

- A new customer's first transaction, which has no average, previous transaction or time gap.
- Any transaction with no location source, because both distance fields are unknown. This can affect a
  large share of online payments.
- Any transaction where the caller's history store is unavailable.

If a value will never be available, either supply a real value from a named source (for example IP
geolocation or a shipping address) or remove the feature and retrain ([section 9](#9-customizing-features)).

Error responses that list exactly which fields failed are planned.

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

## 7. CSV training format (Planned)

CSV loading does not exist yet. This section defines the agreed format.

| Column | Required | Purpose |
|---|---|---|
| The 16 model inputs | Yes | Same names and rules as the API |
| `fraud` | Yes | Label, `true` or `false` |
| `transactionId` | No | Identifier. Not a model input |
| `timestamp` | No | Metadata. ISO-8601 with a time zone offset, for example `2026-10-30T14:05:00+01:00` |
| `customerId` | No | Metadata. A pseudonymized customer reference |

**File rules:** a header row is required, column order is free, decimals use a dot, and encoding is UTF-8.

**Validation rules:**

- Rows follow the same validation as the API, and unknown `merchantCategory` values are rejected.
- A missing or blank required value fails the load, with every problem reported by line number. Rows are
  not silently skipped, because dropping them could change the fraud rate.
- Columns not listed above are ignored with a logged warning.

**Metadata rules:**

- `timestamp` and `customerId` are **never model features** and must never reach `FeatureExtractor`.
- `customerId` must be a stable pseudonym, not a card or account number. Hashing a card number without a
  secret key is not enough, because card numbers can be guessed by brute force.
- Card numbers and other personal data must not appear in the file.

**Limitation:** the MVP may read these columns but will not use them to split data. Time-based and
customer-based evaluation can build on them later. Until then, metrics on real CSV data may look better
than real performance, because transactions from the same customer can appear in both training and test
data.

## 8. Synthetic data

**Implemented:** `SyntheticTransactionGenerator` is the default demonstration source. It produces
imbalanced data (3% fraud by default) from legitimate traffic and four fraud patterns: stolen card, card
testing, account takeover and stealthy.

**Known problems in the current generator:**

- Some field combinations appear only in fraud rows: an account under 30 days old, a chip read on a
  card-not-present transaction, and a new device on a card-present transaction.
- Fields are sampled independently, so some legitimate rows contradict themselves. For example, the last
  transaction can be over 24 hours ago while `transactionsLast24h` is 2 or more.
- Stealthy fraud replaces the normal hour distribution, so it is far more night-weighted than legitimate
  traffic.
- Card testing rows always have at least 8 transactions and 2 failed attempts.
- Account takeover includes newly opened accounts, although a takeover targets an established account.
- There is no label noise, change over time, or label delay.

**Performance numbers are provisional.** Results from this generator show that the pipeline works. They
are not evidence of real-world performance, and they will still be a synthetic demonstration after the
generator is corrected.

**Planned generator rules:**

1. `usedChip` requires card-present, `usedPin` requires `usedChip`, and `newDevice` requires
   card-not-present.
2. `minutesSinceLastTransaction` is under 1,440 exactly when `transactionsLast24h` is at least 1.
3. Legitimate accounts can be new, so an account under 30 days old no longer identifies fraud.
4. Account takeover uses established accounts only.
5. Stealthy fraud keeps a normal hour distribution and changes only one or two weak signals.
6. Card testing is no longer perfectly clean.

The fraud rate stays realistically imbalanced. There is no balanced-dataset example.

## 9. Customizing features

To change the features, edit `FeatureExtractor` and retrain:

1. Change `FEATURE_NAMES` and `extract()` together. They must stay in the same order, because the vector
   is built by position and nothing checks the alignment.
2. If you add or remove a `Transaction` field, update `Transaction`, `SyntheticTransactionGenerator` and
   any tests that build a `Transaction`.
3. Update the feature count in `FraudDetectionApplicationTest`, which expects 27.
4. Retrain.

Removing a feature you cannot supply, such as the distance features, is a valid choice.

**Behavior with an existing model (Implemented):** a saved model records its feature names, and a model
trained with different names is rejected. Startup stops with a message naming the features that changed,
and the file is left unchanged. The application never retrains over it, renames it or deletes it. To
continue, move `fraud-model.bin` out of `fraud.model.dir`, or point `fraud.model.dir` at an empty folder.
With `fraud.model.train-if-missing: true` the next start trains a new model.
