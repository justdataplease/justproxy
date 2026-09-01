"""Command-line interface for the JustProxy control API."""

import argparse
import json
import os
import shlex
import sys
from typing import Any, Dict, List, Optional, Sequence
from urllib.parse import urlsplit

from .client import DEFAULT_BASE_URL, DEFAULT_TIMEOUT, JustProxyClient
from .exceptions import ConfigurationError, JustProxyError
from .helpers import proxy_environment
from .models import JsonModel


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="justproxy",
        description="Control JustProxy and print machine-readable JSON.",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("JUSTPROXY_BASE_URL", DEFAULT_BASE_URL),
        help="control API URL (env: JUSTPROXY_BASE_URL)",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("JUSTPROXY_TOKEN"),
        help="Bearer token (prefer env: JUSTPROXY_TOKEN)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help="request timeout in seconds (default: %(default)s)",
    )
    parser.add_argument(
        "--compact", action="store_true", help="print compact JSON"
    )
    parser.add_argument("--version", action="version", version="%(prog)s 0.2.0b1")

    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="show service and current-IP status")
    commands.add_parser("metrics", help="show traffic and session counters")
    commands.add_parser(
        "rotate",
        help="request rotation (a different carrier IP is not guaranteed)",
    )
    commands.add_parser("check-ip", help="request a fresh public-IP check")
    commands.add_parser("ip-history", help="show observed public-IP history")
    commands.add_parser("sessions", help="show recent proxy sessions")

    env_parser = commands.add_parser(
        "env", help="print HTTP(S) and SOCKS proxy environment values"
    )
    env_parser.add_argument(
        "--host",
        help="proxy host (default: host from --base-url)",
    )
    env_parser.add_argument(
        "--proxy-port",
        type=int,
        help="proxy port (default: query /v1/status)",
    )
    env_parser.add_argument(
        "--username",
        default=os.environ.get("JUSTPROXY_PROXY_USERNAME"),
        help="required proxy username (env: JUSTPROXY_PROXY_USERNAME)",
    )
    env_parser.add_argument(
        "--password",
        default=os.environ.get("JUSTPROXY_PROXY_PASSWORD"),
        help=(
            "proxy password; defaults to the control token "
            "(env: JUSTPROXY_PROXY_PASSWORD)"
        ),
    )
    env_parser.add_argument(
        "--local-dns",
        action="store_true",
        help="emit socks5:// instead of socks5h://",
    )
    env_parser.add_argument(
        "--format",
        choices=("json", "posix", "powershell"),
        default="json",
        help="output format (default: %(default)s)",
    )
    env_parser.add_argument(
        "--uppercase-only",
        action="store_true",
        help="omit lowercase environment variable aliases",
    )
    return parser


def _client(args: argparse.Namespace) -> JustProxyClient:
    if not args.token:
        raise ConfigurationError(
            "set JUSTPROXY_TOKEN or pass --token for authenticated API commands"
        )
    return JustProxyClient(args.token, args.base_url, args.timeout)


def _as_json(value: Any) -> Any:
    if isinstance(value, JsonModel):
        return value.to_dict()
    if isinstance(value, tuple):
        return [_as_json(item) for item in value]
    return value


def _print_json(value: Any, compact: bool) -> None:
    if compact:
        print(json.dumps(_as_json(value), separators=(",", ":"), sort_keys=True))
    else:
        print(json.dumps(_as_json(value), indent=2, sort_keys=True))


def _environment(args: argparse.Namespace) -> Dict[str, str]:
    if not args.username or not args.username.strip():
        raise ConfigurationError(
            "JustProxy requires proxy authentication; set "
            "JUSTPROXY_PROXY_USERNAME or pass --username"
        )
    password = args.password if args.password is not None else args.token
    if password is None:
        raise ConfigurationError(
            "set JUSTPROXY_PROXY_PASSWORD, pass --password, or provide the "
            "control token as the proxy-password default"
        )
    host = args.host or urlsplit(args.base_url).hostname
    if not host:
        raise ConfigurationError(
            "could not determine proxy host; pass --host explicitly"
        )
    port = args.proxy_port
    if port is None:
        port = _client(args).status().proxy_port
    if port is None:
        raise ConfigurationError(
            "status did not include proxy_port; pass --proxy-port explicitly"
        )
    return proxy_environment(
        host,
        port,
        remote_dns=not args.local_dns,
        username=args.username.strip(),
        password=password,
        include_lowercase=not args.uppercase_only,
    )


def _quote_powershell(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _print_environment(values: Dict[str, str], output_format: str, compact: bool) -> None:
    if output_format == "json":
        _print_json(values, compact)
    elif output_format == "posix":
        for key, value in values.items():
            print("export {0}={1}".format(key, shlex.quote(value)))
    else:
        for key, value in values.items():
            print("$env:{0} = {1}".format(key, _quote_powershell(value)))


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Run the CLI and return a process exit status."""

    args = _parser().parse_args(argv)
    try:
        if args.command == "env":
            _print_environment(_environment(args), args.format, args.compact)
            return 0

        client = _client(args)
        actions = {
            "status": client.status,
            "metrics": client.metrics,
            "rotate": client.rotate,
            "check-ip": client.check_ip,
            "ip-history": client.ip_history,
            "sessions": client.sessions,
        }
        _print_json(actions[args.command](), args.compact)
        return 0
    except JustProxyError as exc:
        print("justproxy: {0}".format(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("justproxy: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
