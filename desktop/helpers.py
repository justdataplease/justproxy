"""Pure helpers used by the JustProxy desktop companion."""

from __future__ import annotations

from dataclasses import dataclass, field
import hmac
import ipaddress
import json
import os
from pathlib import Path
import tempfile
from typing import Any, Mapping, Optional
from urllib.parse import unquote, urlsplit


EM_DASH = "\N{EM DASH}"
CONFIG_SCHEMA_VERSION = 1
MAX_SETUP_TEXT_LENGTH = 16_384


def parse_port(value: str, label: str) -> int:
    """Parse a TCP port with a useful field-specific error."""

    text = value.strip()
    if not text or not text.isascii() or not text.isdecimal():
        raise ValueError("{0} must be a number from 1 to 65535".format(label))
    port = int(text, 10)
    if not 1 <= port <= 65535:
        raise ValueError("{0} must be a number from 1 to 65535".format(label))
    return port


def normalize_host(value: str) -> str:
    """Return a normalized host name or IP literal without a scheme or path."""

    host = value.strip()
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    if not host:
        raise ValueError("Host is required")
    if any(character.isspace() for character in host):
        raise ValueError("Host must not contain whitespace")
    if any(character in host for character in "/\\@?#") or "://" in host:
        raise ValueError("Host must be a host name or IP address, not a URL")

    if ":" in host:
        try:
            address = ipaddress.ip_address(host)
        except ValueError as exc:
            raise ValueError("Host contains an invalid IPv6 address") from exc
        if address.version != 6:
            raise ValueError("Host contains an invalid IPv6 address")
        return address.compressed

    if "." in host and all(character.isdecimal() or character == "." for character in host):
        try:
            address = ipaddress.ip_address(host)
        except ValueError as exc:
            raise ValueError("Host contains an invalid IPv4 address") from exc
        if address.version != 4:
            raise ValueError("Host contains an invalid IPv4 address")
        return str(address)

    try:
        ascii_host = host.encode("idna").decode("ascii")
    except UnicodeError as exc:
        raise ValueError("Host name is invalid") from exc
    if len(ascii_host) > 253:
        raise ValueError("Host name is too long")
    labels = ascii_host.rstrip(".").split(".")
    if not labels or any(not label or len(label) > 63 for label in labels):
        raise ValueError("Host name is invalid")
    for label in labels:
        if label.startswith("-") or label.endswith("-"):
            raise ValueError("Host name is invalid")
        if not all(character.isalnum() or character == "-" for character in label):
            raise ValueError("Host name is invalid")
    return ascii_host.lower().rstrip(".")


@dataclass(frozen=True)
class ConnectionSettings:
    """Validated values captured from the connection form."""

    host: str
    control_port: int
    token: str = field(repr=False)
    proxy_username: str
    proxy_port: int

    @classmethod
    def from_strings(
        cls,
        host: str,
        control_port: str,
        token: str,
        proxy_username: str,
        proxy_port: str,
    ) -> "ConnectionSettings":
        normalized_token = token.strip()
        if not normalized_token:
            raise ValueError("Token is required")
        if "\r" in token or "\n" in token:
            raise ValueError("Token must not contain newlines")

        normalized_username = proxy_username.strip()
        if "\r" in proxy_username or "\n" in proxy_username:
            raise ValueError("Proxy username must not contain newlines")
        if ":" in normalized_username:
            raise ValueError("Proxy username must not contain ':'")

        return cls(
            host=normalize_host(host),
            control_port=parse_port(control_port, "Control port"),
            token=normalized_token,
            proxy_username=normalized_username,
            proxy_port=parse_port(proxy_port, "Proxy port"),
        )

    @property
    def base_url(self) -> str:
        host = "[{0}]".format(self.host) if ":" in self.host else self.host
        return "http://{0}:{1}".format(host, self.control_port)


@dataclass(frozen=True)
class SavedConnectionSettings:
    """The non-secret connection fields that may be stored on the computer."""

    host: str = "127.0.0.1"
    control_port: int = 8283
    proxy_username: str = ""
    proxy_port: int = 8282

    @classmethod
    def from_strings(
        cls,
        host: str,
        control_port: str,
        proxy_username: str,
        proxy_port: str,
    ) -> "SavedConnectionSettings":
        normalized_username = proxy_username.strip()
        if "\r" in proxy_username or "\n" in proxy_username:
            raise ValueError("Proxy username must not contain newlines")
        if ":" in normalized_username:
            raise ValueError("Proxy username must not contain ':'")
        return cls(
            host=normalize_host(host),
            control_port=parse_port(control_port, "Control port"),
            proxy_username=normalized_username,
            proxy_port=parse_port(proxy_port, "Proxy port"),
        )

    @classmethod
    def from_json_value(cls, value: Any) -> "SavedConnectionSettings":
        if not isinstance(value, dict):
            raise ValueError("settings must be a JSON object")
        if value.get("schema_version") != CONFIG_SCHEMA_VERSION:
            raise ValueError("settings use an unsupported schema version")
        if "token" in value or "password" in value or "proxy_password" in value:
            raise ValueError("settings must not contain secrets")

        host = value.get("host")
        proxy_username = value.get("proxy_username")
        control_port = value.get("control_port")
        proxy_port = value.get("proxy_port")
        if not isinstance(host, str) or not isinstance(proxy_username, str):
            raise ValueError("settings contain invalid text fields")
        if (
            isinstance(control_port, bool)
            or not isinstance(control_port, int)
            or isinstance(proxy_port, bool)
            or not isinstance(proxy_port, int)
        ):
            raise ValueError("settings contain invalid port fields")
        return cls.from_strings(
            host,
            str(control_port),
            proxy_username,
            str(proxy_port),
        )

    def as_json_value(self) -> dict[str, Any]:
        return {
            "schema_version": CONFIG_SCHEMA_VERSION,
            "host": self.host,
            "control_port": self.control_port,
            "proxy_username": self.proxy_username,
            "proxy_port": self.proxy_port,
        }


DEFAULT_SAVED_CONNECTION = SavedConnectionSettings()


def desktop_config_path(
    environ: Optional[Mapping[str, str]] = None,
    home: Optional[Path] = None,
) -> Path:
    """Return the per-user desktop preferences path without creating it."""

    environment = os.environ if environ is None else environ
    appdata = environment.get("APPDATA", "").strip()
    if appdata:
        base = Path(appdata)
    else:
        xdg_config = environment.get("XDG_CONFIG_HOME", "").strip()
        base = Path(xdg_config) if xdg_config else (home or Path.home()) / ".config"
    return base / "JustProxy" / "desktop.json"


def load_saved_connection(
    path: Path,
) -> tuple[SavedConnectionSettings, Optional[str]]:
    """Load non-secret fields, falling back safely when the file is unavailable."""

    try:
        if path.stat().st_size > 65_536:
            raise ValueError("settings file is unexpectedly large")
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
        return SavedConnectionSettings.from_json_value(value), None
    except FileNotFoundError:
        return DEFAULT_SAVED_CONNECTION, None
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        detail = " ".join(str(error).split()) or error.__class__.__name__
        return (
            DEFAULT_SAVED_CONNECTION,
            "Saved connection settings were ignored: {0}".format(detail),
        )


def save_saved_connection(path: Path, settings: SavedConnectionSettings) -> None:
    """Atomically store only the explicitly non-secret connection fields."""

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix="desktop-",
            suffix=".tmp",
            dir=str(path.parent),
            delete=False,
        ) as handle:
            temporary_path = Path(handle.name)
            json.dump(settings.as_json_value(), handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except OSError:
                pass


@dataclass(frozen=True)
class PastedSetup:
    """Validated fields parsed from Android's Copy setup clipboard block."""

    host: str
    control_port: int
    token: str = field(repr=False)
    proxy_username: str
    proxy_port: int


@dataclass(frozen=True)
class _SetupUrl:
    host: str
    port: int
    username: Optional[str]
    password: Optional[str] = field(repr=False)


def _parse_setup_url(
    value: str,
    label: str,
    expected_scheme: str,
    require_credentials: bool,
) -> _SetupUrl:
    if not value or any(character in value for character in "\r\n\0"):
        raise ValueError("{0} is invalid".format(label))
    parsed = urlsplit(value.strip())
    if parsed.scheme.lower() != expected_scheme or not parsed.netloc:
        raise ValueError("{0} must use {1}://".format(label, expected_scheme))
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ValueError("{0} must not contain a path, query, or fragment".format(label))
    try:
        raw_host = parsed.hostname
        port = parsed.port
    except ValueError as error:
        raise ValueError("{0} contains an invalid port".format(label)) from error
    if raw_host is None or port is None:
        raise ValueError("{0} must include a host and port".format(label))

    username = unquote(parsed.username) if parsed.username is not None else None
    password = unquote(parsed.password) if parsed.password is not None else None
    if require_credentials and (not username or not password):
        raise ValueError("{0} must include a username and password".format(label))
    if require_credentials and (
        not username.isascii()
        or not password.isascii()
        or any(character.isspace() for character in username + password)
    ):
        raise ValueError("{0} contains invalid credentials".format(label))
    if not require_credentials and (username is not None or password is not None):
        raise ValueError("{0} must not include credentials".format(label))
    return _SetupUrl(normalize_host(raw_host), port, username, password)


def parse_android_setup(text: str) -> PastedSetup:
    """Parse and cross-check the clipboard block produced by Android Copy setup."""

    if not isinstance(text, str) or not text.strip():
        raise ValueError("Clipboard does not contain a JustProxy setup block")
    if len(text) > MAX_SETUP_TEXT_LENGTH:
        raise ValueError("Clipboard setup block is unexpectedly large")

    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines or lines[0].casefold() != "justproxy":
        raise ValueError("Clipboard does not start with the JustProxy setup header")

    supported_labels = {
        "http proxy",
        "socks5 proxy",
        "control api",
        "api token",
    }
    values: dict[str, str] = {}
    for line in lines[1:]:
        label, separator, value = line.partition(":")
        normalized_label = label.strip().casefold()
        if not separator or normalized_label not in supported_labels:
            continue
        if normalized_label in values:
            raise ValueError("Setup block contains duplicate {0} fields".format(label.strip()))
        values[normalized_label] = value.strip()

    missing = sorted(supported_labels.difference(values))
    if missing:
        raise ValueError("Setup block is missing: {0}".format(", ".join(missing)))

    http_proxy = _parse_setup_url(values["http proxy"], "HTTP proxy", "http", True)
    socks_proxy = _parse_setup_url(
        values["socks5 proxy"], "SOCKS5 proxy", "socks5h", True
    )
    control_api = _parse_setup_url(values["control api"], "Control API", "http", False)
    token = values["api token"].strip()
    if (
        not token
        or not token.isascii()
        or any(character.isspace() for character in token)
        or any(character in token for character in "\r\n\0")
    ):
        raise ValueError("API token is invalid")

    if (
        socks_proxy.host != http_proxy.host
        or socks_proxy.port != http_proxy.port
        or socks_proxy.username != http_proxy.username
        or not hmac.compare_digest(socks_proxy.password or "", http_proxy.password or "")
    ):
        raise ValueError("HTTP and SOCKS5 proxy fields do not match")
    if control_api.host != http_proxy.host:
        raise ValueError("Control API and proxy hosts do not match")
    if control_api.port != http_proxy.port + 1:
        raise ValueError("Control API port must be one above the proxy port")
    if not hmac.compare_digest(http_proxy.password or "", token):
        raise ValueError("API token and proxy password do not match")

    settings = ConnectionSettings.from_strings(
        http_proxy.host,
        str(control_api.port),
        token,
        http_proxy.username or "",
        str(http_proxy.port),
    )
    if not settings.proxy_username:
        raise ValueError("Proxy username is required")
    return PastedSetup(
        host=settings.host,
        control_port=settings.control_port,
        token=settings.token,
        proxy_username=settings.proxy_username,
        proxy_port=settings.proxy_port,
    )


def format_bytes(value: Optional[int]) -> str:
    """Format a non-negative byte counter using IEC units."""

    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return EM_DASH
    units = ("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    amount = float(value)
    unit = units[0]
    for candidate in units:
        unit = candidate
        if amount < 1024.0 or candidate == units[-1]:
            break
        amount /= 1024.0

    if unit == "B":
        return "{0} B".format(int(amount))
    if amount >= 100.0:
        return "{0:.0f} {1}".format(amount, unit)
    if amount >= 10.0:
        return "{0:.1f} {1}".format(amount, unit)
    return "{0:.2f} {1}".format(amount, unit)


def format_traffic(uploaded: Optional[int], downloaded: Optional[int]) -> str:
    """Format upload, download, and combined counters for one period."""

    total: Optional[int] = None
    if (
        isinstance(uploaded, int)
        and not isinstance(uploaded, bool)
        and uploaded >= 0
        and isinstance(downloaded, int)
        and not isinstance(downloaded, bool)
        and downloaded >= 0
    ):
        total = uploaded + downloaded
    return "Up {0}  /  Down {1}  /  Total {2}".format(
        format_bytes(uploaded), format_bytes(downloaded), format_bytes(total)
    )


def format_count(value: Optional[int]) -> str:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return EM_DASH
    return "{0:,}".format(value)


def latest_observation_ms(history: Any) -> int:
    """Return the newest valid IP-history timestamp, or -1 when none exists."""

    latest = -1
    for item in getattr(history, "items", ()):
        observed_at_ms = getattr(item, "observed_at_ms", None)
        if isinstance(observed_at_ms, int) and not isinstance(observed_at_ms, bool):
            latest = max(latest, observed_at_ms)
    return latest


def describe_rotation(
    accepted: bool,
    ip_changed: Optional[bool],
    manual_carrier_reset_required: bool,
    response_message: Optional[str] = None,
) -> str:
    """Describe reconnect/rotation results without promising a carrier IP change."""

    if accepted:
        parts = ["Reconnect request accepted by the phone."]
    else:
        parts = ["Reconnect request was not accepted by the phone."]

    if ip_changed is True:
        parts.append("The phone observed a different public IP.")
    elif ip_changed is False:
        parts.append("The observed public IP was unchanged.")
    else:
        parts.append("A public-IP change has not been confirmed.")

    if manual_carrier_reset_required:
        parts.append("The phone reports that a manual carrier reset may be required.")
    if response_message:
        compact = " ".join(response_message.split())[:240]
        if compact:
            parts.append("Phone response: {0}".format(compact))
    parts.append("A carrier-assigned IP change is not guaranteed.")
    return " ".join(parts)
