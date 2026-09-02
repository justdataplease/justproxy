"""Python client for the JustProxy control API."""

from .client import DEFAULT_BASE_URL, DEFAULT_TIMEOUT, JustProxyClient
from .exceptions import (
    APIError,
    AuthenticationError,
    ConfigurationError,
    InvalidResponseError,
    JustProxyConnectionError,
    JustProxyError,
)
from .helpers import (
    http_proxy_url,
    proxy_environment,
    proxy_url,
    requests_proxy_urls,
    socks_proxy_url,
)
from .models import (
    IPCheckResult,
    IPHistory,
    IPHistoryEntry,
    IpRotationStatus,
    JsonModel,
    Metrics,
    RotationResult,
    Session,
    Sessions,
    Status,
    WireGuardStatus,
)

__version__ = "0.3.0b3"

__all__ = [
    "APIError",
    "AuthenticationError",
    "ConfigurationError",
    "DEFAULT_BASE_URL",
    "DEFAULT_TIMEOUT",
    "IPCheckResult",
    "IPHistory",
    "IPHistoryEntry",
    "IpRotationStatus",
    "InvalidResponseError",
    "JsonModel",
    "JustProxyClient",
    "JustProxyConnectionError",
    "JustProxyError",
    "Metrics",
    "RotationResult",
    "Session",
    "Sessions",
    "Status",
    "WireGuardStatus",
    "http_proxy_url",
    "proxy_environment",
    "proxy_url",
    "requests_proxy_urls",
    "socks_proxy_url",
]
