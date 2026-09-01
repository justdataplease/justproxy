"""Pure helpers used by the JustProxy desktop companion."""

from __future__ import annotations

from dataclasses import dataclass, field
import ipaddress
from typing import Any, Optional


EM_DASH = "\N{EM DASH}"


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
