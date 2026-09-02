package com.justproxy.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.justproxy.app.wireguard.WireGuardPeersActivity;
import com.justproxy.app.shizuku.MobileDataCommandResult;
import com.justproxy.app.shizuku.ShizukuMobileDataController;

import java.util.List;

public final class MainActivity extends Activity {
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            renderStatus(ProxyService.getStatus());
            ticker.postDelayed(this, 1_000);
        }
    };

    private AppSettings settings;
    private TextView statusChip;
    private TextView statusMessage;
    private TextView endpointText;
    private TextView controlEndpointText;
    private TextView usernameText;
    private TextView passwordText;
    private TextView publicIpText;
    private TextView runTrafficText;
    private TextView todayTrafficText;
    private TextView lifetimeTrafficText;
    private TextView activeConnectionsText;
    private TextView analyticsDetailText;
    private TextView wireGuardStatusText;
    private TextView wireGuardEndpointText;
    private TextView wireGuardTrafficText;
    private TextView shizukuStatusText;
    private Button startStopButton;
    private Button rotateButton;
    private Button refreshIpButton;
    private Button regenerateButton;
    private Button shizukuSetupButton;
    private Button shizukuRotateNowButton;
    private Switch lanAccessSwitch;
    private Switch cellularOnlySwitch;
    private Switch privateDestinationsSwitch;
    private Switch wireGuardEnabledSwitch;
    private Switch legacyProxyEnabledSwitch;
    private Switch shizukuRotationSwitch;
    private EditText portInput;
    private EditText wireGuardPortInput;
    private EditText rotationInput;
    private EditText idleInput;
    private EditText maxConnectionsInput;
    private EditText dataCapInput;
    private EditText shizukuIntervalInput;
    private EditText shizukuDataOffInput;
    private ShizukuMobileDataController mobileDataController;
    private ShizukuMobileDataController.Availability mobileDataAvailability;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);
        bindViews();
        loadSettings();
        installActions();
        mobileDataController = new ShizukuMobileDataController(this,
                availability -> {
                    mobileDataAvailability = availability;
                    renderStatus(ProxyService.getStatus());
                });
        mobileDataAvailability = mobileDataController.getAvailability();
        mobileDataController.start();
        requestNotificationPermission();
        if (!settings.hasAcceptedSafetyNotice()) showSafetyNotice(null);
    }

    private void bindViews() {
        statusChip = findViewById(R.id.statusChip);
        statusMessage = findViewById(R.id.statusMessage);
        endpointText = findViewById(R.id.endpointText);
        controlEndpointText = findViewById(R.id.controlEndpointText);
        usernameText = findViewById(R.id.usernameText);
        passwordText = findViewById(R.id.passwordText);
        publicIpText = findViewById(R.id.publicIpText);
        runTrafficText = findViewById(R.id.runTrafficText);
        todayTrafficText = findViewById(R.id.todayTrafficText);
        lifetimeTrafficText = findViewById(R.id.lifetimeTrafficText);
        activeConnectionsText = findViewById(R.id.activeConnectionsText);
        analyticsDetailText = findViewById(R.id.analyticsDetailText);
        wireGuardStatusText = findViewById(R.id.wireGuardStatusText);
        wireGuardEndpointText = findViewById(R.id.wireGuardEndpointText);
        wireGuardTrafficText = findViewById(R.id.wireGuardTrafficText);
        shizukuStatusText = findViewById(R.id.shizukuStatusText);
        startStopButton = findViewById(R.id.startStopButton);
        rotateButton = findViewById(R.id.rotateButton);
        refreshIpButton = findViewById(R.id.refreshIpButton);
        regenerateButton = findViewById(R.id.regenerateButton);
        shizukuSetupButton = findViewById(R.id.shizukuSetupButton);
        shizukuRotateNowButton = findViewById(R.id.shizukuRotateNowButton);
        lanAccessSwitch = findViewById(R.id.lanAccessSwitch);
        cellularOnlySwitch = findViewById(R.id.cellularOnlySwitch);
        privateDestinationsSwitch = findViewById(R.id.privateDestinationsSwitch);
        wireGuardEnabledSwitch = findViewById(R.id.wireGuardEnabledSwitch);
        legacyProxyEnabledSwitch = findViewById(R.id.legacyProxyEnabledSwitch);
        shizukuRotationSwitch = findViewById(R.id.shizukuRotationSwitch);
        portInput = findViewById(R.id.portInput);
        wireGuardPortInput = findViewById(R.id.wireGuardPortInput);
        rotationInput = findViewById(R.id.rotationInput);
        idleInput = findViewById(R.id.idleInput);
        maxConnectionsInput = findViewById(R.id.maxConnectionsInput);
        dataCapInput = findViewById(R.id.dataCapInput);
        shizukuIntervalInput = findViewById(R.id.shizukuIntervalInput);
        shizukuDataOffInput = findViewById(R.id.shizukuDataOffInput);
    }

    private void loadSettings() {
        lanAccessSwitch.setChecked(settings.isLanAccessEnabled());
        cellularOnlySwitch.setChecked(settings.isCellularOnly());
        privateDestinationsSwitch.setChecked(settings.isPrivateDestinationAccessEnabled());
        wireGuardEnabledSwitch.setChecked(settings.isWireGuardEnabled());
        legacyProxyEnabledSwitch.setChecked(settings.isLegacyProxyEnabled());
        shizukuRotationSwitch.setChecked(settings.isShizukuIpRotationEnabled());
        portInput.setText(String.valueOf(settings.getPort()));
        wireGuardPortInput.setText(String.valueOf(settings.getWireGuardPort()));
        rotationInput.setText(String.valueOf(settings.getRotationMinutes()));
        idleInput.setText(String.valueOf(settings.getIdleTimeoutSeconds()));
        maxConnectionsInput.setText(String.valueOf(settings.getMaxConnections()));
        dataCapInput.setText(String.valueOf(settings.getDataCapMiB()));
        shizukuIntervalInput.setText(String.valueOf(
                settings.getShizukuIpRotationIntervalMinutes()));
        shizukuDataOffInput.setText(String.valueOf(
                settings.getShizukuDataOffSeconds()));
        renderCredentials();
        renderEndpoints();
        renderWireGuardEndpoint();
    }

    private void installActions() {
        startStopButton.setOnClickListener(view -> {
            if (ProxyService.getStatus().isActive()) {
                sendCommand(ProxyService.ACTION_STOP, false);
            } else if (!settings.hasAcceptedSafetyNotice()) {
                showSafetyNotice(() -> startProxy());
            } else {
                startProxy();
            }
        });
        rotateButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Reconnect active connections?")
                .setMessage("Active proxy sessions and WireGuard flows will close. This does not force the carrier to assign a new public IP.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reconnect", (dialog, which) ->
                        sendCommand(ProxyService.ACTION_ROTATE, false))
                .show());
        refreshIpButton.setOnClickListener(view ->
                sendCommand(ProxyService.ACTION_REFRESH_IP, false));
        shizukuSetupButton.setOnClickListener(view -> handleShizukuSetup());
        shizukuRotateNowButton.setOnClickListener(view ->
                new AlertDialog.Builder(this)
                        .setTitle("Rotate the carrier IP now?")
                        .setMessage("JustProxy will enable airplane mode, wait for cellular service to disconnect, keep airplane mode on for "
                                + parseOrDefault(shizukuDataOffInput,
                                settings.getShizukuDataOffSeconds())
                                + " second(s), then disable it, reconnect the gateway, and check the real public IP. This interrupts cellular calls/texts and may interrupt Wi-Fi. Phone-hotspot rotation is unsupported. The carrier may return the same IP.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Rotate IP", (dialog, which) ->
                                sendCommand(ProxyService.ACTION_ROTATE_IP, false))
                        .show());
        findViewById(R.id.copyButton).setOnClickListener(view -> copySetup());
        regenerateButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Generate a new secret?")
                .setMessage("The current proxy password and API token will stop working. Active sessions will be closed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Generate", (dialog, which) -> {
                    settings.regenerateCredentials();
                    renderCredentials();
                    if (ProxyService.getStatus().isActive()) {
                        sendCommand(ProxyService.ACTION_RESTART, false);
                    }
                }).show());
        findViewById(R.id.activityButton).setOnClickListener(view ->
                startActivity(new Intent(this, AnalyticsActivity.class)));
        findViewById(R.id.manageWireGuardButton).setOnClickListener(view ->
                openWireGuardPeers());
        lanAccessSwitch.setOnCheckedChangeListener((button, checked) -> renderEndpoints());
        legacyProxyEnabledSwitch.setOnCheckedChangeListener(
                (button, checked) -> renderEndpoints());
        wireGuardEnabledSwitch.setOnCheckedChangeListener(
                (button, checked) -> renderWireGuardEndpoint());
        portInput.setOnFocusChangeListener((view, focused) -> {
            if (!focused) renderEndpoints();
        });
        wireGuardPortInput.setOnFocusChangeListener((view, focused) -> {
            if (!focused) renderWireGuardEndpoint();
        });
    }

    private void startProxy() {
        try {
            saveSettings();
            sendCommand(ProxyService.ACTION_START, true);
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openWireGuardPeers() {
        try {
            if (!ProxyService.getStatus().isActive()) {
                saveSettings();
            }
            startActivity(new Intent(this, WireGuardPeersActivity.class));
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSettings() {
        int port = number(portInput, "Proxy port");
        int wireGuardPort = number(wireGuardPortInput, "WireGuard port");
        int rotation = number(rotationInput, "Reconnect interval");
        int idle = number(idleInput, "Idle timeout");
        int max = number(maxConnectionsInput, "Maximum connections");
        long cap = longNumber(dataCapInput, "Data cap");
        int shizukuInterval = number(
                shizukuIntervalInput, "Automatic IP rotation interval");
        int shizukuDataOff = number(
                shizukuDataOffInput, "Airplane-mode hold time");
        requireRange(port, 1024, 65534, "Proxy port");
        requireRange(wireGuardPort, 1024, 65535, "WireGuard port");
        requireRange(rotation, 0, 1440, "Reconnect interval");
        requireRange(idle, 10, 3600, "Idle timeout");
        requireRange(max, 1, 64, "Maximum connections");
        requireRange(shizukuInterval, 1, 1440,
                "Automatic IP rotation interval");
        requireRange(shizukuDataOff, 1, 10, "Airplane-mode hold time");
        if (cap < 0 || cap > 1_048_576L) {
            throw new IllegalArgumentException("Data cap must be 0 to 1048576 MiB");
        }
        if (!wireGuardEnabledSwitch.isChecked() && !legacyProxyEnabledSwitch.isChecked()) {
            throw new IllegalArgumentException(
                    "Enable WireGuard or the legacy HTTP / SOCKS5 proxy");
        }
        if (wireGuardEnabledSwitch.isChecked()
                && (wireGuardPort == port || wireGuardPort == port + 1)) {
            throw new IllegalArgumentException(
                    "WireGuard port must differ from the proxy and control ports");
        }
        if (shizukuRotationSwitch.isChecked()
                && !cellularOnlySwitch.isChecked()) {
            throw new IllegalArgumentException(
                    "Automatic IP rotation requires cellular-only egress");
        }
        settings.setPort(port);
        settings.setWireGuardPort(wireGuardPort);
        settings.setWireGuardEnabled(wireGuardEnabledSwitch.isChecked());
        settings.setLegacyProxyEnabled(legacyProxyEnabledSwitch.isChecked());
        settings.setRotationMinutes(rotation);
        settings.setShizukuIpRotationEnabled(
                shizukuRotationSwitch.isChecked());
        settings.setShizukuIpRotationIntervalMinutes(shizukuInterval);
        settings.setShizukuDataOffSeconds(shizukuDataOff);
        settings.setIdleTimeoutSeconds(idle);
        settings.setMaxConnections(max);
        settings.setDataCapMiB(cap);
        settings.setLanAccessEnabled(lanAccessSwitch.isChecked());
        settings.setCellularOnly(cellularOnlySwitch.isChecked());
        settings.setPrivateDestinationAccessEnabled(privateDestinationsSwitch.isChecked());
        renderEndpoints();
        renderWireGuardEndpoint();
    }

    private void sendCommand(String action, boolean foreground) {
        Intent intent = new Intent(this, ProxyService.class).setAction(action);
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void renderCredentials() {
        AppSettings.Credentials credentials = settings.getCredentials();
        usernameText.setText(credentials.username);
        passwordText.setText(credentials.password);
    }

    private void renderEndpoints() {
        int port = parseOrDefault(portInput, settings.getPort());
        if (!legacyProxyEnabledSwitch.isChecked()) {
            endpointText.setText("Legacy proxy disabled");
            controlEndpointText.setText("Python control API still uses port " + (port + 1));
            return;
        }
        StringBuilder endpoints = new StringBuilder("USB: 127.0.0.1:").append(port);
        if (lanAccessSwitch.isChecked()) {
            List<NetworkAddresses.LocalAddress> addresses =
                    NetworkAddresses.localIpv4Addresses();
            if (addresses.isEmpty()) {
                endpoints.append("\nHotspot/LAN: no trusted local address detected");
            } else {
                for (NetworkAddresses.LocalAddress address : addresses) {
                    endpoints.append('\n').append(address.getDisplayName())
                            .append(':').append(port);
                }
            }
        }
        endpointText.setText(endpoints);
        controlEndpointText.setText("Control API uses port " + (port + 1)
                + " with the password as its Bearer token");
    }

    private void renderWireGuardEndpoint() {
        int port = parseOrDefault(wireGuardPortInput, settings.getWireGuardPort());
        if (!wireGuardEnabledSwitch.isChecked()) {
            wireGuardEndpointText.setText("Gateway disabled");
            return;
        }
        List<NetworkAddresses.LocalAddress> addresses =
                NetworkAddresses.localIpv4Addresses();
        if (addresses.isEmpty()) {
            wireGuardEndpointText.setText(
                    "Connect the phone to the PC's LAN or enable the phone hotspot");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (NetworkAddresses.LocalAddress address : addresses) {
            if (text.length() > 0) text.append('\n');
            text.append(address.getLabel()).append(": ")
                    .append(address.getAddress()).append(':').append(port).append("/udp");
        }
        wireGuardEndpointText.setText(text);
    }

    private void renderStatus(ProxyStatus status) {
        statusChip.setText(status.state.name());
        boolean running = status.state == ProxyStatus.State.RUNNING;
        statusChip.setBackgroundResource(running
                ? R.drawable.status_running : R.drawable.status_stopped);
        statusChip.setTextColor(getColor(running ? R.color.teal_dark : R.color.slate));
        statusMessage.setText(status.message + ("-".equals(status.egress) ? "" : "  |  " + status.egress));
        startStopButton.setText(status.isActive() ? "Stop JustProxy" : "Start JustProxy");
        startStopButton.setBackgroundTintList(getColorStateList(status.isActive()
                ? R.color.danger : R.color.teal_dark));
        rotateButton.setEnabled(running);
        refreshIpButton.setEnabled(status.isActive());
        publicIpText.setText(status.publicIp);
        runTrafficText.setText(ByteFormatter.formatTotal(
                status.runUploadedBytes, status.runDownloadedBytes));
        todayTrafficText.setText(ByteFormatter.formatTotal(
                status.todayUploadedBytes, status.todayDownloadedBytes));
        lifetimeTrafficText.setText(ByteFormatter.formatTotal(
                status.lifetimeUploadedBytes, status.lifetimeDownloadedBytes));
        activeConnectionsText.setText(status.activeConnections + " active");
        wireGuardStatusText.setText(status.wireGuard.message);
        wireGuardTrafficText.setText(status.wireGuard.activeFlows + " active flows  |  "
                + ByteFormatter.formatTotal(status.wireGuard.uploadedBytes,
                status.wireGuard.downloadedBytes) + " this run");
        analyticsDetailText.setText("Up " + ByteFormatter.format(status.lifetimeUploadedBytes)
                + "  |  Down " + ByteFormatter.format(status.lifetimeDownloadedBytes)
                + "  |  " + status.lifetimeSessions + " sessions"
                + "  |  " + status.ipChangeCount + " IP changes");
        renderShizukuStatus(status);
        setConfigurationEnabled(!status.isActive());
    }

    private void renderShizukuStatus(ProxyStatus status) {
        boolean recoveryRequired = mobileDataController != null
                && mobileDataController.isRecoveryRequired();
        String message;
        if (recoveryRequired) {
            message = status.isActive()
                    ? status.ipRotation.message
                    : mobileDataController.getManualRecoveryInstruction()
                            + ", then retry recovery.";
        } else if (status.isActive()) {
            message = status.ipRotation.message;
        } else if (mobileDataAvailability != null) {
            message = mobileDataAvailability.getMessage();
        } else {
            message = "Checking Shizuku";
        }
        shizukuStatusText.setText(message);

        boolean ready = mobileDataAvailability != null
                && mobileDataAvailability.isReady();
        boolean idle = status.ipRotation.state == IpRotationStatus.State.READY
                || status.ipRotation.state == IpRotationStatus.State.DISABLED
                || status.ipRotation.state == IpRotationStatus.State.ERROR;
        shizukuRotateNowButton.setEnabled(
                status.state == ProxyStatus.State.RUNNING
                        && settings.isCellularOnly()
                        && ready && idle && !recoveryRequired);

        if (recoveryRequired) {
            shizukuSetupButton.setText(status.isActive()
                    && status.ipRotation.state != IpRotationStatus.State.ERROR
                    ? "Recovery in progress" : "Retry recovery");
        } else if (ready) {
            shizukuSetupButton.setText("Test Shizuku");
        } else if (mobileDataAvailability != null
                && (mobileDataAvailability.getState()
                        == ShizukuMobileDataController.State.PERMISSION_REQUIRED
                || mobileDataAvailability.getState()
                        == ShizukuMobileDataController.State.PERMISSION_DENIED)) {
            shizukuSetupButton.setText("Allow JustProxy");
        } else {
            shizukuSetupButton.setText("Set up Shizuku");
        }
        shizukuSetupButton.setEnabled(true);
    }

    private void setConfigurationEnabled(boolean enabled) {
        lanAccessSwitch.setEnabled(enabled);
        cellularOnlySwitch.setEnabled(enabled);
        privateDestinationsSwitch.setEnabled(enabled);
        wireGuardEnabledSwitch.setEnabled(enabled);
        legacyProxyEnabledSwitch.setEnabled(enabled);
        portInput.setEnabled(enabled);
        wireGuardPortInput.setEnabled(enabled);
        rotationInput.setEnabled(enabled);
        idleInput.setEnabled(enabled);
        maxConnectionsInput.setEnabled(enabled);
        dataCapInput.setEnabled(enabled);
        shizukuRotationSwitch.setEnabled(enabled);
        shizukuIntervalInput.setEnabled(enabled);
        shizukuDataOffInput.setEnabled(enabled);
        regenerateButton.setEnabled(true);
    }

    private void handleShizukuSetup() {
        if (mobileDataController == null) return;
        ProxyStatus status = ProxyService.getStatus();
        if (mobileDataController.isRecoveryRequired()) {
            if (status.isActive()) {
                Toast.makeText(this,
                        "Retrying airplane-mode recovery",
                        Toast.LENGTH_LONG).show();
                sendCommand(ProxyService.ACTION_RECOVER_MOBILE_DATA, false);
            } else if (mobileDataController.getAvailability().isReady()) {
                runShizukuOperation(true);
            } else {
                reconcileManualMobileDataRecovery();
            }
            return;
        }
        ShizukuMobileDataController.Availability availability =
                mobileDataController.getAvailability();
        if (availability.isReady()) {
            runShizukuOperation(false);
            return;
        }
        if (availability.getState()
                == ShizukuMobileDataController.State.PERMISSION_REQUIRED
                || availability.getState()
                == ShizukuMobileDataController.State.PERMISSION_DENIED) {
            if (availability.getState()
                    == ShizukuMobileDataController.State.PERMISSION_REQUIRED
                    && !availability.shouldShowPermissionRationale()) {
                mobileDataController.requestPermission();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Allow JustProxy in Shizuku")
                        .setMessage("JustProxy uses Shizuku only for the fixed airplane-mode commands needed by Rotate IP, plus a mobile-data restore command if a rotation from an older version was interrupted. It cannot run arbitrary commands. You can leave automatic rotation disabled and keep using WireGuard normally.")
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Setup guide", (dialog, which) ->
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://shizuku.rikka.app/guide/setup/"))))
                        .setPositiveButton("Request again", (dialog, which) ->
                                mobileDataController.requestPermission())
                        .show();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Set up Shizuku")
                .setMessage("Install and start a current Shizuku with API 13 using Wireless debugging, then return here and tap Retry. JustProxy will ask for permission; root is not required.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Retry", (dialog, which) ->
                        mobileDataController.requestPermission())
                .setPositiveButton("Open setup guide", (dialog, which) ->
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://shizuku.rikka.app/guide/setup/"))))
                .show();
    }

    private void runShizukuOperation(boolean restore) {
        ShizukuMobileDataController.OperationCallback callback =
                new ShizukuMobileDataController.OperationCallback() {
                    @Override public void onResult(MobileDataCommandResult result) {
                        Toast.makeText(MainActivity.this, result.getMessage(),
                                result.isSuccess()
                                        ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                        renderStatus(ProxyService.getStatus());
                    }

                    @Override public void onUnavailable(
                            ShizukuMobileDataController.Availability availability) {
                        mobileDataAvailability = availability;
                        Toast.makeText(MainActivity.this,
                                availability.getMessage(), Toast.LENGTH_LONG).show();
                        renderStatus(ProxyService.getStatus());
                    }

                    @Override public void onError(Throwable error) {
                        String message = error.getMessage();
                        Toast.makeText(MainActivity.this,
                                message == null ? "Shizuku operation failed" : message,
                                Toast.LENGTH_LONG).show();
                        renderStatus(ProxyService.getStatus());
                    }
                };
        if (restore) {
            mobileDataController.restoreAsync(callback);
        } else {
            mobileDataController.probeAsync(callback);
        }
    }

    private void reconcileManualMobileDataRecovery() {
        mobileDataController.reconcileRecoveryAsync(
                new ShizukuMobileDataController.OperationCallback() {
                    @Override public void onResult(MobileDataCommandResult result) {
                        Toast.makeText(MainActivity.this, result.getMessage(),
                                Toast.LENGTH_SHORT).show();
                        renderStatus(ProxyService.getStatus());
                    }

                    @Override public void onUnavailable(
                            ShizukuMobileDataController.Availability availability) {
                        Toast.makeText(MainActivity.this,
                                availability.getMessage(), Toast.LENGTH_LONG).show();
                    }

                    @Override public void onError(Throwable error) {
                        String message = error.getMessage();
                        Toast.makeText(MainActivity.this,
                                message == null
                                        ? mobileDataController.getManualRecoveryInstruction()
                                                + ", then retry recovery"
                                        : message,
                                Toast.LENGTH_LONG).show();
                        renderStatus(ProxyService.getStatus());
                    }
                });
    }

    private void copySetup() {
        try {
            saveSettings();
        } catch (IllegalArgumentException ignored) {
            // Copy the last valid saved values.
        }
        AppSettings.Credentials credentials = settings.getCredentials();
        int port = settings.getPort();
        if (!settings.isLanAccessEnabled()) {
            copySetupForHost(credentials, port, "127.0.0.1", "USB");
            return;
        }

        List<NetworkAddresses.LocalAddress> addresses =
                NetworkAddresses.localIpv4Addresses();
        if (addresses.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No trusted LAN address found")
                    .setMessage("Connect this phone and PC to the same Wi-Fi/hotspot, or use ADB USB forwarding. JustProxy will not guess a cellular or VPN address.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Copy USB setup", (dialog, which) ->
                            copySetupForHost(credentials, port, "127.0.0.1", "USB"))
                    .show();
            return;
        }
        if (addresses.size() == 1) {
            NetworkAddresses.LocalAddress address = addresses.get(0);
            copySetupForHost(credentials, port, address.getAddress(), address.getLabel());
            return;
        }

        String[] choices = new String[addresses.size()];
        for (int index = 0; index < addresses.size(); index++) {
            choices[index] = addresses.get(index).getDisplayName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose the PC connection address")
                .setItems(choices, (dialog, which) -> {
                    NetworkAddresses.LocalAddress address = addresses.get(which);
                    copySetupForHost(
                            credentials, port, address.getAddress(), address.getLabel());
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use USB", (dialog, which) ->
                        copySetupForHost(credentials, port, "127.0.0.1", "USB"))
                .show();
    }

    private void copySetupForHost(AppSettings.Credentials credentials, int port,
                                  String host, String connectionLabel) {
        String text = "JustProxy\n"
                + "HTTP proxy: http://" + credentials.username + ":" + credentials.password
                + "@" + host + ":" + port + "\n"
                + "SOCKS5 proxy: socks5h://" + credentials.username + ":" + credentials.password
                + "@" + host + ":" + port + "\n"
                + "Control API: http://" + host + ":" + (port + 1) + "\n"
                + "API token: " + credentials.password + "\n"
                + "USB setup: adb forward tcp:" + port + " tcp:" + port
                + " && adb forward tcp:" + (port + 1) + " tcp:" + (port + 1);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("JustProxy setup", text));
        Toast.makeText(this, connectionLabel + " connection setup copied",
                Toast.LENGTH_SHORT).show();
    }

    private void showSafetyNotice(Runnable afterAccept) {
        new AlertDialog.Builder(this)
                .setTitle("Before you start JustProxy")
                .setMessage("Use it only for traffic you own or are authorized to route. Mobile data charges, battery use, carrier rules, and destination terms still apply.\n\n"
                        + "JustProxy records byte counts, connection metadata, and public-IP observations on this phone. It never records traffic contents, TLS secrets, or WireGuard private keys in analytics.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("I understand", (dialog, which) -> {
                    settings.setAcceptedSafetyNotice(true);
                    if (afterAccept != null) afterAccept.run();
                }).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private static int number(EditText input, String label) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number");
        }
    }

    private static long longNumber(EditText input, String label) {
        try {
            return Long.parseLong(input.getText().toString().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number");
        }
    }

    private static int parseOrDefault(EditText input, int fallback) {
        try { return Integer.parseInt(input.getText().toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static void requireRange(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(label + " must be " + min + " to " + max);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(refreshTask);
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (mobileDataController != null) mobileDataController.close();
        super.onDestroy();
    }
}
