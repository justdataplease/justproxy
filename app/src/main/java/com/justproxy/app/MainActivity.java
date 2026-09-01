package com.justproxy.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.util.List;
import java.util.Locale;

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
    private Button startStopButton;
    private Button rotateButton;
    private Button refreshIpButton;
    private Button regenerateButton;
    private Switch lanAccessSwitch;
    private Switch cellularOnlySwitch;
    private Switch privateDestinationsSwitch;
    private EditText portInput;
    private EditText rotationInput;
    private EditText idleInput;
    private EditText maxConnectionsInput;
    private EditText dataCapInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);
        bindViews();
        loadSettings();
        installActions();
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
        startStopButton = findViewById(R.id.startStopButton);
        rotateButton = findViewById(R.id.rotateButton);
        refreshIpButton = findViewById(R.id.refreshIpButton);
        regenerateButton = findViewById(R.id.regenerateButton);
        lanAccessSwitch = findViewById(R.id.lanAccessSwitch);
        cellularOnlySwitch = findViewById(R.id.cellularOnlySwitch);
        privateDestinationsSwitch = findViewById(R.id.privateDestinationsSwitch);
        portInput = findViewById(R.id.portInput);
        rotationInput = findViewById(R.id.rotationInput);
        idleInput = findViewById(R.id.idleInput);
        maxConnectionsInput = findViewById(R.id.maxConnectionsInput);
        dataCapInput = findViewById(R.id.dataCapInput);
    }

    private void loadSettings() {
        lanAccessSwitch.setChecked(settings.isLanAccessEnabled());
        cellularOnlySwitch.setChecked(settings.isCellularOnly());
        privateDestinationsSwitch.setChecked(settings.isPrivateDestinationAccessEnabled());
        portInput.setText(String.valueOf(settings.getPort()));
        rotationInput.setText(String.valueOf(settings.getRotationMinutes()));
        idleInput.setText(String.valueOf(settings.getIdleTimeoutSeconds()));
        maxConnectionsInput.setText(String.valueOf(settings.getMaxConnections()));
        dataCapInput.setText(String.valueOf(settings.getDataCapMiB()));
        renderCredentials();
        renderEndpoints();
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
                .setTitle("Reconnect proxy sessions?")
                .setMessage("Active TCP sessions will close. This does not force the carrier to assign a new public IP.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reconnect", (dialog, which) ->
                        sendCommand(ProxyService.ACTION_ROTATE, false))
                .show());
        refreshIpButton.setOnClickListener(view ->
                sendCommand(ProxyService.ACTION_REFRESH_IP, false));
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
        lanAccessSwitch.setOnCheckedChangeListener((button, checked) -> renderEndpoints());
        portInput.setOnFocusChangeListener((view, focused) -> {
            if (!focused) renderEndpoints();
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

    private void saveSettings() {
        int port = number(portInput, "Proxy port");
        int rotation = number(rotationInput, "Reconnect interval");
        int idle = number(idleInput, "Idle timeout");
        int max = number(maxConnectionsInput, "Maximum connections");
        long cap = longNumber(dataCapInput, "Data cap");
        requireRange(port, 1024, 65534, "Proxy port");
        requireRange(rotation, 0, 1440, "Reconnect interval");
        requireRange(idle, 10, 3600, "Idle timeout");
        requireRange(max, 1, 64, "Maximum connections");
        if (cap < 0 || cap > 1_048_576L) {
            throw new IllegalArgumentException("Data cap must be 0 to 1048576 MiB");
        }
        settings.setPort(port);
        settings.setRotationMinutes(rotation);
        settings.setIdleTimeoutSeconds(idle);
        settings.setMaxConnections(max);
        settings.setDataCapMiB(cap);
        settings.setLanAccessEnabled(lanAccessSwitch.isChecked());
        settings.setCellularOnly(cellularOnlySwitch.isChecked());
        settings.setPrivateDestinationAccessEnabled(privateDestinationsSwitch.isChecked());
        renderEndpoints();
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

    private void renderStatus(ProxyStatus status) {
        statusChip.setText(status.state.name());
        boolean running = status.state == ProxyStatus.State.RUNNING;
        statusChip.setBackgroundResource(running
                ? R.drawable.status_running : R.drawable.status_stopped);
        statusChip.setTextColor(getColor(running ? R.color.teal_dark : R.color.slate));
        statusMessage.setText(status.message + ("-".equals(status.egress) ? "" : "  |  " + status.egress));
        startStopButton.setText(status.isActive() ? "Stop proxy" : "Start proxy");
        startStopButton.setBackgroundTintList(getColorStateList(status.isActive()
                ? R.color.danger : R.color.teal_dark));
        rotateButton.setEnabled(running);
        refreshIpButton.setEnabled(status.isActive());
        publicIpText.setText(status.publicIp);
        runTrafficText.setText(formatBytes(status.runUploadedBytes + status.runDownloadedBytes));
        todayTrafficText.setText(formatBytes(status.todayUploadedBytes + status.todayDownloadedBytes));
        lifetimeTrafficText.setText(formatBytes(
                status.lifetimeUploadedBytes + status.lifetimeDownloadedBytes));
        activeConnectionsText.setText(status.activeConnections + " active");
        analyticsDetailText.setText("Up " + formatBytes(status.lifetimeUploadedBytes)
                + "  |  Down " + formatBytes(status.lifetimeDownloadedBytes)
                + "  |  " + status.lifetimeSessions + " sessions"
                + "  |  " + status.ipChangeCount + " IP changes");
        setConfigurationEnabled(!status.isActive());
    }

    private void setConfigurationEnabled(boolean enabled) {
        lanAccessSwitch.setEnabled(enabled);
        cellularOnlySwitch.setEnabled(enabled);
        privateDestinationsSwitch.setEnabled(enabled);
        portInput.setEnabled(enabled);
        rotationInput.setEnabled(enabled);
        idleInput.setEnabled(enabled);
        maxConnectionsInput.setEnabled(enabled);
        dataCapInput.setEnabled(enabled);
        regenerateButton.setEnabled(true);
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
                        + "JustProxy records byte counts, client/target metadata, and public-IP observations on this phone. It never records traffic contents or TLS secrets.")
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

    private static String formatBytes(long bytes) {
        if (bytes < 1_000L) return bytes + " B";
        if (bytes < 1_000_000L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1_000d);
        if (bytes < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000d);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000d);
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.post(refreshTask);
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(refreshTask);
        super.onPause();
    }
}
