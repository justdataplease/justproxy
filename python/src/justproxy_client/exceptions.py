"""Exceptions raised by the JustProxy client."""

from typing import Any, Optional


class JustProxyError(Exception):
    """Base class for all client errors."""


class ConfigurationError(JustProxyError, ValueError):
    """The client or a proxy helper received invalid configuration."""


class JustProxyConnectionError(JustProxyError):
    """The control API could not be reached."""


class InvalidResponseError(JustProxyError):
    """The control API returned a response that does not match its contract."""


class APIError(JustProxyError):
    """The control API returned a non-successful HTTP response."""

    def __init__(
        self,
        status_code: int,
        message: str,
        *,
        response_body: str = "",
        payload: Optional[Any] = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.response_body = response_body
        self.payload = payload

    def __str__(self) -> str:
        return "HTTP {0}: {1}".format(self.status_code, self.message)


class AuthenticationError(APIError):
    """The Bearer token was missing, rejected, or not authorized."""
