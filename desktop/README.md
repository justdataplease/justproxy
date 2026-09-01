# JustProxy Desktop

JustProxy Desktop is a zero-runtime-dependency Windows companion for the
JustProxy Android app. It uses Python's built-in Tkinter UI and the sibling
`justproxy-client` SDK. It can show proxy state and traffic counters, request a
fresh public-IP check, ask the phone to reconnect active proxy sessions, and
paste the Android app's setup block or copy authenticated HTTP or SOCKS5 proxy
URLs.

The reconnect action can interrupt active clients. It asks the phone to perform
its available rotation/reconnect action, but **it does not guarantee that the
mobile carrier assigns a different public IP**. The phone accepts the request
before its public-IP check completes, so the desktop polls for a newer
observation and keeps unconfirmed outcomes explicit.

## Run from the repository

Python 3.9 or newer is required. The desktop launcher automatically looks for
the SDK at `..\python\src`, so an editable install is not required when running
from a full repository checkout:

```powershell
py desktop\justproxy_desktop.py
```

If the SDK cannot be found, the UI displays this fallback installation hint:

```powershell
py -m pip install .\python
```

Tkinter and the SDK use only the Python standard library at runtime.

## Connect to the phone

Enter the values displayed by the Android app:

- **Phone host:** the phone's trusted-LAN address, or `127.0.0.1` with ADB
  forwarding.
- **Control port:** normally one above the proxy port (`8283` when the proxy is
  on `8282`).
- **Token / proxy password:** the generated password shown by the app. It is
  masked and is not saved by this companion.
- **Proxy username:** the generated `jp_...` username shown by the app.
- **Proxy port:** normally `8282`; a successful status call updates this field
  if the phone reports a different port.

Use **Copy setup** on the Android phone, transfer that text to the PC clipboard,
then select **Paste phone setup** to fill all five fields at once. The desktop
cross-checks the HTTP, SOCKS5, control API, and token entries before accepting
the block.

The desktop remembers only the host, control port, proxy username, and proxy
port in `%APPDATA%\JustProxy\desktop.json`. It never writes the API token or
proxy password to that file. A missing, unreadable, or malformed settings file
is ignored and the app continues with safe defaults.

For a USB-connected development device, forward both ports before using
`127.0.0.1`:

```powershell
adb forward tcp:8283 tcp:8283
adb forward tcp:8282 tcp:8282
```

For LAN access, enable it in JustProxy and use the phone's LAN address. The
control API is plain HTTP, so use only loopback, ADB forwarding, or a trusted
private network. Do not expose the control or proxy ports to the public
internet.

## Actions and metrics

- **Status** retrieves current state, public IP, and active connections.
- **Check IP** requests a fresh observation. After acceptance, the desktop polls
  status, metrics, and IP history once per second for up to 12 attempts, stopping
  when the phone reports a newer observation.
- **Reconnect sessions** invokes the SDK's `rotate()` request after a warning,
  then performs the same asynchronous polling. Acceptance only means that the
  request was accepted; carrier IP change is not guaranteed.
- **Refresh** retrieves both status and traffic metrics.
- **Paste phone setup** validates the clipboard block created by Android's
  **Copy setup** action and fills the connection form.
- **Copy HTTP setup** copies an `http://` proxy URL.
- **Copy SOCKS5 setup** copies a `socks5h://` URL, so DNS is resolved through
  the phone.

The copy actions require the proxy username. Their URL contains that username
and the token as its proxy password. Treat the clipboard as sensitive and clear
it after configuring your client. Copied credential values are never written to
the activity log. If no fresh IP observation arrives within the polling window,
the activity log asks you to use **Refresh** later. JustProxy Desktop holds a
pasted token only in process memory and excludes it from the settings file;
clipboard history or synchronization may retain the original setup text until
you clear it.

Traffic is displayed as upload, download, and total for the current run, local
day, and lifetime. Units are IEC units (KiB, MiB, GiB). The app also shows
lifetime sessions and observed public-IP changes.

## Tests

The helper tests require no GUI, phone, network, or third-party package:

```powershell
py -m unittest discover -s desktop\tests -v
```

## Build the Windows executable

PyInstaller is a build-time tool only; the resulting executable bundles Python,
Tkinter, this UI, and the sibling SDK. From `desktop`:

```powershell
.\build.ps1 -InstallPyInstaller
```

Later builds can omit the install switch:

```powershell
.\build.ps1
```

The one-file, windowed executable is written to:

```text
desktop\dist\JustProxyDesktop.exe
```

That executable can be attached as a release asset. Test it on a clean Windows
machine and verify both ADB-forwarded and trusted-LAN connection paths before a
public release. The bundled alpha executable in the repository is unsigned and
may trigger Windows SmartScreen; verify its SHA-256 checksum before running it.
