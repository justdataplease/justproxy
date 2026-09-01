"""Tkinter desktop companion for the JustProxy Android app."""

from __future__ import annotations

import ctypes
from datetime import datetime
import importlib
from pathlib import Path
import queue
import sys
import threading
import tkinter as tk
from tkinter import messagebox, scrolledtext, ttk
from typing import Any, Callable, Optional

try:
    from .helpers import (
        DEFAULT_SAVED_CONNECTION,
        EM_DASH,
        ConnectionSettings,
        SavedConnectionSettings,
        describe_rotation,
        desktop_config_path,
        format_count,
        format_traffic,
        latest_observation_ms,
        load_saved_connection,
        parse_android_setup,
        save_saved_connection,
    )
except ImportError:
    from helpers import (  # type: ignore[no-redef]
        DEFAULT_SAVED_CONNECTION,
        EM_DASH,
        ConnectionSettings,
        SavedConnectionSettings,
        describe_rotation,
        desktop_config_path,
        format_count,
        format_traffic,
        latest_observation_ms,
        load_saved_connection,
        parse_android_setup,
        save_saved_connection,
    )


SDK_INSTALL_HINT = (
    "The justproxy-client SDK is unavailable. From the repository root run "
    "'py -m pip install .\\python', then reopen the desktop app."
)


def _load_sdk() -> tuple[Optional[Any], Optional[str]]:
    try:
        return importlib.import_module("justproxy_client"), None
    except ModuleNotFoundError as first_error:
        if first_error.name != "justproxy_client":
            return None, "{0} ({1})".format(SDK_INSTALL_HINT, first_error)

    sibling_source = Path(__file__).resolve().parent.parent / "python" / "src"
    if sibling_source.is_dir():
        sys.path.insert(0, str(sibling_source))
        try:
            return importlib.import_module("justproxy_client"), None
        except Exception as second_error:
            return None, "{0} ({1})".format(SDK_INSTALL_HINT, second_error)
    return None, SDK_INSTALL_HINT


SDK, SDK_ERROR = _load_sdk()


class JustProxyDesktop:
    POLL_MILLIS = 100
    REQUEST_TIMEOUT_SECONDS = 6.0
    IP_RESULT_POLL_MILLIS = 1000
    IP_RESULT_POLL_ATTEMPTS = 12

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.closed = False
        self.busy = False
        self.results: "queue.Queue[tuple[str, Callable[[Any], None], Any, Optional[BaseException]]]" = queue.Queue()
        self.network_buttons: list[ttk.Button] = []

        try:
            self.config_path: Optional[Path] = desktop_config_path()
            saved_connection, config_warning = load_saved_connection(self.config_path)
        except Exception as error:
            self.config_path = None
            saved_connection = DEFAULT_SAVED_CONNECTION
            detail = " ".join(str(error).split()) or error.__class__.__name__
            config_warning = "Saved connection settings are unavailable: {0}".format(detail)

        self.host_var = tk.StringVar(value=saved_connection.host)
        self.control_port_var = tk.StringVar(value=str(saved_connection.control_port))
        self.token_var = tk.StringVar()
        self.proxy_username_var = tk.StringVar(value=saved_connection.proxy_username)
        self.proxy_port_var = tk.StringVar(value=str(saved_connection.proxy_port))
        self.show_token_var = tk.BooleanVar(value=False)

        self.state_var = tk.StringVar(value=EM_DASH)
        self.public_ip_var = tk.StringVar(value=EM_DASH)
        self.active_connections_var = tk.StringVar(value=EM_DASH)
        self.run_traffic_var = tk.StringVar(value=EM_DASH)
        self.today_traffic_var = tk.StringVar(value=EM_DASH)
        self.lifetime_traffic_var = tk.StringVar(value=EM_DASH)
        self.ip_changes_var = tk.StringVar(value=EM_DASH)
        self.lifetime_sessions_var = tk.StringVar(value=EM_DASH)
        self.phone_message_var = tk.StringVar(value="Not connected")

        self._configure_window()
        self._configure_styles()
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(self.POLL_MILLIS, self._poll_results)
        self._log("Ready. Enter the connection values shown by the JustProxy Android app.")
        if config_warning:
            self._log(config_warning)
        if SDK_ERROR:
            self._log(SDK_ERROR)
            self.root.after(250, lambda: messagebox.showerror("SDK unavailable", SDK_ERROR))

    def _configure_window(self) -> None:
        self.root.title("JustProxy Desktop")
        self.root.geometry("1040x720")
        self.root.minsize(900, 640)
        self.root.configure(background="#f3f5f7")

    def _configure_styles(self) -> None:
        style = ttk.Style(self.root)
        if "clam" in style.theme_names():
            style.theme_use("clam")
        style.configure("App.TFrame", background="#f3f5f7")
        style.configure(
            "Header.TLabel",
            background="#f3f5f7",
            foreground="#15263b",
            font=("Segoe UI Semibold", 21),
        )
        style.configure(
            "Subtitle.TLabel",
            background="#f3f5f7",
            foreground="#53657a",
            font=("Segoe UI", 9),
        )
        style.configure("TLabelframe", background="#ffffff", borderwidth=1)
        style.configure(
            "TLabelframe.Label",
            background="#ffffff",
            foreground="#23364d",
            font=("Segoe UI Semibold", 10),
        )
        style.configure("White.TFrame", background="#ffffff")
        style.configure("Field.TLabel", background="#ffffff", foreground="#53657a")
        style.configure(
            "MetricName.TLabel", background="#ffffff", foreground="#607087"
        )
        style.configure(
            "MetricValue.TLabel",
            background="#ffffff",
            foreground="#15263b",
            font=("Segoe UI Semibold", 10),
        )
        style.configure(
            "State.TLabel",
            background="#ffffff",
            foreground="#5c6b7a",
            font=("Segoe UI Semibold", 10),
        )
        style.configure("Running.State.TLabel", foreground="#087f5b", background="#ffffff")
        style.configure("Error.State.TLabel", foreground="#c92a2a", background="#ffffff")
        style.configure("Accent.TButton", font=("Segoe UI Semibold", 9))
        style.configure("Caution.TButton", foreground="#9c4f00")

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, style="App.TFrame", padding=(22, 16, 22, 14))
        outer.grid(row=0, column=0, sticky="nsew")
        self.root.rowconfigure(0, weight=1)
        self.root.columnconfigure(0, weight=1)
        outer.columnconfigure(0, weight=1)
        outer.rowconfigure(4, weight=1)

        ttk.Label(outer, text="JustProxy Desktop", style="Header.TLabel").grid(
            row=0, column=0, sticky="w"
        )
        ttk.Label(
            outer,
            text="Control your phone proxy and inspect traffic without exposing traffic contents.",
            style="Subtitle.TLabel",
        ).grid(row=1, column=0, sticky="w", pady=(0, 12))

        connection = ttk.LabelFrame(outer, text="Connection", padding=12)
        connection.grid(row=2, column=0, sticky="ew", pady=(0, 10))
        field_specs = (
            ("Phone host", self.host_var, 20, None, 3),
            ("Control port", self.control_port_var, 9, None, 1),
            ("Token / proxy password", self.token_var, 25, "*", 3),
            ("Proxy username", self.proxy_username_var, 17, None, 2),
            ("Proxy port", self.proxy_port_var, 9, None, 1),
        )
        for column, (label, variable, width, show, weight) in enumerate(field_specs):
            field_frame = ttk.Frame(connection, style="White.TFrame")
            field_frame.grid(row=0, column=column, sticky="ew", padx=(0, 10 if column < 4 else 0))
            connection.columnconfigure(column, weight=weight)
            ttk.Label(field_frame, text=label, style="Field.TLabel").pack(anchor="w")
            entry = ttk.Entry(field_frame, textvariable=variable, width=width, show=show or "")
            entry.pack(fill="x", pady=(3, 0))
            if variable is self.token_var:
                self.token_entry = entry
        ttk.Checkbutton(
            connection,
            text="Show token",
            variable=self.show_token_var,
            command=self._toggle_token,
        ).grid(row=1, column=2, sticky="w", pady=(7, 0))

        middle = ttk.Frame(outer, style="App.TFrame")
        middle.grid(row=3, column=0, sticky="ew", pady=(0, 10))
        middle.columnconfigure(0, weight=1)

        actions = ttk.Frame(middle, style="App.TFrame")
        actions.grid(row=0, column=0, sticky="w")
        self._network_button(actions, "Status", self.get_status).pack(side="left", padx=(0, 7))
        self._network_button(actions, "Check IP", self.check_ip).pack(side="left", padx=(0, 7))
        self._network_button(
            actions, "Reconnect sessions", self.reconnect_sessions, "Caution.TButton"
        ).pack(side="left", padx=(0, 7))
        self._network_button(actions, "Refresh", self.refresh_all, "Accent.TButton").pack(
            side="left"
        )

        setup_actions = ttk.Frame(middle, style="App.TFrame")
        setup_actions.grid(row=0, column=1, sticky="e")
        ttk.Button(setup_actions, text="Paste phone setup", command=self.paste_setup).pack(
            side="left", padx=(0, 7)
        )
        ttk.Button(setup_actions, text="Copy HTTP setup", command=self.copy_http).pack(
            side="left", padx=(0, 7)
        )
        ttk.Button(setup_actions, text="Copy SOCKS5 setup", command=self.copy_socks).pack(
            side="left"
        )

        content = ttk.Panedwindow(outer, orient=tk.VERTICAL)
        content.grid(row=4, column=0, sticky="nsew")

        metrics = ttk.LabelFrame(content, text="Live overview", padding=12)
        metrics.columnconfigure(1, weight=1)
        metrics.columnconfigure(3, weight=2)
        content.add(metrics, weight=0)
        self._metric_row(metrics, 0, "State", self.state_var, "Run traffic", self.run_traffic_var)
        self._metric_row(
            metrics, 1, "Public IP", self.public_ip_var, "Today traffic", self.today_traffic_var
        )
        self._metric_row(
            metrics,
            2,
            "Active connections",
            self.active_connections_var,
            "Lifetime traffic",
            self.lifetime_traffic_var,
        )
        self._metric_row(
            metrics,
            3,
            "IP changes",
            self.ip_changes_var,
            "Lifetime sessions",
            self.lifetime_sessions_var,
        )
        ttk.Separator(metrics).grid(row=4, column=0, columnspan=4, sticky="ew", pady=(7, 6))
        ttk.Label(metrics, text="Phone message", style="MetricName.TLabel").grid(
            row=5, column=0, sticky="nw", padx=(0, 10)
        )
        ttk.Label(
            metrics,
            textvariable=self.phone_message_var,
            style="MetricValue.TLabel",
            wraplength=760,
        ).grid(row=5, column=1, columnspan=3, sticky="w")

        log_frame = ttk.LabelFrame(content, text="Activity", padding=(10, 8, 10, 10))
        content.add(log_frame, weight=1)
        log_frame.rowconfigure(0, weight=1)
        log_frame.columnconfigure(0, weight=1)
        self.log_text = scrolledtext.ScrolledText(
            log_frame,
            height=14,
            wrap=tk.WORD,
            state=tk.DISABLED,
            borderwidth=0,
            background="#0f1d2d",
            foreground="#dce7f3",
            insertbackground="#dce7f3",
            font=("Consolas", 9),
            padx=9,
            pady=8,
        )
        self.log_text.grid(row=0, column=0, sticky="nsew")

        ttk.Label(
            outer,
            text=(
                "Reconnect interrupts active proxy sessions. Carrier-assigned public IP changes "
                "are not guaranteed. Use the control token only on loopback or a trusted network."
            ),
            style="Subtitle.TLabel",
            wraplength=980,
        ).grid(row=5, column=0, sticky="w", pady=(9, 0))

    def _network_button(
        self,
        parent: ttk.Frame,
        text: str,
        command: Callable[[], None],
        style: Optional[str] = None,
    ) -> ttk.Button:
        button = ttk.Button(parent, text=text, command=command, style=style or "TButton")
        self.network_buttons.append(button)
        return button

    def _metric_row(
        self,
        parent: ttk.LabelFrame,
        row: int,
        left_name: str,
        left_variable: tk.StringVar,
        right_name: str,
        right_variable: tk.StringVar,
    ) -> None:
        ttk.Label(parent, text=left_name, style="MetricName.TLabel").grid(
            row=row, column=0, sticky="w", padx=(0, 10), pady=3
        )
        left_style = "State.TLabel" if left_variable is self.state_var else "MetricValue.TLabel"
        left_label = ttk.Label(parent, textvariable=left_variable, style=left_style)
        left_label.grid(row=row, column=1, sticky="w", padx=(0, 24), pady=3)
        if left_variable is self.state_var:
            self.state_label = left_label
        ttk.Label(parent, text=right_name, style="MetricName.TLabel").grid(
            row=row, column=2, sticky="w", padx=(0, 10), pady=3
        )
        ttk.Label(parent, textvariable=right_variable, style="MetricValue.TLabel").grid(
            row=row, column=3, sticky="w", pady=3
        )

    def _toggle_token(self) -> None:
        self.token_entry.configure(show="" if self.show_token_var.get() else "*")

    def _settings(self) -> ConnectionSettings:
        return ConnectionSettings.from_strings(
            self.host_var.get(),
            self.control_port_var.get(),
            self.token_var.get(),
            self.proxy_username_var.get(),
            self.proxy_port_var.get(),
        )

    def _client(self, settings: ConnectionSettings) -> Any:
        if SDK is None:
            raise RuntimeError(SDK_ERROR or SDK_INSTALL_HINT)
        return SDK.JustProxyClient(
            token=settings.token,
            base_url=settings.base_url,
            timeout=self.REQUEST_TIMEOUT_SECONDS,
        )

    def _validated_settings(self) -> Optional[ConnectionSettings]:
        try:
            settings = self._settings()
        except ValueError as error:
            self._log("Configuration error: {0}".format(error))
            messagebox.showerror("Invalid connection settings", str(error))
            return None
        self._persist_saved_connection(
            SavedConnectionSettings(
                host=settings.host,
                control_port=settings.control_port,
                proxy_username=settings.proxy_username,
                proxy_port=settings.proxy_port,
            )
        )
        return settings

    def _persist_saved_connection(self, settings: SavedConnectionSettings) -> None:
        if self.config_path is None:
            return
        try:
            save_saved_connection(self.config_path, settings)
        except Exception as error:
            detail = " ".join(str(error).split()) or error.__class__.__name__
            self._log(
                "Could not save non-secret connection settings; continuing without "
                "saving: {0}".format(detail)
            )

    def paste_setup(self) -> None:
        try:
            clipboard_text = self.root.clipboard_get()
        except tk.TclError:
            message = "The clipboard does not contain text."
            self._log("Paste phone setup failed: {0}".format(message))
            messagebox.showerror("Could not paste setup", message)
            return

        try:
            setup = parse_android_setup(clipboard_text)
        except ValueError as error:
            self._log("Paste phone setup failed: {0}".format(error))
            messagebox.showerror("Invalid JustProxy setup", str(error))
            return

        self.host_var.set(setup.host)
        self.control_port_var.set(str(setup.control_port))
        self.token_var.set(setup.token)
        self.proxy_username_var.set(setup.proxy_username)
        self.proxy_port_var.set(str(setup.proxy_port))
        self._persist_saved_connection(
            SavedConnectionSettings(
                host=setup.host,
                control_port=setup.control_port,
                proxy_username=setup.proxy_username,
                proxy_port=setup.proxy_port,
            )
        )
        self._log(
            "Phone setup pasted. Host, ports, and username may be saved; "
            "the token remains in memory only."
        )

    def get_status(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return
        self._submit(
            "Status",
            lambda: self._client(settings).status(),
            self._apply_status,
        )

    def refresh_all(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return

        def request() -> tuple[Any, Any]:
            client = self._client(settings)
            return client.status(), client.metrics()

        self._submit("Refresh", request, self._apply_refresh)

    def check_ip(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return

        def request() -> tuple[Any, ConnectionSettings, int]:
            client = self._client(settings)
            baseline = latest_observation_ms(client.ip_history())
            return client.check_ip(), settings, baseline

        self._submit(
            "Public-IP check",
            request,
            self._after_ip_check,
        )

    def reconnect_sessions(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return
        confirmed = messagebox.askyesno(
            "Reconnect proxy sessions",
            "Reconnect active proxy sessions? Connected clients may be interrupted.\n\n"
            "The carrier may keep the same public IP.",
            icon="warning",
        )
        if not confirmed:
            self._log("Reconnect cancelled.")
            return

        def request() -> tuple[Any, ConnectionSettings, int]:
            client = self._client(settings)
            baseline = latest_observation_ms(client.ip_history())
            return client.rotate(), settings, baseline

        self._submit(
            "Reconnect sessions",
            request,
            self._after_reconnect,
        )

    def _apply_status(self, status: Any) -> None:
        state = status.state or "UNKNOWN"
        self.state_var.set(state.upper())
        self.public_ip_var.set(status.public_ip or EM_DASH)
        self.active_connections_var.set(format_count(status.active_connections))
        self.phone_message_var.set(status.message or "Status received")
        if isinstance(status.proxy_port, int) and 1 <= status.proxy_port <= 65535:
            self.proxy_port_var.set(str(status.proxy_port))
        state_upper = state.upper()
        if state_upper == "RUNNING":
            self.state_label.configure(style="Running.State.TLabel")
        elif state_upper == "ERROR":
            self.state_label.configure(style="Error.State.TLabel")
        else:
            self.state_label.configure(style="State.TLabel")
        self._log("Status updated: {0}.".format(state_upper))

    def _apply_metrics(self, metrics: Any) -> None:
        self.run_traffic_var.set(
            format_traffic(metrics.run_uploaded_bytes, metrics.run_downloaded_bytes)
        )
        self.today_traffic_var.set(
            format_traffic(metrics.today_uploaded_bytes, metrics.today_downloaded_bytes)
        )
        self.lifetime_traffic_var.set(
            format_traffic(metrics.lifetime_uploaded_bytes, metrics.lifetime_downloaded_bytes)
        )
        self.ip_changes_var.set(format_count(metrics.ip_change_count))
        self.lifetime_sessions_var.set(format_count(metrics.lifetime_sessions))

    def _apply_refresh(self, result: tuple[Any, Any]) -> None:
        status, metrics = result
        self._apply_status(status)
        self._apply_metrics(metrics)
        self._log("Traffic metrics refreshed.")

    def _after_ip_check(self, payload: tuple[Any, ConnectionSettings, int]) -> None:
        result, settings, baseline = payload
        if result.accepted:
            message = result.message or "The phone accepted a fresh public-IP check."
            self._log("Public-IP check accepted. {0}".format(" ".join(message.split())))
            self.phone_message_var.set(message)
            self._schedule_ip_result_poll(settings, baseline)
        else:
            message = result.message or "The phone did not accept the public-IP check."
            self._log(message)
            self.phone_message_var.set(message)

    def _after_reconnect(self, payload: tuple[Any, ConnectionSettings, int]) -> None:
        result, settings, baseline = payload
        description = describe_rotation(
            result.accepted,
            result.ip_changed,
            result.manual_carrier_reset_required,
            result.message,
        )
        self._log(description)
        self.phone_message_var.set(description)
        if result.accepted:
            self._schedule_ip_result_poll(settings, baseline)

    def _schedule_ip_result_poll(
        self,
        settings: ConnectionSettings,
        baseline_observed_at_ms: int,
        attempts_remaining: Optional[int] = None,
    ) -> None:
        remaining = (
            self.IP_RESULT_POLL_ATTEMPTS
            if attempts_remaining is None
            else attempts_remaining
        )
        self.root.after(
            self.IP_RESULT_POLL_MILLIS,
            lambda: self._poll_ip_result(settings, baseline_observed_at_ms, remaining),
        )

    def _poll_ip_result(
        self,
        settings: ConnectionSettings,
        baseline_observed_at_ms: int,
        attempts_remaining: int,
    ) -> None:
        if self.closed:
            return
        if self.busy:
            self._schedule_ip_result_poll(
                settings, baseline_observed_at_ms, attempts_remaining
            )
            return

        def request() -> tuple[Any, Any, Any]:
            client = self._client(settings)
            return client.status(), client.metrics(), client.ip_history()

        def apply(result: tuple[Any, Any, Any]) -> None:
            status, metrics, history = result
            self._apply_status(status)
            self._apply_metrics(metrics)
            if latest_observation_ms(history) > baseline_observed_at_ms:
                self._log("Fresh public-IP observation received from the phone.")
                return
            if attempts_remaining > 1:
                self._schedule_ip_result_poll(
                    settings, baseline_observed_at_ms, attempts_remaining - 1
                )
            else:
                self._log(
                    "The phone has not reported a fresh public-IP observation yet; "
                    "use Refresh to check again."
                )

        self._submit("IP result", request, apply)

    def copy_http(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return
        try:
            client = self._client(settings)
            proxies = client.requests_proxies(
                host=settings.host,
                proxy_port=settings.proxy_port,
                username=settings.proxy_username or None,
                password=settings.token if settings.proxy_username else None,
            )
            self._copy_to_clipboard(proxies["http"], "HTTP proxy URL")
        except Exception as error:
            self._show_local_error("Copy HTTP setup", error)

    def copy_socks(self) -> None:
        settings = self._validated_settings()
        if settings is None:
            return
        try:
            client = self._client(settings)
            socks_url = client.socks_url(
                host=settings.host,
                proxy_port=settings.proxy_port,
                remote_dns=True,
                username=settings.proxy_username or None,
                password=settings.token if settings.proxy_username else None,
            )
            self._copy_to_clipboard(socks_url, "SOCKS5 proxy URL")
        except Exception as error:
            self._show_local_error("Copy SOCKS5 setup", error)

    def _copy_to_clipboard(self, value: str, label: str) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        self.root.update_idletasks()
        auth_note = "" if self.proxy_username_var.get().strip() else " (without credentials)"
        self._log("{0} copied to the clipboard{1}; the value itself is not logged.".format(
            label, auth_note
        ))

    def _submit(
        self,
        label: str,
        operation: Callable[[], Any],
        on_success: Callable[[Any], None],
    ) -> None:
        if self.busy:
            self._log("Another control request is still running.")
            return
        if SDK is None:
            self._show_local_error(label, RuntimeError(SDK_ERROR or SDK_INSTALL_HINT))
            return
        self.busy = True
        self._set_network_buttons_enabled(False)
        self._log("{0}: contacting the phone...".format(label))

        def worker() -> None:
            try:
                result = operation()
                self.results.put((label, on_success, result, None))
            except BaseException as error:
                self.results.put((label, on_success, None, error))

        threading.Thread(
            target=worker,
            name="justproxy-desktop-request",
            daemon=True,
        ).start()

    def _poll_results(self) -> None:
        if self.closed:
            return
        try:
            while True:
                label, on_success, result, error = self.results.get_nowait()
                self.busy = False
                self._set_network_buttons_enabled(True)
                if error is not None:
                    self._show_local_error(label, error)
                else:
                    try:
                        on_success(result)
                    except Exception as callback_error:
                        self._show_local_error(label, callback_error)
        except queue.Empty:
            pass
        self.root.after(self.POLL_MILLIS, self._poll_results)

    def _set_network_buttons_enabled(self, enabled: bool) -> None:
        state = tk.NORMAL if enabled else tk.DISABLED
        for button in self.network_buttons:
            button.configure(state=state)

    def _show_local_error(self, label: str, error: BaseException) -> None:
        message = self._friendly_error(error)
        self.phone_message_var.set(message)
        self._log("{0} failed: {1}".format(label, message))

    @staticmethod
    def _friendly_error(error: BaseException) -> str:
        if SDK is not None:
            if isinstance(error, SDK.AuthenticationError):
                return "Authentication failed. Check the token shown in the Android app."
            if isinstance(error, SDK.JustProxyConnectionError):
                return (
                    "Could not reach the phone. Check its address, the control port, "
                    "LAN/ADB forwarding, and that JustProxy is running."
                )
            if isinstance(error, SDK.ConfigurationError):
                return str(error)
        text = " ".join(str(error).split())
        return text or error.__class__.__name__

    def _log(self, message: str) -> None:
        timestamp = datetime.now().strftime("%H:%M:%S")
        compact = " ".join(str(message).split())
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.insert(tk.END, "[{0}] {1}\n".format(timestamp, compact))
        line_count = int(self.log_text.index("end-1c").split(".")[0])
        if line_count > 500:
            self.log_text.delete("1.0", "51.0")
        self.log_text.see(tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def close(self) -> None:
        try:
            saved_connection = SavedConnectionSettings.from_strings(
                self.host_var.get(),
                self.control_port_var.get(),
                self.proxy_username_var.get(),
                self.proxy_port_var.get(),
            )
        except ValueError:
            pass
        else:
            self._persist_saved_connection(saved_connection)
        self.closed = True
        self.root.destroy()


def main() -> int:
    if sys.platform == "win32":
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (AttributeError, OSError):
            pass
    root = tk.Tk()
    JustProxyDesktop(root)
    root.mainloop()
    return 0


def packaged_self_test() -> int:
    """Exercise bundled SDK and helper imports without opening a window."""
    if SDK is None:
        return 2
    settings = ConnectionSettings.from_strings(
        "127.0.0.1", "8283", "self-test-token", "self-test-user", "8282"
    )
    if settings.base_url != "http://127.0.0.1:8283":
        return 3
    client = SDK.JustProxyClient(token=settings.token, base_url=settings.base_url)
    proxies = client.requests_proxies(
        host=settings.host,
        proxy_port=settings.proxy_port,
        username=settings.proxy_username,
        password=settings.token,
    )
    if not proxies.get("https", "").startswith("http://"):
        return 4
    pasted = parse_android_setup(
        "JustProxy\n"
        "HTTP proxy: http://self-test-user:self-test-token@127.0.0.1:8282\n"
        "SOCKS5 proxy: socks5h://self-test-user:self-test-token@127.0.0.1:8282\n"
        "Control API: http://127.0.0.1:8283\n"
        "API token: self-test-token\n"
        "USB setup: adb forward tcp:8282 tcp:8282"
    )
    return 0 if pasted.proxy_username == "self-test-user" else 5


if __name__ == "__main__":
    raise SystemExit(packaged_self_test() if "--self-test" in sys.argv else main())
