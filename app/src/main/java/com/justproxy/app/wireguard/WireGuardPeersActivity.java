package com.justproxy.app.wireguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.justproxy.app.AppSettings;
import com.justproxy.app.NetworkAddresses;
import com.justproxy.app.ProxyService;
import com.justproxy.app.R;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** One-peer beta UI for creating and securely exporting a standard WireGuard profile. */
public final class WireGuardPeersActivity extends Activity {
    private static final int REQUEST_EXPORT_PROFILE = 8201;

    private final List<NetworkAddresses.LocalAddress> endpointAddresses = new ArrayList<>();
    private WireGuardPeerStore peerStore;
    private AppSettings settings;
    private WireGuardPeerRecord currentRecord;
    private TextView statusView;
    private EditText peerNameInput;
    private Spinner endpointSpinner;
    private ArrayAdapter<String> endpointAdapter;
    private Button createButton;
    private Button exportButton;
    private Button revokeButton;
    private String pendingExportConfig;
    private boolean storageError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        peerStore = new WireGuardPeerStore(this);
        settings = new AppSettings(this);
        setContentView(buildContent());
        refreshEndpoints();
        refreshRecord();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.off_white));
        LinearLayout root = column();
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(view -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        TextView title = title("WireGuard PC profile");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(10));
        top.addView(title, titleParams);
        root.addView(top, matchWrap());

        LinearLayout statusCard = card();
        statusCard.addView(title("One-peer beta"), matchWrap());
        TextView explanation = caption(
                "Create one PC identity, then import its .conf file into the official WireGuard "
                        + "app. Regenerating or revoking replaces the only accepted peer.");
        explanation.setPadding(0, dp(6), 0, dp(12));
        statusCard.addView(explanation, matchWrap());
        statusView = text("Checking encrypted peer storage...");
        statusCard.addView(statusView, matchWrap());
        root.addView(statusCard, cardParams());

        LinearLayout profileCard = card();
        profileCard.addView(title("Profile"), matchWrap());
        profileCard.addView(caption("PC profile name"), labelParams());
        peerNameInput = new EditText(this);
        peerNameInput.setSingleLine(true);
        peerNameInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        peerNameInput.setText("JustProxy-PC");
        profileCard.addView(peerNameInput, matchWrap());

        profileCard.addView(caption("Phone endpoint on this LAN / hotspot"), labelParams());
        endpointSpinner = new Spinner(this);
        endpointAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>());
        endpointAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        endpointSpinner.setAdapter(endpointAdapter);
        profileCard.addView(endpointSpinner, matchWrap());

        Button refreshAddresses = new Button(this);
        refreshAddresses.setText("Refresh LAN addresses");
        refreshAddresses.setOnClickListener(view -> refreshEndpoints());
        profileCard.addView(refreshAddresses, matchWrap());

        TextView endpointNote = caption(
                "The selected address and WireGuard UDP port "
                        + settings.getWireGuardPort()
                        + " are written into the exported profile. The PC must be able to reach "
                        + "that phone address.");
        endpointNote.setPadding(0, dp(8), 0, 0);
        profileCard.addView(endpointNote, matchWrap());
        root.addView(profileCard, cardParams());

        LinearLayout actionsCard = card();
        createButton = new Button(this);
        createButton.setText("Create profile");
        createButton.setOnClickListener(view -> createOrRegenerate());
        actionsCard.addView(createButton, matchWrap());

        exportButton = new Button(this);
        exportButton.setText("Export .conf");
        exportButton.setOnClickListener(view -> confirmExport());
        actionsCard.addView(exportButton, topMarginParams());

        TextView secretWarning = caption(
                "Security warning: the exported .conf contains the PC private key. Anyone who "
                        + "gets that file can impersonate this peer. Transfer and store it securely.");
        secretWarning.setTextColor(getColor(R.color.danger));
        secretWarning.setPadding(0, dp(9), 0, dp(4));
        actionsCard.addView(secretWarning, matchWrap());

        revokeButton = new Button(this);
        revokeButton.setText("Revoke peer");
        revokeButton.setTextColor(getColor(R.color.danger));
        revokeButton.setOnClickListener(view -> confirmRevoke());
        actionsCard.addView(revokeButton, topMarginParams());
        root.addView(actionsCard, cardParams());
        return scroll;
    }

    private void refreshEndpoints() {
        String previousAddress = selectedEndpointAddress();
        endpointAddresses.clear();
        endpointAddresses.addAll(NetworkAddresses.localIpv4Addresses());
        endpointAdapter.clear();
        if (endpointAddresses.isEmpty()) {
            endpointAdapter.add("No reachable Wi-Fi / hotspot address found");
        } else {
            for (NetworkAddresses.LocalAddress address : endpointAddresses) {
                endpointAdapter.add(address.getDisplayName());
            }
        }
        endpointAdapter.notifyDataSetChanged();
        if (previousAddress != null) {
            for (int index = 0; index < endpointAddresses.size(); index++) {
                if (previousAddress.equals(endpointAddresses.get(index).getAddress())) {
                    endpointSpinner.setSelection(index);
                    break;
                }
            }
        }
        updateButtons();
    }

    private void refreshRecord() {
        try {
            Optional<WireGuardPeerRecord> loaded = peerStore.load();
            storageError = false;
            currentRecord = loaded.orElse(null);
            if (currentRecord == null) {
                statusView.setText("No PC peer exists yet. Create one before exporting.");
            } else {
                peerNameInput.setText(currentRecord.getPeerName().getValue());
                statusView.setText("Active peer: " + currentRecord.getPeerName().getValue()
                        + "\nCreated: " + formatTime(currentRecord.getCreatedAtMillis())
                        + "\nKeys are encrypted with Android Keystore.");
            }
        } catch (RuntimeException exception) {
            storageError = true;
            currentRecord = null;
            statusView.setText("Encrypted peer storage needs attention: "
                    + safeMessage(exception));
        }
        updateButtons();
    }

    private void createOrRegenerate() {
        final WireGuardProfileName name;
        try {
            name = WireGuardProfileName.of(peerNameInput.getText().toString().trim());
        } catch (IllegalArgumentException exception) {
            showError("Invalid profile name", exception.getMessage());
            return;
        }
        if (currentRecord == null) {
            generateAndStore(name);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Regenerate the only peer?")
                .setMessage("The existing PC configuration will stop authenticating after the "
                        + "gateway reloads. Export and import the new configuration on your PC.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate", (dialog, which) -> generateAndStore(name))
                .show();
    }

    private void generateAndStore(WireGuardProfileName name) {
        try {
            WireGuardNativeGateway.KeyPair server = WireGuardNativeGateway.generateKeyPair();
            WireGuardNativeGateway.KeyPair client = WireGuardNativeGateway.generateKeyPair();
            WireGuardPeerRecord record = new WireGuardPeerRecord(
                    name,
                    System.currentTimeMillis(),
                    server.getPrivateKey(),
                    server.getPublicKey(),
                    client.getPrivateKey(),
                    client.getPublicKey());
            peerStore.save(record);
            currentRecord = record;
            reloadRunningGateway();
            Toast.makeText(this, "WireGuard peer created", Toast.LENGTH_SHORT).show();
            refreshRecord();
        } catch (LinkageError error) {
            showError("WireGuard component unavailable",
                    "The native WireGuard gateway is not included in this build.");
        } catch (RuntimeException exception) {
            showError("Could not create WireGuard peer", safeMessage(exception));
        }
    }

    private void confirmExport() {
        if (currentRecord == null) {
            showError("No peer", "Create a PC peer before exporting its configuration.");
            return;
        }
        String address = selectedEndpointAddress();
        if (address == null) {
            showError("No LAN endpoint",
                    "Connect the phone to Wi-Fi or enable its hotspot, then refresh addresses.");
            return;
        }

        final WireGuardProfile profile;
        try {
            WireGuardEndpoint endpoint = WireGuardEndpoint.of(
                    address, settings.getWireGuardPort());
            profile = WireGuardPeerProfileFactory.createClientProfile(currentRecord, endpoint);
        } catch (RuntimeException exception) {
            showError("Could not build profile", safeMessage(exception));
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Export private configuration?")
                .setMessage("This .conf file contains the PC private key. Anyone with the file "
                        + "can impersonate this peer. Save it only to a trusted location.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose location", (dialog, which) -> launchExport(profile))
                .show();
    }

    private void launchExport(WireGuardProfile profile) {
        pendingExportConfig = profile.renderConfig();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, profile.getFileName());
        startActivityForResult(intent, REQUEST_EXPORT_PROFILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_PROFILE) {
            return;
        }
        String config = pendingExportConfig;
        pendingExportConfig = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (config == null) {
            showError("Export expired", "Please choose Export .conf again.");
            return;
        }
        Uri destination = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) {
                throw new IOException("Document provider did not open the destination");
            }
            output.write(config.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "WireGuard profile exported", Toast.LENGTH_LONG).show();
        } catch (IOException | SecurityException exception) {
            showError("Export failed", safeMessage(exception));
        }
    }

    private void confirmRevoke() {
        if (currentRecord == null && !storageError) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(storageError ? "Clear broken peer storage?" : "Revoke this PC peer?")
                .setMessage("The encrypted private keys will be deleted from this phone. The "
                        + "exported PC configuration cannot be recovered or reused after the "
                        + "gateway reloads.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Revoke", (dialog, which) -> revokePeer())
                .show();
    }

    private void revokePeer() {
        try {
            peerStore.revoke();
            currentRecord = null;
            storageError = false;
            pendingExportConfig = null;
            reloadRunningGateway();
            Toast.makeText(this, "WireGuard peer revoked", Toast.LENGTH_SHORT).show();
            refreshRecord();
        } catch (RuntimeException exception) {
            showError("Could not revoke peer", safeMessage(exception));
        }
    }

    private String selectedEndpointAddress() {
        if (endpointSpinner == null || endpointAddresses.isEmpty()) {
            return null;
        }
        int position = endpointSpinner.getSelectedItemPosition();
        return position >= 0 && position < endpointAddresses.size()
                ? endpointAddresses.get(position).getAddress() : null;
    }

    private void updateButtons() {
        if (createButton == null) {
            return;
        }
        boolean hasPeer = currentRecord != null;
        createButton.setText(hasPeer ? "Regenerate profile" : "Create profile");
        createButton.setEnabled(!storageError);
        exportButton.setEnabled(hasPeer && !endpointAddresses.isEmpty());
        revokeButton.setEnabled(hasPeer || storageError);
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void reloadRunningGateway() {
        if (ProxyService.getStatus().state == com.justproxy.app.ProxyStatus.State.STOPPED) {
            return;
        }
        startService(new Intent(this, ProxyService.class)
                .setAction(ProxyService.ACTION_RELOAD_WIREGUARD_PEER));
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(18), dp(16), dp(18), dp(16));
        view.setBackgroundResource(R.drawable.card_background);
        view.setElevation(dp(2));
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView title(String value) {
        TextView view = text(value);
        view.setTextColor(getColor(R.color.navy));
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView caption(String value) {
        TextView view = text(value);
        view.setTextColor(getColor(R.color.slate));
        view.setTextSize(13);
        return view;
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(getColor(R.color.navy));
        view.setTextSize(14);
        view.setTextIsSelectable(true);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(14), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams labelParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(12), 0, dp(3));
        return params;
    }

    private LinearLayout.LayoutParams topMarginParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(millis));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        pendingExportConfig = null;
        super.onDestroy();
    }
}
