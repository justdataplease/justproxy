# Contributing

Issues and pull requests are welcome. Keep changes focused on owner-operated, authenticated proxy use.

Before opening a pull request:

1. Run Android/JVM tests with ./gradlew testDebugUnitTest.
2. Run Android lint with ./gradlew lintDebug.
3. Run Python tests with python -m unittest discover -s python/tests -v.
4. Run desktop helper tests with python -m unittest discover -s desktop/tests -v.
5. Document protocol, API, privacy, or behavior changes.
6. Do not commit credentials, signing keys, traffic captures, build caches, or analytics databases.

Security-sensitive changes should include a regression test. Please report exploitable vulnerabilities through the private process in SECURITY.md instead of a public issue.
