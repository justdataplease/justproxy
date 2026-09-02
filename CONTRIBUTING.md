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

- Keep session reconnect (`/v1/rotate`, `rotate()`, and `rotate`) separate
  from mobile-data cycling (`/v1/ip-rotate`, `rotate_ip()`, and
  `rotate-ip`).
- Do not add a generic shell or user-controlled command interface. Tests must
  assert the exact fixed command arguments, 1–10-second bounds, timeout
  behavior, serialized operations, and an enable attempt after every disable
  path, including interruption and failure.
- Cover the initially-disabled/unknown data-state guard, marker-before-disable
  ordering, marker clearing only after positive enabled-state verification,
  recovery-only restart behavior, permission/binder loss, stop/restart during a
  cycle, and the rule that no later disable occurs while recovery is pending.
- Update Android status/API model tests and Python SDK/CLI tests together when
  an IP-rotation field or endpoint changes.
- Run the real-Pixel checklist in the root README before a beta release when a
  change touches Shizuku, telephony commands, recovery, cellular callbacks,
  foreground-service lifecycle, or IP-result reporting. Record the phone,
  Android build, carrier, SIM layout, and Shizuku version. Never treat the
  carrier returning the same IP as a software failure by itself.

Security-sensitive changes should include a regression test. In particular,
report a path that can strand mobile data off, bypass recovery, execute an
arbitrary privileged command, or invoke `/v1/ip-rotate` without authentication
through the private process in SECURITY.md instead of a public issue.
