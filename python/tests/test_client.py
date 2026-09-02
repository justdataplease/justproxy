import io
import json
import os
import sys
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Tuple
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from justproxy_client import (  # noqa: E402
    APIError,
    AuthenticationError,
    ConfigurationError,
    IpRotationStatus,
    InvalidResponseError,
    JustProxyClient,
    RotationResult,
    WireGuardStatus,
    http_proxy_url,
    proxy_environment,
    requests_proxy_urls,
    socks_proxy_url,
)
from justproxy_client.cli import main as cli_main  # noqa: E402


STATUS = {
    "version": "v1",
    "state": "running",
    "message": "Proxy is ready",
    "listen_host": "0.0.0.0",
    "proxy_port": 8888,
    "control_port": 8283,
    "egress": "cellular",
    "public_ip": "203.0.113.42",
    "active_connections": 2,
    "started_at_ms": 1_700_000_000_000,
    "next_rotation_at_ms": 1_700_000_060_000,
    "rotation_guarantees_ip_change": False,
    "wireguard": {
        "state": "running",
        "message": "WireGuard gateway is running",
        "port": 51820,
        "configured_peers": 1,
        "active_flows": 3,
        "total_flows": 9,
        "uploaded_bytes": 1_000,
        "downloaded_bytes": 2_000,
        "last_handshake_ms": 1_700_000_050_000,
        "future_wireguard_field": {"preserved": True},
    },
    "ip_rotation": {
        "enabled": True,
        "provider": "shizuku",
        "state": "ready",
        "message": "Ready",
        "interval_minutes": 60,
        "mode": "airplane_mode",
        "airplane_mode_seconds": 1,
        "data_off_seconds": 1,
        "next_at_ms": 1_700_003_600_000,
        "last_attempt_at_ms": 1_700_000_000_000,
        "last_outcome": "unchanged",
        "recovery_required": False,
        "guarantees_ip_change": False,
        "future_ip_rotation_field": {"preserved": True},
    },
    "future_status_field": {"preserved": True},
}

METRICS = {
    "run_uploaded_bytes": 10,
    "run_downloaded_bytes": 20,
    "today_uploaded_bytes": 30,
    "today_downloaded_bytes": 40,
    "lifetime_uploaded_bytes": 50,
    "lifetime_downloaded_bytes": 60,
    "lifetime_sessions": 7,
    "ip_change_count": 3,
    "wireguard_uploaded_bytes": 1_000,
    "wireguard_downloaded_bytes": 2_000,
    "wireguard_active_flows": 3,
    "wireguard_total_flows": 9,
}

IP_HISTORY = {
    "items": [
        {
            "ip": "203.0.113.41",
            "observed_at_ms": 1_699_999_000_000,
            "changed": False,
        },
        {
            "ip": "203.0.113.42",
            "observed_at_ms": 1_700_000_000_000,
            "changed": True,
            "future_item_field": "kept",
        },
    ],
    "future_page_field": "kept",
}

SESSIONS = {
    "items": [
        {
            "started_at_ms": 1_700_000_000_000,
            "ended_at_ms": 1_700_000_001_000,
            "client": "127.0.0.1:52000",
            "protocol": "http-connect",
            "target": "example.com:443",
            "uploaded_bytes": 100,
            "downloaded_bytes": 200,
            "result": "completed",
        }
    ]
}

ROTATION = {
    "accepted": True,
    "action": "sessions_reconnect_scheduled",
    "previous_ip": "203.0.113.42",
    "ip_changed": None,
    "manual_carrier_reset_required": True,
    "message": "Sessions will reconnect and the public IP will be checked",
}

IP_ROTATION = {
    "accepted": True,
    "action": "airplane_mode_cycle_scheduled",
    "previous_ip": "203.0.113.42",
    "ip_changed": None,
    "manual_carrier_reset_required": False,
    "reason": None,
    "mode": "airplane_mode",
    "airplane_mode_seconds": 1,
    "data_off_seconds": 1,
    "guarantees_ip_change": False,
    "message": "Airplane mode will cycle and the public IP will be checked",
}

CHECK_IP = {
    "accepted": True,
    "message": "IP check scheduled.",
}


class _Handler(BaseHTTPRequestHandler):
    server_version = "JustProxyFake/1"

    def do_GET(self) -> None:
        self._respond()

    def do_POST(self) -> None:
        self._respond()

    def _respond(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        self.server.requests.append(  # type: ignore[attr-defined]
            {
                "method": self.command,
                "path": self.path,
                "authorization": self.headers.get("Authorization"),
                "accept": self.headers.get("Accept"),
                "content_type": self.headers.get("Content-Type"),
                "body": body,
            }
        )

        if self.headers.get("Authorization") != "Bearer secret-token":
            self._write(401, {"message": "invalid token"})
            return

        response = self.server.responses.get(  # type: ignore[attr-defined]
            (self.command, self.path),
            (404, {"message": "not found"}),
        )
        self._write(*response)

    def _write(self, status: int, payload: Any) -> None:
        if isinstance(payload, bytes):
            encoded = payload
        else:
            encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: Any) -> None:
        return


class FakeAPI:
    def __init__(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.server.requests: List[Dict[str, Any]] = []  # type: ignore[attr-defined]
        self.server.responses: Dict[Tuple[str, str], Tuple[int, Any]] = {  # type: ignore[attr-defined]
            ("GET", "/v1/status"): (200, STATUS),
            ("GET", "/v1/metrics"): (200, METRICS),
            ("GET", "/v1/ip-history"): (200, IP_HISTORY),
            ("GET", "/v1/sessions"): (200, SESSIONS),
            ("POST", "/v1/rotate"): (200, ROTATION),
            ("POST", "/v1/ip-rotate"): (200, IP_ROTATION),
            ("POST", "/v1/check-ip"): (200, CHECK_IP),
        }
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        host, port = self.server.server_address[:2]
        return "http://{0}:{1}".format(host, port)

    @property
    def requests(self) -> List[Dict[str, Any]]:
        return self.server.requests  # type: ignore[attr-defined,no-any-return]

    @property
    def responses(self) -> Dict[Tuple[str, str], Tuple[int, Any]]:
        return self.server.responses  # type: ignore[attr-defined,no-any-return]

    def __enter__(self) -> "FakeAPI":
        self.thread.start()
        return self

    def __exit__(self, *args: Any) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


class ClientTests(unittest.TestCase):
    def test_all_api_methods_are_typed_authenticated_and_forward_compatible(self) -> None:
        with FakeAPI() as api:
            client = JustProxyClient("secret-token", api.url)

            status = client.status()
            self.assertEqual(status.state, "running")
            self.assertEqual(status.public_ip, "203.0.113.42")
            self.assertEqual(status.proxy_port, 8888)
            self.assertFalse(status.rotation_guarantees_ip_change)
            self.assertEqual(status["future_status_field"], {"preserved": True})
            self.assertIsInstance(status.wireguard, WireGuardStatus)
            wireguard = status.wireguard
            self.assertIsNotNone(wireguard)
            self.assertEqual(wireguard.state, "running")
            self.assertEqual(wireguard.port, 51820)
            self.assertEqual(wireguard.configured_peers, 1)
            self.assertEqual(wireguard.active_flows, 3)
            self.assertEqual(wireguard.total_flows, 9)
            self.assertEqual(wireguard.uploaded_bytes, 1_000)
            self.assertEqual(wireguard.downloaded_bytes, 2_000)
            self.assertEqual(wireguard.last_handshake_ms, 1_700_000_050_000)
            self.assertEqual(
                wireguard["future_wireguard_field"], {"preserved": True}
            )
            self.assertIsInstance(status.ip_rotation, IpRotationStatus)
            ip_rotation = status.ip_rotation
            self.assertIsNotNone(ip_rotation)
            self.assertTrue(ip_rotation.enabled)
            self.assertEqual(ip_rotation.provider, "shizuku")
            self.assertEqual(ip_rotation.state, "ready")
            self.assertEqual(ip_rotation.interval_minutes, 60)
            self.assertEqual(ip_rotation.mode, "airplane_mode")
            self.assertEqual(ip_rotation.airplane_mode_seconds, 1)
            self.assertEqual(ip_rotation.data_off_seconds, 1)
            self.assertEqual(ip_rotation.next_at_ms, 1_700_003_600_000)
            self.assertEqual(
                ip_rotation.last_attempt_at_ms, 1_700_000_000_000
            )
            self.assertEqual(ip_rotation.last_outcome, "unchanged")
            self.assertFalse(ip_rotation.recovery_required)
            self.assertFalse(ip_rotation.guarantees_ip_change)
            self.assertEqual(
                ip_rotation["future_ip_rotation_field"], {"preserved": True}
            )

            metrics = client.metrics()
            self.assertEqual(metrics.run_downloaded_bytes, 20)
            self.assertEqual(metrics.lifetime_sessions, 7)
            self.assertEqual(metrics.wireguard_uploaded_bytes, 1_000)
            self.assertEqual(metrics.wireguard_downloaded_bytes, 2_000)
            self.assertEqual(metrics.wireguard_active_flows, 3)
            self.assertEqual(metrics.wireguard_total_flows, 9)

            history = client.ip_history()
            self.assertEqual(len(history.items), 2)
            self.assertEqual(history.items[1].ip, "203.0.113.42")
            self.assertTrue(history.items[1].changed)
            self.assertEqual(history.items[1]["future_item_field"], "kept")
            self.assertEqual(history["future_page_field"], "kept")

            sessions = client.sessions()
            self.assertEqual(len(sessions.items), 1)
            self.assertEqual(sessions.items[0].protocol, "http-connect")
            self.assertEqual(sessions.items[0].downloaded_bytes, 200)

            rotation = client.rotate()
            self.assertTrue(rotation.accepted)
            self.assertIsNone(rotation.ip_changed)
            self.assertTrue(rotation.manual_carrier_reset_required)

            ip_rotation_request = client.rotate_ip()
            self.assertTrue(ip_rotation_request.accepted)
            self.assertEqual(
                ip_rotation_request.action, "airplane_mode_cycle_scheduled"
            )
            self.assertEqual(ip_rotation_request.mode, "airplane_mode")
            self.assertEqual(ip_rotation_request.airplane_mode_seconds, 1)
            self.assertEqual(ip_rotation_request.data_off_seconds, 1)
            self.assertIsNone(ip_rotation_request.ip_changed)
            self.assertFalse(
                ip_rotation_request.manual_carrier_reset_required
            )

            check = client.check_ip()
            self.assertTrue(check.accepted)
            self.assertEqual(check.message, "IP check scheduled.")

        self.assertEqual(
            [(request["method"], request["path"]) for request in api.requests],
            [
                ("GET", "/v1/status"),
                ("GET", "/v1/metrics"),
                ("GET", "/v1/ip-history"),
                ("GET", "/v1/sessions"),
                ("POST", "/v1/rotate"),
                ("POST", "/v1/ip-rotate"),
                ("POST", "/v1/check-ip"),
            ],
        )
        self.assertTrue(
            all(
                request["authorization"] == "Bearer secret-token"
                for request in api.requests
            )
        )
        post_requests = [
            request for request in api.requests if request["method"] == "POST"
        ]
        self.assertTrue(all(request["body"] == b"{}" for request in post_requests))
        self.assertTrue(
            all(
                request["content_type"] == "application/json"
                for request in post_requests
            )
        )

    def test_additive_status_fields_are_optional_for_legacy_responses(self) -> None:
        with FakeAPI() as api:
            api.responses[("GET", "/v1/status")] = (
                200,
                {"state": "running", "legacy_unknown": "preserved"},
            )
            api.responses[("GET", "/v1/metrics")] = (
                200,
                {"run_uploaded_bytes": 12},
            )
            client = JustProxyClient("secret-token", api.url)

            status = client.status()
            self.assertIsNone(status.wireguard)
            self.assertIsNone(status.ip_rotation)
            self.assertEqual(status["legacy_unknown"], "preserved")

            metrics = client.metrics()
            self.assertEqual(metrics.run_uploaded_bytes, 12)
            self.assertIsNone(metrics.wireguard_uploaded_bytes)
            self.assertIsNone(metrics.wireguard_downloaded_bytes)
            self.assertIsNone(metrics.wireguard_active_flows)
            self.assertIsNone(metrics.wireguard_total_flows)

    def test_legacy_data_off_seconds_remains_available(self) -> None:
        with FakeAPI() as api:
            api.responses[("GET", "/v1/status")] = (
                200,
                {
                    "state": "running",
                    "ip_rotation": {
                        "provider": "shizuku",
                        "data_off_seconds": 3,
                    },
                },
            )
            status = JustProxyClient("secret-token", api.url).status()

        self.assertIsNotNone(status.ip_rotation)
        ip_rotation = status.ip_rotation
        self.assertIsNotNone(ip_rotation)
        self.assertEqual(ip_rotation.data_off_seconds, 3)
        self.assertIsNone(ip_rotation.mode)
        self.assertIsNone(ip_rotation.airplane_mode_seconds)

    def test_beta2_positional_model_constructors_remain_compatible(self) -> None:
        status = IpRotationStatus(
            {"legacy": True},
            True,
            "shizuku",
            "ready",
            "Mobile-data control is ready",
            10,
            3,
            100,
            90,
            "unchanged",
            False,
            False,
        )
        self.assertEqual(status.data_off_seconds, 3)
        self.assertEqual(status.last_outcome, "unchanged")
        self.assertIsNone(status.mode)
        self.assertIsNone(status.airplane_mode_seconds)

        rotation = RotationResult(
            {"legacy": True},
            True,
            "mobile_data_cycle_scheduled",
            "198.51.100.1",
            None,
            False,
            "Mobile data will cycle and the public IP will be checked",
        )
        self.assertIsNone(rotation.data_off_seconds)
        self.assertEqual(
            rotation.message,
            "Mobile data will cycle and the public IP will be checked",
        )
        self.assertIsNone(rotation.mode)
        self.assertIsNone(rotation.airplane_mode_seconds)

    def test_unmodified_beta2_rotation_response_still_parses(self) -> None:
        rotation = RotationResult.from_dict(
            {
                "accepted": True,
                "action": "mobile_data_cycle_scheduled",
                "previous_ip": "198.51.100.1",
                "ip_changed": None,
                "manual_carrier_reset_required": False,
                "data_off_seconds": 1,
                "message": "Mobile data will cycle and the public IP will be checked",
            }
        )

        self.assertTrue(rotation.accepted)
        self.assertEqual(rotation.data_off_seconds, 1)
        self.assertIsNone(rotation.mode)
        self.assertIsNone(rotation.airplane_mode_seconds)

    def test_authentication_error_has_status_and_payload(self) -> None:
        with FakeAPI() as api:
            client = JustProxyClient("wrong-token", api.url)
            with self.assertRaises(AuthenticationError) as raised:
                client.status()
        self.assertEqual(raised.exception.status_code, 401)
        self.assertEqual(raised.exception.message, "invalid token")
        self.assertEqual(raised.exception.payload, {"message": "invalid token"})

    def test_other_http_error_is_api_error(self) -> None:
        with FakeAPI() as api:
            api.responses[("GET", "/v1/status")] = (
                503,
                {"error": "temporarily unavailable"},
            )
            client = JustProxyClient("secret-token", api.url)
            with self.assertRaises(APIError) as raised:
                client.status()
        self.assertNotIsInstance(raised.exception, AuthenticationError)
        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(str(raised.exception), "HTTP 503: temporarily unavailable")

    def test_malformed_json_is_an_invalid_response(self) -> None:
        with FakeAPI() as api:
            api.responses[("GET", "/v1/status")] = (200, b"not-json")
            with self.assertRaises(InvalidResponseError):
                JustProxyClient("secret-token", api.url).status()

    def test_wrong_collection_shape_is_an_invalid_response(self) -> None:
        with FakeAPI() as api:
            api.responses[("GET", "/v1/sessions")] = (200, {"items": {}})
            with self.assertRaises(InvalidResponseError) as raised:
                JustProxyClient("secret-token", api.url).sessions()
        self.assertIn("must be an array", str(raised.exception))

    def test_proxy_conveniences_can_discover_the_port_from_status(self) -> None:
        with FakeAPI() as api:
            client = JustProxyClient(
                "secret-token",
                api.url,
                proxy_username="proxy-user",
            )
            self.assertEqual(
                client.requests_proxies(),
                {
                    "http": "http://proxy-user:secret-token@127.0.0.1:8888",
                    "https": "http://proxy-user:secret-token@127.0.0.1:8888",
                },
            )
            self.assertEqual(
                client.socks_url(),
                "socks5h://proxy-user:secret-token@127.0.0.1:8888",
            )

    def test_proxy_conveniences_fail_closed_without_a_username(self) -> None:
        client = JustProxyClient("secret-token")
        actions = (
            lambda: client.requests_proxies(proxy_port=8888),
            lambda: client.socks_url(proxy_port=8888),
            lambda: client.environment(proxy_port=8888),
        )
        for action in actions:
            with self.subTest(action=action):
                with self.assertRaises(ConfigurationError) as raised:
                    action()
                self.assertIn("requires proxy authentication", str(raised.exception))

    def test_proxy_credentials_support_defaults_and_per_call_overrides(self) -> None:
        client = JustProxyClient(
            "control-token",
            proxy_username="configured-user",
            proxy_password="configured-password",
        )
        self.assertEqual(
            client.requests_proxies(proxy_port=8888)["http"],
            "http://configured-user:configured-password@127.0.0.1:8888",
        )
        self.assertEqual(
            client.socks_url(
                proxy_port=8888,
                username="one-off-user",
            ),
            "socks5h://one-off-user:configured-password@127.0.0.1:8888",
        )
        control_only_client = JustProxyClient("control-token")
        self.assertEqual(
            control_only_client.socks_url(
                proxy_port=8888,
                username="one-off-user",
            ),
            "socks5h://one-off-user:control-token@127.0.0.1:8888",
        )
        self.assertEqual(
            client.environment(
                proxy_port=8888,
                username="override-user",
                password="override-password",
                include_lowercase=False,
            )["HTTP_PROXY"],
            "http://override-user:override-password@127.0.0.1:8888",
        )

    def test_configuration_validation(self) -> None:
        with self.assertRaises(ConfigurationError):
            JustProxyClient("", "http://127.0.0.1:8283")
        with self.assertRaises(ConfigurationError):
            JustProxyClient("token", "ftp://127.0.0.1:8283")
        with self.assertRaises(ConfigurationError):
            JustProxyClient("token", "http://127.0.0.1:8283?token=bad")
        with self.assertRaises(ConfigurationError):
            JustProxyClient("token", proxy_password="password-without-username")


class HelperTests(unittest.TestCase):
    def test_http_and_requests_style_urls(self) -> None:
        self.assertEqual(
            http_proxy_url(
                "proxy.example",
                8080,
                username="name@example.com",
                password="p:/ word",
            ),
            "http://name%40example.com:p%3A%2F%20word@proxy.example:8080",
        )
        self.assertEqual(
            requests_proxy_urls("proxy.example", 8080),
            {
                "http": "http://proxy.example:8080",
                "https": "http://proxy.example:8080",
            },
        )

    def test_socks_remote_dns_ipv6_and_environment(self) -> None:
        self.assertEqual(
            socks_proxy_url("2001:db8::1", 1080),
            "socks5h://[2001:db8::1]:1080",
        )
        self.assertEqual(
            socks_proxy_url("[2001:db8::1]", 1080, remote_dns=False),
            "socks5://[2001:db8::1]:1080",
        )
        values = proxy_environment("phone.local", 8888)
        self.assertEqual(values["HTTP_PROXY"], "http://phone.local:8888")
        self.assertEqual(values["ALL_PROXY"], "socks5h://phone.local:8888")
        self.assertEqual(values["http_proxy"], values["HTTP_PROXY"])

    def test_invalid_helper_configuration(self) -> None:
        with self.assertRaises(ConfigurationError):
            http_proxy_url("proxy.example", 0)
        with self.assertRaises(ConfigurationError):
            http_proxy_url("proxy.example/path", 8080)
        with self.assertRaises(ConfigurationError):
            http_proxy_url("proxy.example", 8080, password="secret")


class CLITests(unittest.TestCase):
    def test_status_command_prints_json(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with FakeAPI() as api, redirect_stdout(stdout), redirect_stderr(stderr):
            result = cli_main(
                [
                    "--base-url",
                    api.url,
                    "--token",
                    "secret-token",
                    "status",
                ]
            )
        self.assertEqual(result, 0)
        self.assertEqual(stderr.getvalue(), "")
        self.assertEqual(json.loads(stdout.getvalue())["public_ip"], "203.0.113.42")

    def test_rotate_command_preserves_unknown_ip_changed(self) -> None:
        stdout = io.StringIO()
        with FakeAPI() as api, redirect_stdout(stdout):
            result = cli_main(
                [
                    "--base-url",
                    api.url,
                    "--token",
                    "secret-token",
                    "rotate",
                ]
            )
        self.assertEqual(result, 0)
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["accepted"])
        self.assertIsNone(payload["ip_changed"])
        self.assertTrue(payload["manual_carrier_reset_required"])

    def test_rotate_ip_command_uses_the_dedicated_endpoint(self) -> None:
        stdout = io.StringIO()
        with FakeAPI() as api, redirect_stdout(stdout):
            result = cli_main(
                [
                    "--base-url",
                    api.url,
                    "--token",
                    "secret-token",
                    "rotate-ip",
                ]
            )
        self.assertEqual(result, 0)
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["accepted"])
        self.assertEqual(payload["action"], "airplane_mode_cycle_scheduled")
        self.assertEqual(payload["mode"], "airplane_mode")
        self.assertEqual(payload["airplane_mode_seconds"], 1)
        self.assertEqual(payload["data_off_seconds"], 1)
        self.assertIsNone(payload["ip_changed"])
        self.assertFalse(payload["manual_carrier_reset_required"])
        self.assertEqual(api.requests[-1]["method"], "POST")
        self.assertEqual(api.requests[-1]["path"], "/v1/ip-rotate")
        self.assertEqual(
            api.requests[-1]["authorization"], "Bearer secret-token"
        )
        self.assertEqual(api.requests[-1]["body"], b"{}")

    def test_env_can_run_without_token_when_port_is_explicit(self) -> None:
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            result = cli_main(
                [
                    "--compact",
                    "env",
                    "--host",
                    "phone.local",
                    "--proxy-port",
                    "8888",
                    "--username",
                    "proxy-user",
                    "--password",
                    "proxy-password",
                    "--uppercase-only",
                ]
            )
        self.assertEqual(result, 0)
        self.assertEqual(
            json.loads(stdout.getvalue()),
            {
                "ALL_PROXY": (
                    "socks5h://proxy-user:proxy-password@phone.local:8888"
                ),
                "HTTPS_PROXY": (
                    "http://proxy-user:proxy-password@phone.local:8888"
                ),
                "HTTP_PROXY": (
                    "http://proxy-user:proxy-password@phone.local:8888"
                ),
            },
        )

    def test_env_defaults_proxy_password_to_control_token(self) -> None:
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            result = cli_main(
                [
                    "--token",
                    "control-token",
                    "--compact",
                    "env",
                    "--host",
                    "phone.local",
                    "--proxy-port",
                    "8888",
                    "--username",
                    "proxy-user",
                    "--uppercase-only",
                ]
            )
        self.assertEqual(result, 0)
        self.assertEqual(
            json.loads(stdout.getvalue())["HTTP_PROXY"],
            "http://proxy-user:control-token@phone.local:8888",
        )

    def test_env_without_proxy_username_returns_a_clean_error(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(
            stdout
        ), redirect_stderr(stderr):
            result = cli_main(
                [
                    "--token",
                    "control-token",
                    "env",
                    "--host",
                    "phone.local",
                    "--proxy-port",
                    "8888",
                ]
            )
        self.assertEqual(result, 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertIn("JUSTPROXY_PROXY_USERNAME", stderr.getvalue())

    def test_missing_token_returns_a_clean_error(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(
            stdout
        ), redirect_stderr(stderr):
            result = cli_main(["status"])
        self.assertEqual(result, 1)
        self.assertEqual(stdout.getvalue(), "")
        self.assertIn("JUSTPROXY_TOKEN", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
