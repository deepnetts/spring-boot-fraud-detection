# Replace the transaction fields

Use this prompt to replace the example fields with the fields of your own payment system. Replace the placeholders
before sending it.

```text
Read SKILL.md and <example>/README.md. I want the <example> model to use the fields of our payment
system instead of the example fields.

Our fields: <list each field with its type, meaning and source system, for example
 - merchantCountry (ISO code, from the acquirer message)
 - deviceRiskScore (0 to 1, from our device fingerprinting service)>

1. Before changing code, show a table that maps each of our fields to an existing input, a new input,
   or "not usable". Mark fields that are only known after the payment, such as chargebacks; they must
   not become inputs.
2. Explain how each new input will be encoded and scaled, and why.
3. List every file you will change, following "Replace the transaction fields" in SKILL.md.
4. After I approve, make the change, delete the saved model, run `mvn test`, and update requests.http
   and the README.
5. Explain the diff file by file, so that I can review it before I accept it.
```
