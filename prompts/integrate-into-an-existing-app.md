# Integrate the example into an existing Spring Boot application

Use this prompt to move the fraud model into your own application. Replace the placeholders before sending it.

```text
Read SKILL.md and <example>/README.md. I want to add fraud scoring from the <example> project to our
existing Spring Boot application in <path to the application>.

Where it should be used: <for example, in PaymentService.authorize before the payment is sent on>

1. Read our application first and describe how it is structured: Spring Boot and Java versions, where
   payments are processed, and how configuration and tests are organized.
2. Propose which classes to copy or adapt, where the model file will live, and when the model is trained
   or loaded. Check that our Java version supports Deep Netts 4.x.
3. Propose how our payment data maps to the model inputs. Missing values must not be replaced with
   defaults.
4. After I approve, make the change in small steps, run our tests after each step, and add tests for
   the new scoring call.
5. Explain each step, including what happens when the model is not loaded yet, so that I can review it.
```
