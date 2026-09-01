# Contributing

Issues and pull requests are welcome. Keep changes focused on owner-operated, authenticated proxy and WireGuard gateway use.

Before opening a pull request:

1. Run native tests with `cargo test --locked` from `native/wireguard-gateway`.
2. Run native lint and formatting checks with `cargo clippy --all-targets --locked -- -D warnings` and `cargo fmt -- --check`.
3. Run Android/JVM tests with `./gradlew testDebugUnitTest`.
4. Run Android lint with `./gradlew lintDebug` and build the APK with `./gradlew assembleDebug`.
5. Run Python tests with `python -m unittest discover -s python/tests -v`.
6. Document protocol, API, privacy, or behavior changes.
7. Do not commit credentials, signing keys, exported WireGuard profiles, traffic captures, build caches, or analytics databases.

Security-sensitive changes should include a regression test. Please report exploitable vulnerabilities through the private process in SECURITY.md instead of a public issue.
