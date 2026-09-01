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
    JsonModel,
    Metrics,
    RotationResult,
    Session,
    Sessions,
    Status,
)

__version__ = "0.1.0"

__all__ = [
    "APIError",
    "AuthenticationError",
    "ConfigurationError",
    "DEFAULT_BASE_URL",
    "DEFAULT_TIMEOUT",
    "IPCheckResult",
    "IPHistory",
    "IPHistoryEntry",
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
    "http_proxy_url",
    "proxy_environment",
    "proxy_url",
    "requests_proxy_urls",
    "socks_proxy_url",
]
