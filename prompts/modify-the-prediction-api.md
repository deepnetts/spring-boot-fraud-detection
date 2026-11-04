# Modify the prediction API

Use this prompt to change what the REST API accepts or returns. Replace the placeholders before sending it.

```text
Read SKILL.md and <example>/README.md. I want to change the prediction API of the <example> project:

<Describe the change, for example:
 - Return a decision APPROVE, REVIEW or DECLINE based on two thresholds.
 - Accept the merchant ID and return it in the response.>

1. Show the new request and response records and an example JSON exchange before writing code.
2. Keep request and response types as Java records with Bean Validation, and keep the model score
   separate from the business decision.
3. If the change adds a model input, follow "Replace the transaction fields" in SKILL.md instead of only
   changing the API.
4. After I approve, implement it, update the controller tests, requests.http and the README, and run
   `mvn test`.
5. Summarize what changed for API clients and whether existing requests still work.
```
