import unittest
from types import SimpleNamespace

from desktop.helpers import (
    EM_DASH,
    ConnectionSettings,
    describe_rotation,
    format_bytes,
    format_traffic,
    latest_observation_ms,
    normalize_host,
    parse_port,
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
