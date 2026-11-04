# Review a change

Use this prompt after changing fields, data, preprocessing, training or the API, whether you or an assistant made the
change.

```text
Read SKILL.md. Review the uncommitted changes in <example>/ against the rules in SKILL.md.

Check in particular:
1. Training and prediction use the same inputs in the same order and the same preprocessing.
2. The test data is unchanged and independent of all training data and of every setting that was chosen.
3. Missing values are rejected, not replaced with defaults.
4. The Deep Netts 4.0.4 workarounds are still in place.
5. Tests cover the change, and `mvn test` passes.
6. The evaluation before and after the change, measured on the same test data.
7. The README and requests.http match the new behavior, and every number in them was measured.

Report findings ordered by severity, with file and line. Do not modify files unless I ask.
```
