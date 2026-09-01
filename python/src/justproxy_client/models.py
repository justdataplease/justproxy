"""Typed, forward-compatible models for JustProxy API responses."""

from copy import deepcopy
from dataclasses import dataclass, field
from typing import Any, Dict, Iterator, Mapping, Optional, Tuple


def _optional_str(value: Any) -> Optional[str]:
    return value if isinstance(value, str) else None


def _optional_bool(value: Any) -> Optional[bool]:
    return value if isinstance(value, bool) else None


def _optional_int(value: Any) -> Optional[int]:
    # bool is an int subclass, but it is not a valid count, port, or timestamp.
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _bool(value: Any, default: bool = False) -> bool:
    parsed = _optional_bool(value)
    return default if parsed is None else parsed


def _copy_mapping(value: Mapping[str, Any]) -> Dict[str, Any]:
    return deepcopy(dict(value))


@dataclass(frozen=True)
class JsonModel(Mapping[str, Any]):
    """A typed model that also retains every field returned by the API.

    ``raw`` and :meth:`to_dict` make newer server fields available without
    requiring a matching client release. Mapping access is supported as a
    convenience: ``status["future_field"]``.
    """

    raw: Dict[str, Any] = field(repr=False, compare=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "raw", _copy_mapping(self.raw))

    def __getitem__(self, key: str) -> Any:
        return self.raw[key]

    def __iter__(self) -> Iterator[str]:
        return iter(self.raw)

    def __len__(self) -> int:
        return len(self.raw)

    def to_dict(self) -> Dict[str, Any]:
        """Return a defensive copy of the complete JSON object."""

        return _copy_mapping(self.raw)


@dataclass(frozen=True)
class Status(JsonModel):
    version: Optional[str] = None
    state: Optional[str] = None
    message: Optional[str] = None
    listen_host: Optional[str] = None
    proxy_port: Optional[int] = None
    control_port: Optional[int] = None
    egress: Optional[str] = None
    public_ip: Optional[str] = None
    active_connections: Optional[int] = None
    started_at_ms: Optional[int] = None
    next_rotation_at_ms: Optional[int] = None
    rotation_guarantees_ip_change: bool = False

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "Status":
        return cls(
            raw=dict(value),
            version=_optional_str(value.get("version")),
            state=_optional_str(value.get("state")),
            message=_optional_str(value.get("message")),
            listen_host=_optional_str(value.get("listen_host")),
            proxy_port=_optional_int(value.get("proxy_port")),
            control_port=_optional_int(value.get("control_port")),
            egress=_optional_str(value.get("egress")),
            public_ip=_optional_str(value.get("public_ip")),
            active_connections=_optional_int(value.get("active_connections")),
            started_at_ms=_optional_int(value.get("started_at_ms")),
            next_rotation_at_ms=_optional_int(value.get("next_rotation_at_ms")),
            rotation_guarantees_ip_change=_bool(
                value.get("rotation_guarantees_ip_change"), False
            ),
        )


@dataclass(frozen=True)
class Metrics(JsonModel):
    run_uploaded_bytes: Optional[int] = None
    run_downloaded_bytes: Optional[int] = None
    today_uploaded_bytes: Optional[int] = None
    today_downloaded_bytes: Optional[int] = None
    lifetime_uploaded_bytes: Optional[int] = None
    lifetime_downloaded_bytes: Optional[int] = None
    lifetime_sessions: Optional[int] = None
    ip_change_count: Optional[int] = None

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "Metrics":
        return cls(
            raw=dict(value),
            run_uploaded_bytes=_optional_int(value.get("run_uploaded_bytes")),
            run_downloaded_bytes=_optional_int(value.get("run_downloaded_bytes")),
            today_uploaded_bytes=_optional_int(value.get("today_uploaded_bytes")),
            today_downloaded_bytes=_optional_int(value.get("today_downloaded_bytes")),
            lifetime_uploaded_bytes=_optional_int(
                value.get("lifetime_uploaded_bytes")
            ),
            lifetime_downloaded_bytes=_optional_int(
                value.get("lifetime_downloaded_bytes")
            ),
            lifetime_sessions=_optional_int(value.get("lifetime_sessions")),
            ip_change_count=_optional_int(value.get("ip_change_count")),
        )


@dataclass(frozen=True)
class IPHistoryEntry(JsonModel):
    ip: Optional[str] = None
    observed_at_ms: Optional[int] = None
    changed: Optional[bool] = None

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "IPHistoryEntry":
        return cls(
            raw=dict(value),
            ip=_optional_str(value.get("ip")),
            observed_at_ms=_optional_int(value.get("observed_at_ms")),
            changed=_optional_bool(value.get("changed")),
        )


@dataclass(frozen=True)
class IPHistory(JsonModel):
    items: Tuple[IPHistoryEntry, ...] = ()

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "IPHistory":
        raw_items = value.get("items")
        if not isinstance(raw_items, list):
            raise ValueError("ip-history response field 'items' must be an array")
        parsed = []
        for index, item in enumerate(raw_items):
            if not isinstance(item, Mapping):
                raise ValueError(
                    "ip-history item {0} must be an object".format(index)
                )
            parsed.append(IPHistoryEntry.from_dict(item))
        return cls(raw=dict(value), items=tuple(parsed))


@dataclass(frozen=True)
class Session(JsonModel):
    started_at_ms: Optional[int] = None
    ended_at_ms: Optional[int] = None
    client: Optional[str] = None
    protocol: Optional[str] = None
    target: Optional[str] = None
    uploaded_bytes: Optional[int] = None
    downloaded_bytes: Optional[int] = None
    result: Optional[str] = None

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "Session":
        return cls(
            raw=dict(value),
            started_at_ms=_optional_int(value.get("started_at_ms")),
            ended_at_ms=_optional_int(value.get("ended_at_ms")),
            client=_optional_str(value.get("client")),
            protocol=_optional_str(value.get("protocol")),
            target=_optional_str(value.get("target")),
            uploaded_bytes=_optional_int(value.get("uploaded_bytes")),
            downloaded_bytes=_optional_int(value.get("downloaded_bytes")),
            result=_optional_str(value.get("result")),
        )


@dataclass(frozen=True)
class Sessions(JsonModel):
    items: Tuple[Session, ...] = ()

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "Sessions":
        raw_items = value.get("items")
        if not isinstance(raw_items, list):
            raise ValueError("sessions response field 'items' must be an array")
        parsed = []
        for index, item in enumerate(raw_items):
            if not isinstance(item, Mapping):
                raise ValueError("session item {0} must be an object".format(index))
            parsed.append(Session.from_dict(item))
        return cls(raw=dict(value), items=tuple(parsed))


@dataclass(frozen=True)
class RotationResult(JsonModel):
    accepted: bool = False
    action: Optional[str] = None
    previous_ip: Optional[str] = None
    ip_changed: Optional[bool] = None
    manual_carrier_reset_required: bool = False
    message: Optional[str] = None

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "RotationResult":
        return cls(
            raw=dict(value),
            accepted=_bool(value.get("accepted"), False),
            action=_optional_str(value.get("action")),
            previous_ip=_optional_str(value.get("previous_ip")),
            ip_changed=_optional_bool(value.get("ip_changed")),
            manual_carrier_reset_required=_bool(
                value.get("manual_carrier_reset_required"), False
            ),
            message=_optional_str(value.get("message")),
        )


@dataclass(frozen=True)
class IPCheckResult(JsonModel):
    accepted: bool = False
    message: Optional[str] = None

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "IPCheckResult":
        return cls(
            raw=dict(value),
            accepted=_bool(value.get("accepted"), False),
            message=_optional_str(value.get("message")),
        )
