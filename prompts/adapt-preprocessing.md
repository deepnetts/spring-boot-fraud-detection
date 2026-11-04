# Adapt preprocessing to a different dataset

Use this prompt when your data has different ranges, missing values or categories than the example data. Replace the
placeholders before sending it.

```text
Read SKILL.md and <example>/README.md. I want to train the <example> model on this dataset:

File: <path to a CSV file with one labelled transaction per row>
Label column: <name, and which value means fraud>

1. Profile the data without changing code: row count, fraud rate, value ranges, missing values,
   categories, and columns that could leak the label.
2. Propose the preprocessing for each column (scaling, log transform, one-hot encoding, or dropping it)
   and explain each choice. Do not fill missing values with defaults; propose how to handle them.
3. Propose a split into training and test data, by time if the data has timestamps. The test data must
   not be used for training or for choosing settings.
4. After I approve, implement the preprocessing so that training and prediction use exactly the same
   steps, run `mvn test`, delete the saved model and report the new evaluation next to the old one.
5. State clearly which results are measured on held-out data and what they do not prove.
```
