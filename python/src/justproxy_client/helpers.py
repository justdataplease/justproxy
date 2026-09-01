"""Dependency-free helpers for constructing proxy URLs."""

from typing import Dict, Optional
from urllib.parse import quote

from .exceptions import ConfigurationError


def _authority(
    host: str,
    port: int,
    username: Optional[str],
    password: Optional[str],
) -> str:
    normalized_host = host.strip()
    if not normalized_host:
        raise ConfigurationError("proxy host must not be empty")
    if "/" in normalized_host or "@" in normalized_host:
        raise ConfigurationError("proxy host must be a host name or IP address")
    if normalized_host.startswith("[") and normalized_host.endswith("]"):
        normalized_host = normalized_host[1:-1]
    if ":" in normalized_host:
        normalized_host = "[{0}]".format(normalized_host)

    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ConfigurationError("proxy port must be an integer from 1 to 65535")
    if password is not None and username is None:
        raise ConfigurationError("a proxy password requires a username")

    credentials = ""
    if username is not None:
        credentials = quote(username, safe="")
        if password is not None:
            credentials += ":" + quote(password, safe="")
        credentials += "@"
    return "{0}{1}:{2}".format(credentials, normalized_host, port)


def proxy_url(
    scheme: str,
    host: str,
    port: int,
    *,
    username: Optional[str] = None,
    password: Optional[str] = None,
) -> str:
    """Build an HTTP or SOCKS proxy URL without importing a proxy library."""

    normalized_scheme = scheme.lower()
    if normalized_scheme not in {"http", "https", "socks5", "socks5h"}:
        raise ConfigurationError(
            "proxy scheme must be http, https, socks5, or socks5h"
        )
    return "{0}://{1}".format(
        normalized_scheme, _authority(host, port, username, password)
    )


def http_proxy_url(
    host: str,
    port: int,
    *,
    username: Optional[str] = None,
    password: Optional[str] = None,
) -> str:
    """Return an HTTP proxy URL."""

    return proxy_url(
        "http", host, port, username=username, password=password
    )


def socks_proxy_url(
    host: str,
    port: int,
    *,
    remote_dns: bool = True,
    username: Optional[str] = None,
    password: Optional[str] = None,
) -> str:
    """Return a SOCKS5 URL; ``socks5h`` delegates DNS to the proxy."""

    scheme = "socks5h" if remote_dns else "socks5"
    return proxy_url(
        scheme, host, port, username=username, password=password
    )


def requests_proxy_urls(
    host: str,
    port: int,
    *,
    username: Optional[str] = None,
    password: Optional[str] = None,
) -> Dict[str, str]:
    """Return the proxy mapping expected by requests-style HTTP clients.

    The helper does not import or require the third-party ``requests`` package.
    """

    url = http_proxy_url(
        host, port, username=username, password=password
    )
    return {"http": url, "https": url}


def proxy_environment(
    host: str,
    port: int,
    *,
    remote_dns: bool = True,
    username: Optional[str] = None,
    password: Optional[str] = None,
    include_lowercase: bool = True,
) -> Dict[str, str]:
    """Return conventional HTTP(S) and SOCKS proxy environment variables."""

    http_url = http_proxy_url(
        host, port, username=username, password=password
    )
    socks_url = socks_proxy_url(
        host,
        port,
        remote_dns=remote_dns,
        username=username,
        password=password,
    )
    values = {
        "HTTP_PROXY": http_url,
        "HTTPS_PROXY": http_url,
        "ALL_PROXY": socks_url,
    }
    if include_lowercase:
        values.update({key.lower(): value for key, value in values.items()})
    return values
