# Explain the example

Use this prompt to get a guided walkthrough before changing anything. Replace `<example>` with `minimal` or `extended`.

```text
Read SKILL.md and <example>/README.md. Then explain the <example> project to a Java developer who has
not used machine learning before.

1. Describe the business problem and what the model predicts.
2. Walk through the workflow in the code, in this order: training data, preprocessing, training,
   evaluation, saving and loading, prediction in the REST endpoint. For each step, name the class and
   method and quote the few lines that matter.
3. Explain the evaluation: why accuracy alone is misleading for fraud, and what precision and recall
   mean for the business.
4. Run `mvn test` in <example>/ and summarize what the tests check.

Do not modify any files.
```
