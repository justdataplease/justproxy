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

Changes to the v0.3 Shizuku beta must preserve these review requirements:

- Keep session reconnect (`/v1/rotate`, `rotate()`, and `rotate`) separate from airplane-mode cycling (`/v1/ip-rotate`, `rotate_ip()`, and `rotate-ip`).
- Do not add a generic shell or user-controlled command interface. Tests must assert exact fixed command arguments, 1-10-second bounds, timeouts, serialized operations, and a disable attempt after every airplane-enable path, including interruption and failure.
- Cover the initial airplane-disabled and mobile-data-enabled guards, marker-before-enable ordering, fail-closed cellular-loss observation, marker clearing only after verified safe state, beta.2 enable-only data recovery, permission/Binder loss, stop/restart during a cycle, and the rule that no later cycle occurs while recovery is pending.
- Update Android status/API model tests and Python SDK/CLI tests together when an IP-rotation field, discriminator, or endpoint changes. Preserve positional Python model compatibility.
- Run the real-Pixel checklist in the root README before a beta release when a change touches Shizuku, connectivity commands, recovery, cellular callbacks, foreground-service lifecycle, or IP-result reporting. Record the phone, Android build, carrier, SIM layout, and Shizuku version. Never treat the carrier returning the same IP as a software failure by itself.

Security-sensitive changes should include a regression test. In particular, report a path that can strand airplane mode on, bypass recovery, execute an arbitrary privileged command, or invoke `/v1/ip-rotate` without authentication through GitHub private vulnerability reporting instead of a public issue.
