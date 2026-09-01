"""Synchronous, standard-library client for the JustProxy control API."""

import json
from http.client import HTTPResponse
from typing import Any, Dict, Mapping, Optional, Tuple, Type, TypeVar
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import (
    HTTPRedirectHandler,
    OpenerDirector,
    ProxyHandler,
    Request,
    build_opener,
)

from .exceptions import (
    APIError,
    AuthenticationError,
    ConfigurationError,
    InvalidResponseError,
    JustProxyConnectionError,
)
from .helpers import (
    proxy_environment,
    requests_proxy_urls,
    socks_proxy_url,
)
from .models import (
    IPCheckResult,
    IPHistory,
    JsonModel,
    Metrics,
    RotationResult,
    Sessions,
    Status,
)

DEFAULT_BASE_URL = "http://127.0.0.1:8283"
DEFAULT_TIMEOUT = 10.0

ModelT = TypeVar("ModelT", bound=JsonModel)


class _NoRedirectHandler(HTTPRedirectHandler):
    """Keep a Bearer token from being forwarded to a redirect target."""

    def redirect_request(  # type: ignore[override]
        self,
        req: Request,
        fp: HTTPResponse,
        code: int,
        msg: str,
        headers: Mapping[str, str],
        newurl: str,
    ) -> None:
        return None


class JustProxyClient:
    """Client for an authenticated JustProxy JSON control API.

    Args:
        token: Bearer token configured in JustProxy.
        base_url: Control API URL. Defaults to the local forwarded port.
        timeout: Per-request timeout in seconds.
        proxy_username: Username configured for the proxy listener.
        proxy_password: Proxy password. Defaults to the control token when a
            proxy username is supplied.

    System proxy environment variables are deliberately ignored for control API
    calls. This avoids accidentally sending the control token through a proxy.
    """

    def __init__(
        self,
        token: str,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT,
        *,
        proxy_username: Optional[str] = None,
        proxy_password: Optional[str] = None,
    ) -> None:
        if not isinstance(token, str) or not token.strip():
            raise ConfigurationError("a non-empty JustProxy Bearer token is required")
        if "\r" in token or "\n" in token:
            raise ConfigurationError("the Bearer token must not contain newlines")
        self.token = token.strip()
        self.base_url = self._normalize_base_url(base_url)
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)):
            raise ConfigurationError("timeout must be a positive number")
        if timeout <= 0:
            raise ConfigurationError("timeout must be a positive number")
        self.timeout = float(timeout)
        if proxy_username is not None:
            if not isinstance(proxy_username, str) or not proxy_username.strip():
                raise ConfigurationError(
                    "proxy_username must be a non-empty string when provided"
                )
            self.proxy_username: Optional[str] = proxy_username.strip()
        else:
            self.proxy_username = None
        if proxy_password is not None and not isinstance(proxy_password, str):
            raise ConfigurationError("proxy_password must be a string when provided")
        if proxy_password is not None and self.proxy_username is None:
            raise ConfigurationError(
                "proxy_password cannot be set without proxy_username"
            )
        self.proxy_password: Optional[str] = (
            proxy_password
            if proxy_password is not None
            else self.token if self.proxy_username is not None else None
        )
        self._opener: OpenerDirector = build_opener(
            ProxyHandler({}), _NoRedirectHandler()
        )

    @staticmethod
    def _normalize_base_url(base_url: str) -> str:
        if not isinstance(base_url, str) or not base_url.strip():
            raise ConfigurationError("base_url must not be empty")
        normalized = base_url.strip().rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ConfigurationError("base_url must be an absolute HTTP(S) URL")
        if parsed.username is not None or parsed.password is not None:
            raise ConfigurationError("base_url must not contain credentials")
        if parsed.query or parsed.fragment:
            raise ConfigurationError("base_url must not contain a query or fragment")
        return normalized

    def __enter__(self) -> "JustProxyClient":
        return self

    def __exit__(self, *args: Any) -> None:
        self.close()

    def close(self) -> None:
        """Close the client.

        urllib opens a connection per call, so this currently has no resources
        to release. The method makes context-manager use forward compatible.
        """

    def status(self) -> Status:
        """Return service, listener, connection, and current-IP status."""

        return self._get_model("/v1/status", Status)

    def metrics(self) -> Metrics:
        """Return run, daily, and lifetime traffic counters."""

        return self._get_model("/v1/metrics", Metrics)

    def ip_history(self) -> IPHistory:
        """Return observed public-IP history."""

        return self._get_model("/v1/ip-history", IPHistory)

    def sessions(self) -> Sessions:
        """Return recent proxy sessions."""

        return self._get_model("/v1/sessions", Sessions)

    def rotate(self) -> RotationResult:
        """Request a rotation action.

        Acceptance does not mean that the carrier assigned a different public
        IP. Inspect ``ip_changed`` and the response message.
        """

        return self._post_model("/v1/rotate", RotationResult)

    def check_ip(self) -> IPCheckResult:
        """Request a fresh public-IP observation."""

        return self._post_model("/v1/check-ip", IPCheckResult)

    def requests_proxies(
        self,
        *,
        host: Optional[str] = None,
        proxy_port: Optional[int] = None,
        username: Optional[str] = None,
        password: Optional[str] = None,
    ) -> Dict[str, str]:
        """Build a requests-style HTTP/HTTPS mapping for this JustProxy host.

        When ``proxy_port`` is omitted, the method fetches ``/v1/status``.
        No third-party package is imported.
        """

        resolved_username, resolved_password = self._proxy_credentials(
            username, password
        )
        resolved_host, resolved_port = self._proxy_target(host, proxy_port)
        return requests_proxy_urls(
            resolved_host,
            resolved_port,
            username=resolved_username,
            password=resolved_password,
        )

    def socks_url(
        self,
        *,
        host: Optional[str] = None,
        proxy_port: Optional[int] = None,
        remote_dns: bool = True,
        username: Optional[str] = None,
        password: Optional[str] = None,
    ) -> str:
        """Build a SOCKS5 URL for this JustProxy host."""

        resolved_username, resolved_password = self._proxy_credentials(
            username, password
        )
        resolved_host, resolved_port = self._proxy_target(host, proxy_port)
        return socks_proxy_url(
            resolved_host,
            resolved_port,
            remote_dns=remote_dns,
            username=resolved_username,
            password=resolved_password,
        )

    def environment(
        self,
        *,
        host: Optional[str] = None,
        proxy_port: Optional[int] = None,
        remote_dns: bool = True,
        username: Optional[str] = None,
        password: Optional[str] = None,
        include_lowercase: bool = True,
    ) -> Dict[str, str]:
        """Build conventional proxy environment variables."""

        resolved_username, resolved_password = self._proxy_credentials(
            username, password
        )
        resolved_host, resolved_port = self._proxy_target(host, proxy_port)
        return proxy_environment(
            resolved_host,
            resolved_port,
            remote_dns=remote_dns,
            username=resolved_username,
            password=resolved_password,
            include_lowercase=include_lowercase,
        )

    def _proxy_credentials(
        self,
        username: Optional[str],
        password: Optional[str],
    ) -> Tuple[str, str]:
        if username is not None:
            if not isinstance(username, str) or not username.strip():
                raise ConfigurationError(
                    "proxy username must be a non-empty string"
                )
            resolved_username = username.strip()
        else:
            resolved_username = self.proxy_username
        if resolved_username is None:
            raise ConfigurationError(
                "JustProxy requires proxy authentication; configure "
                "proxy_username on the client or pass username to this method"
            )

        if password is not None:
            if not isinstance(password, str):
                raise ConfigurationError("proxy password must be a string")
            resolved_password = password
        else:
            # Constructor defaults remain in force unless this field is
            # overridden for the individual helper call.
            resolved_password = self.proxy_password
        if resolved_password is None:
            # This covers a per-call username on a control-only client.
            resolved_password = self.token
        return resolved_username, resolved_password

    def _proxy_target(
        self, host: Optional[str], proxy_port: Optional[int]
    ) -> Tuple[str, int]:
        resolved_host = host or urlsplit(self.base_url).hostname
        if resolved_host is None:
            raise ConfigurationError("could not determine the proxy host")
        resolved_port = proxy_port
        if resolved_port is None:
            resolved_port = self.status().proxy_port
        if resolved_port is None:
            raise InvalidResponseError("status response did not contain proxy_port")
        return resolved_host, resolved_port

    def _get_model(self, path: str, model: Type[ModelT]) -> ModelT:
        return self._model(path, model, "GET")

    def _post_model(self, path: str, model: Type[ModelT]) -> ModelT:
        return self._model(path, model, "POST")

    def _model(self, path: str, model: Type[ModelT], method: str) -> ModelT:
        value = self._request_json(path, method)
        if not isinstance(value, Mapping):
            raise InvalidResponseError(
                "{0} returned JSON {1}; expected an object".format(
                    path, type(value).__name__
                )
            )
        try:
            return model.from_dict(value)  # type: ignore[attr-defined,no-any-return]
        except (TypeError, ValueError) as exc:
            raise InvalidResponseError(
                "{0} returned an invalid response: {1}".format(path, exc)
            ) from exc

    def _request_json(self, path: str, method: str) -> Any:
        body = b"{}" if method == "POST" else None
        headers = {
            "Accept": "application/json",
            "Authorization": "Bearer {0}".format(self.token),
            "User-Agent": "justproxy-client/0.1.0",
        }
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                status_code = response.getcode()
                response_body = response.read()
        except HTTPError as exc:
            error_body = exc.read()
            self._raise_api_error(exc.code, error_body)
            raise AssertionError("unreachable")
        except (URLError, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            raise JustProxyConnectionError(
                "could not reach {0}: {1}".format(self.base_url, reason)
            ) from exc

        if not 200 <= status_code < 300:
            self._raise_api_error(status_code, response_body)
        if not response_body:
            raise InvalidResponseError(
                "{0} returned an empty response; expected JSON".format(path)
            )
        try:
            text = response_body.decode("utf-8")
            return json.loads(text)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise InvalidResponseError(
                "{0} returned malformed JSON".format(path)
            ) from exc

    @staticmethod
    def _raise_api_error(status_code: int, body: bytes) -> None:
        text = body.decode("utf-8", errors="replace")
        payload: Any = None
        message = text.strip() or "control API request failed"
        try:
            payload = json.loads(text)
        except (TypeError, json.JSONDecodeError):
            pass
        if isinstance(payload, Mapping):
            for key in ("message", "error", "detail"):
                candidate = payload.get(key)
                if isinstance(candidate, str) and candidate:
                    message = candidate
                    break
        error_type = (
            AuthenticationError if status_code in {401, 403} else APIError
        )
        raise error_type(
            status_code,
            message,
            response_body=text,
            payload=payload,
        )
