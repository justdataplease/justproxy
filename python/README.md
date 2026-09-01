# JustProxy Python client

`justproxy-client` is a typed Python 3.9+ client and command-line interface for
the authenticated JustProxy control API. It uses only the Python standard
library at runtime (`urllib`, `argparse`, `json`, and `dataclasses`).

The control API defaults to `http://127.0.0.1:8283`. Every API request sends the
configured token as `Authorization: Bearer <token>`.

JustProxy's proxy listener always requires a username and password. These are
separate from HTTP Bearer authentication on the control API. The Python client
requires a proxy username before it will produce proxy URLs; when a username is
configured without a separate proxy password, it uses the control token as the
proxy password.

## Install

From a clone of this repository:

```console
python -m pip install ./python
```

Or directly from the public repository after it is pushed:

```console
python -m pip install "git+https://github.com/justdataplease/justproxy.git#subdirectory=python"
```

There are no runtime dependencies. A virtual environment is recommended but
not required.

## Configure the CLI

Set the control API address, Bearer token, and configured proxy username in
environment variables. Keeping credentials out of command history is preferable
to passing them as command-line options. Set `JUSTPROXY_PROXY_PASSWORD` too only
when the proxy password differs from the control token.

PowerShell:

```powershell
$env:JUSTPROXY_BASE_URL = 'http://127.0.0.1:8283'
$env:JUSTPROXY_TOKEN = 'replace-with-the-token-from-the-app'
$env:JUSTPROXY_PROXY_USERNAME = 'replace-with-the-proxy-username'
# Optional when it is different from JUSTPROXY_TOKEN:
# $env:JUSTPROXY_PROXY_PASSWORD = 'replace-with-the-proxy-password'
```

POSIX shells:

```sh
export JUSTPROXY_BASE_URL='http://127.0.0.1:8283'
export JUSTPROXY_TOKEN='replace-with-the-token-from-the-app'
export JUSTPROXY_PROXY_USERNAME='replace-with-the-proxy-username'
# Optional when it is different from JUSTPROXY_TOKEN:
# export JUSTPROXY_PROXY_PASSWORD='replace-with-the-proxy-password'
```

If the Android device is attached over ADB and the control server only listens
on the device, forward the configured control port first:

```console
adb forward tcp:8283 tcp:8283
```

Forward the proxy port too if you intend to use the proxy through ADB. Replace
`8888` with the `proxy_port` returned by `justproxy status`:

```console
adb forward tcp:8888 tcp:8888
```

Keep the control API on loopback or a trusted network. Anyone who has the token
and can reach the control port can invoke its authenticated actions.

## CLI

All data commands print JSON:

```console
justproxy status
justproxy metrics
justproxy ip-history
justproxy sessions
justproxy check-ip
justproxy rotate
```

Global options go before the command:

```console
justproxy --base-url http://192.0.2.10:8283 --timeout 5 status
justproxy --compact metrics
```

`rotate` asks JustProxy to perform the available rotation action. An accepted
request does **not** guarantee that a carrier assigns a different public IP.
Read `ip_changed`, `manual_carrier_reset_required`, and `message` in the result;
when `ip_changed` is `null`, the outcome is not known yet. Use `check-ip` and
then inspect `status` or `ip-history` after completing any requested manual
carrier reset.

Generate authenticated proxy environment values using the host from the control
URL, the `proxy_port` reported by `status`, and the configured proxy username:

```console
justproxy env
justproxy env --format posix
justproxy env --format powershell
```

You can avoid the status call by supplying the connection values explicitly.
This form does not need a control API token when a separate proxy password is
also provided:

```console
justproxy env --host 192.0.2.10 --proxy-port 8888 --username proxy-user --password proxy-password
```

If `JUSTPROXY_TOKEN` is set, `--password` can be omitted and the control token
will be used as the proxy password. `env` fails with a clear error when no proxy
username is available; it never emits an unusable unauthenticated JustProxy URL.
The output itself contains encoded credentials, so handle it as sensitive data.

By default `ALL_PROXY` uses `socks5h://`, which asks the proxy to resolve DNS.
Pass `--local-dns` to emit `socks5://` instead.

## Python API

```python
import os

from justproxy_client import JustProxyClient


client = JustProxyClient(
    token=os.environ["JUSTPROXY_TOKEN"],
    base_url=os.environ.get(
        "JUSTPROXY_BASE_URL", "http://127.0.0.1:8283"
    ),
    proxy_username=os.environ.get("JUSTPROXY_PROXY_USERNAME"),
    proxy_password=os.environ.get("JUSTPROXY_PROXY_PASSWORD"),
)

status = client.status()
print(status.state, status.public_ip, status.active_connections)

metrics = client.metrics()
print(metrics.today_downloaded_bytes)

for entry in client.ip_history().items:
    print(entry.observed_at_ms, entry.ip, entry.changed)

for session in client.sessions().items:
    print(session.protocol, session.target, session.result)

request = client.rotate()
print(request.accepted, request.ip_changed, request.message)

check = client.check_ip()
print(check.accepted, check.message)
```

The response models are typed dataclasses. They also implement read-only-style
mapping access and keep all server fields, including fields added by a newer
server:

```python
status = client.status()
complete_response = status.to_dict()
future_value = status.get("a_field_added_later")
```

### Proxy URL helpers

The app reports one `proxy_port` for its supported proxy protocols. The client
can discover that port from `status` and use the control URL's host. Because
JustProxy always authenticates proxy connections, configure `proxy_username` on
the client. If `proxy_password` is omitted, the control token is used:

```python
http_proxies = client.requests_proxies()
# {'http': 'http://proxy-user:control-token@127.0.0.1:8888',
#  'https': 'http://proxy-user:control-token@127.0.0.1:8888'}

socks_url = client.socks_url()
# 'socks5h://proxy-user:control-token@127.0.0.1:8888'
```

You may omit `proxy_username` when using only control API methods such as
`status()`. Calling `requests_proxies()`, `socks_url()`, or `environment()`
without a constructor username or a per-call `username` raises
`ConfigurationError` instead of returning an unauthenticated URL.

These are strings and dictionaries in formats commonly accepted by HTTP client
libraries; the SDK does not install or import `requests`. If you already use
`requests`, for example, its optional integration is direct:

```python
import requests  # Optional dependency supplied by your application.

response = requests.get(
    "https://api.ipify.org",
    proxies=client.requests_proxies(),
    timeout=20,
)
```

Standalone helpers do not contact the API and remain generic URL builders. Pass
credentials when using them with JustProxy:

```python
import os

from justproxy_client import (
    http_proxy_url,
    proxy_environment,
    requests_proxy_urls,
    socks_proxy_url,
)

credentials = {
    "username": os.environ["JUSTPROXY_PROXY_USERNAME"],
    "password": os.environ.get(
        "JUSTPROXY_PROXY_PASSWORD", os.environ["JUSTPROXY_TOKEN"]
    ),
}
http_url = http_proxy_url("192.0.2.10", 8888, **credentials)
socks_url = socks_proxy_url("192.0.2.10", 8888, **credentials)
proxies = requests_proxy_urls("192.0.2.10", 8888, **credentials)
environment = proxy_environment("192.0.2.10", 8888, **credentials)
```

### API methods

| Method | Request | Return model |
| --- | --- | --- |
| `status()` | `GET /v1/status` | `Status` |
| `metrics()` | `GET /v1/metrics` | `Metrics` |
| `ip_history()` | `GET /v1/ip-history` | `IPHistory` |
| `sessions()` | `GET /v1/sessions` | `Sessions` |
| `rotate()` | `POST /v1/rotate` | `RotationResult` |
| `check_ip()` | `POST /v1/check-ip` | `IPCheckResult` |

POST methods send an empty JSON object. The SDK intentionally does not retry
mutating requests, so a network error cannot silently duplicate an action.

### Errors

All SDK-specific exceptions inherit from `JustProxyError`:

- `ConfigurationError`: invalid URL, token, timeout, proxy credentials, host,
  or port
- `JustProxyConnectionError`: connection, DNS, TLS, or timeout failure
- `AuthenticationError`: HTTP 401 or 403
- `APIError`: another non-2xx response; inspect `status_code`, `message`, and
  `payload`
- `InvalidResponseError`: malformed JSON or an incompatible response shape

Control API redirects are rejected so the Bearer token cannot be forwarded to a
different redirect target. Control calls also ignore system proxy environment
variables.

## Test

The tests use a local in-process fake HTTP server; no Android device or network
access is needed:

```console
cd python
python -m unittest discover -s tests -v
```

If `pytest` is already installed, the same suite also runs with:

```console
cd python
python -m pytest
```
