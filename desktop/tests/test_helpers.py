import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

from desktop.helpers import (
    DEFAULT_SAVED_CONNECTION,
    EM_DASH,
    ConnectionSettings,
    SavedConnectionSettings,
    describe_rotation,
    desktop_config_path,
    format_bytes,
    format_traffic,
    latest_observation_ms,
    load_saved_connection,
    normalize_host,
    parse_android_setup,
    parse_port,
    save_saved_connection,
)


ANDROID_SETUP = (
    "JustProxy\n"
    "HTTP proxy: http://jp_1234:secret-token@192.168.43.1:8282\n"
    "SOCKS5 proxy: socks5h://jp_1234:secret-token@192.168.43.1:8282\n"
    "Control API: http://192.168.43.1:8283\n"
    "API token: secret-token\n"
    "USB setup: adb forward tcp:8282 tcp:8282 && "
    "adb forward tcp:8283 tcp:8283"
)


class ConnectionSettingsTests(unittest.TestCase):
    def test_builds_ipv6_control_url_and_masks_token_in_repr(self) -> None:
        settings = ConnectionSettings.from_strings(
            "[2001:db8::1]", "8283", "super-secret", "jp_user", "8282"
        )

        self.assertEqual("http://[2001:db8::1]:8283", settings.base_url)
        self.assertNotIn("super-secret", repr(settings))

    def test_rejects_url_host_bad_ports_and_newline_token(self) -> None:
        with self.assertRaisesRegex(ValueError, "not a URL"):
            normalize_host("http://phone.local")
        with self.assertRaisesRegex(ValueError, "1 to 65535"):
            parse_port("70000", "Control port")
        with self.assertRaisesRegex(ValueError, "newlines"):
            ConnectionSettings.from_strings(
                "phone.local", "8283", "bad\ntoken", "jp_user", "8282"
            )


class AndroidSetupParserTests(unittest.TestCase):
    def test_parses_the_android_copy_setup_block_without_exposing_token_in_repr(self) -> None:
        setup = parse_android_setup(ANDROID_SETUP)

        self.assertEqual("192.168.43.1", setup.host)
        self.assertEqual(8283, setup.control_port)
        self.assertEqual("secret-token", setup.token)
        self.assertEqual("jp_1234", setup.proxy_username)
        self.assertEqual(8282, setup.proxy_port)
        self.assertNotIn("secret-token", repr(setup))

    def test_accepts_crlf_and_percent_encoded_credentials(self) -> None:
        text = (
            "JustProxy\r\n"
            "HTTP proxy: http://jp%5F1234:secret%2Dtoken@phone.local:9000\r\n"
            "SOCKS5 proxy: socks5h://jp%5F1234:secret%2Dtoken@phone.local:9000\r\n"
            "Control API: http://phone.local:9001\r\n"
            "API token: secret-token\r\n"
            "USB setup: ignored"
        )

        setup = parse_android_setup(text)

        self.assertEqual("phone.local", setup.host)
        self.assertEqual("jp_1234", setup.proxy_username)
        self.assertEqual(9000, setup.proxy_port)
        self.assertEqual(9001, setup.control_port)

    def test_rejects_inconsistent_or_incomplete_setup_blocks(self) -> None:
        cases = (
            (ANDROID_SETUP.replace("API token: secret-token", "API token: another"), "password"),
            (
                ANDROID_SETUP.replace(
                    "SOCKS5 proxy: socks5h://jp_1234:secret-token@192.168.43.1:8282",
                    "SOCKS5 proxy: socks5h://jp_1234:secret-token@192.168.43.1:8284",
                ),
                "do not match",
            ),
            (
                ANDROID_SETUP.replace(
                    "Control API: http://192.168.43.1:8283",
                    "Control API: http://192.168.43.2:8283",
                ),
                "hosts do not match",
            ),
            (
                ANDROID_SETUP.replace(
                    "Control API: http://192.168.43.1:8283",
                    "Control API: http://192.168.43.1:9000",
                ),
                "one above",
            ),
            (
                ANDROID_SETUP.replace("secret-token", "secret-tok\u00e9n"),
                "invalid",
            ),
            (ANDROID_SETUP.replace("Control API:", "Other API:"), "missing"),
            ("HTTP proxy: http://jp:secret@phone.local:8282", "header"),
        )
        for text, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    parse_android_setup(text)

    def test_rejects_duplicate_fields_paths_and_oversized_clipboard_text(self) -> None:
        duplicate = ANDROID_SETUP + "\nAPI token: secret-token"
        with self.assertRaisesRegex(ValueError, "duplicate"):
            parse_android_setup(duplicate)
        with self.assertRaisesRegex(ValueError, "path"):
            parse_android_setup(
                ANDROID_SETUP.replace(
                    "Control API: http://192.168.43.1:8283",
                    "Control API: http://192.168.43.1:8283/status",
                )
            )
        with self.assertRaisesRegex(ValueError, "large"):
            parse_android_setup("JustProxy\n" + ("x" * 17_000))


class SavedConnectionTests(unittest.TestCase):
    def test_uses_appdata_and_has_a_cross_platform_fallback(self) -> None:
        self.assertEqual(
            Path("C:/Users/test/AppData/Roaming/JustProxy/desktop.json"),
            desktop_config_path(
                {"APPDATA": "C:/Users/test/AppData/Roaming"},
                Path("C:/ignored"),
            ),
        )
        self.assertEqual(
            Path("C:/xdg/JustProxy/desktop.json"),
            desktop_config_path(
                {"XDG_CONFIG_HOME": "C:/xdg"},
                Path("C:/ignored"),
            ),
        )
        self.assertEqual(
            Path("C:/home/.config/JustProxy/desktop.json"),
            desktop_config_path({}, Path("C:/home")),
        )

    def test_round_trips_only_the_four_non_secret_fields(self) -> None:
        settings = SavedConnectionSettings.from_strings(
            "Phone.Local", "9001", "jp_1234", "9000"
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "nested" / "desktop.json"
            save_saved_connection(path, settings)
            saved_text = path.read_text(encoding="utf-8")
            payload = json.loads(saved_text)
            loaded, warning = load_saved_connection(path)

            self.assertEqual(settings, loaded)
            self.assertIsNone(warning)
            self.assertEqual(
                {
                    "schema_version",
                    "host",
                    "control_port",
                    "proxy_username",
                    "proxy_port",
                },
                set(payload),
            )
            self.assertNotIn("token", saved_text.casefold())
            self.assertNotIn("password", saved_text.casefold())
            self.assertEqual([], list(path.parent.glob("*.tmp")))

    def test_missing_or_bad_files_fall_back_without_raising(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "desktop.json"
            loaded, warning = load_saved_connection(path)
            self.assertEqual(DEFAULT_SAVED_CONNECTION, loaded)
            self.assertIsNone(warning)

            path.write_text("{broken", encoding="utf-8")
            loaded, warning = load_saved_connection(path)
            self.assertEqual(DEFAULT_SAVED_CONNECTION, loaded)
            self.assertIn("ignored", warning or "")

            path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "host": "phone.local",
                        "control_port": 8283,
                        "proxy_username": "jp_user",
                        "proxy_port": 8282,
                        "token": "must-not-be-loaded",
                    }
                ),
                encoding="utf-8",
            )
            loaded, warning = load_saved_connection(path)
            self.assertEqual(DEFAULT_SAVED_CONNECTION, loaded)
            self.assertIn("secrets", warning or "")

    def test_rejects_invalid_saved_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "port"):
            SavedConnectionSettings.from_json_value(
                {
                    "schema_version": 1,
                    "host": "phone.local",
                    "control_port": True,
                    "proxy_username": "jp_user",
                    "proxy_port": 8282,
                }
            )
        with self.assertRaisesRegex(ValueError, "schema"):
            SavedConnectionSettings.from_json_value(
                {
                    "schema_version": 2,
                    "host": "phone.local",
                    "control_port": 8283,
                    "proxy_username": "jp_user",
                    "proxy_port": 8282,
                }
            )


class FormattingTests(unittest.TestCase):
    def test_formats_byte_units_and_missing_values(self) -> None:
        self.assertEqual("0 B", format_bytes(0))
        self.assertEqual("1023 B", format_bytes(1023))
        self.assertEqual("1.00 KiB", format_bytes(1024))
        self.assertEqual("1.00 GiB", format_bytes(1024**3))
        self.assertEqual(EM_DASH, format_bytes(None))

    def test_formats_directional_and_total_traffic(self) -> None:
        self.assertEqual(
            "Up 1.00 KiB  /  Down 2.00 KiB  /  Total 3.00 KiB",
            format_traffic(1024, 2048),
        )
        self.assertIn("Total {0}".format(EM_DASH), format_traffic(None, 20))


class RotationDescriptionTests(unittest.TestCase):
    def test_every_outcome_has_no_guarantee_caveat(self) -> None:
        cases = (
            describe_rotation(True, True, False),
            describe_rotation(True, False, False),
            describe_rotation(True, None, True),
            describe_rotation(False, None, False),
        )
        for description in cases:
            self.assertIn("not guaranteed", description)

        self.assertIn("observed a different", cases[0])
        self.assertIn("unchanged", cases[1])
        self.assertIn("not been confirmed", cases[2])
        self.assertIn("not accepted", cases[3])


class ObservationPollingTests(unittest.TestCase):
    def test_finds_newest_valid_observation_timestamp(self) -> None:
        history = SimpleNamespace(
            items=(
                SimpleNamespace(observed_at_ms=100),
                SimpleNamespace(observed_at_ms=None),
                SimpleNamespace(observed_at_ms=True),
                SimpleNamespace(observed_at_ms=250),
            )
        )
        self.assertEqual(250, latest_observation_ms(history))
        self.assertEqual(-1, latest_observation_ms(SimpleNamespace(items=())))
        self.assertEqual(-1, latest_observation_ms(object()))


if __name__ == "__main__":
    unittest.main()
