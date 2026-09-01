package com.justproxy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Centralized, validated app preferences and encrypted proxy credentials. */
public final class AppSettings {
    public static final int DEFAULT_PORT = 8282;
    public static final int DEFAULT_WIREGUARD_PORT = 51820;
    private static final String PREFS = "settings";
    private static final String SECRETS = "secrets";
    private static final String KEY_ALIAS = "justproxy.credentials.v1";

    private final SharedPreferences preferences;
    private final SharedPreferences secrets;
    private final SecureRandom random = new SecureRandom();

    public AppSettings(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = app.getSharedPreferences(SECRETS, Context.MODE_PRIVATE);
    }

    public int getPort() {
        return bounded(preferences.getInt("port", DEFAULT_PORT), 1024, 65534, DEFAULT_PORT);
    }

    public void setPort(int value) {
        preferences.edit().putInt("port", bounded(value, 1024, 65534, DEFAULT_PORT)).apply();
    }

    public boolean isLegacyProxyEnabled() {
        return preferences.getBoolean("legacy_proxy_enabled", true);
    }

    public void setLegacyProxyEnabled(boolean value) {
        preferences.edit().putBoolean("legacy_proxy_enabled", value).apply();
    }

    public boolean isWireGuardEnabled() {
        return preferences.getBoolean("wireguard_enabled", false);
    }

    public void setWireGuardEnabled(boolean value) {
        preferences.edit().putBoolean("wireguard_enabled", value).apply();
    }

    public int getWireGuardPort() {
        return bounded(preferences.getInt("wireguard_port", DEFAULT_WIREGUARD_PORT),
                1024, 65535, DEFAULT_WIREGUARD_PORT);
    }

    public void setWireGuardPort(int value) {
        preferences.edit().putInt("wireguard_port",
                bounded(value, 1024, 65535, DEFAULT_WIREGUARD_PORT)).apply();
    }

    public boolean isLanAccessEnabled() {
        return preferences.getBoolean("lan_access", false);
    }

    public void setLanAccessEnabled(boolean value) {
        preferences.edit().putBoolean("lan_access", value).apply();
    }

    public boolean isCellularOnly() {
        return preferences.getBoolean("cellular_only", true);
    }

    public void setCellularOnly(boolean value) {
        preferences.edit().putBoolean("cellular_only", value).apply();
    }

    public int getRotationMinutes() {
        return bounded(preferences.getInt("rotation_minutes", 0), 0, 1440, 0);
    }

    public void setRotationMinutes(int value) {
        preferences.edit().putInt("rotation_minutes", bounded(value, 0, 1440, 0)).apply();
    }

    public int getIdleTimeoutSeconds() {
        return bounded(preferences.getInt("idle_timeout_seconds", 120), 10, 3600, 120);
    }

    public void setIdleTimeoutSeconds(int value) {
        preferences.edit().putInt("idle_timeout_seconds", bounded(value, 10, 3600, 120)).apply();
    }

    public int getMaxConnections() {
        return bounded(preferences.getInt("max_connections", 8), 1, 64, 8);
    }

    public void setMaxConnections(int value) {
        preferences.edit().putInt("max_connections", bounded(value, 1, 64, 8)).apply();
    }

    /** Per-service-run cap in MiB. Zero disables the cap. */
    public long getDataCapMiB() {
        return boundedLong(preferences.getLong("data_cap_mib", 0L), 0L, 1_048_576L, 0L);
    }

    public void setDataCapMiB(long value) {
        preferences.edit().putLong("data_cap_mib", boundedLong(value, 0L, 1_048_576L, 0L)).apply();
    }

    public boolean isPrivateDestinationAccessEnabled() {
        return preferences.getBoolean("allow_private_destinations", false);
    }

    public void setPrivateDestinationAccessEnabled(boolean value) {
        preferences.edit().putBoolean("allow_private_destinations", value).apply();
    }

    public boolean hasAcceptedSafetyNotice() {
        return preferences.getBoolean("accepted_safety_notice", false);
    }

    public void setAcceptedSafetyNotice(boolean value) {
        preferences.edit().putBoolean("accepted_safety_notice", value).apply();
    }

    public Credentials getCredentials() {
        try {
            String encrypted = secrets.getString("credentials", null);
            String iv = secrets.getString("credentials_iv", null);
            if (encrypted != null && iv != null) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                        new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
                String clear = new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                        StandardCharsets.UTF_8);
                int split = clear.indexOf('\n');
                if (split > 0 && split < clear.length() - 1) {
                    return new Credentials(clear.substring(0, split), clear.substring(split + 1));
                }
            }
        } catch (Exception ignored) {
            // A restored/corrupt entry cannot be decrypted; replace it with fresh credentials.
        }
        return regenerateCredentials();
    }

    public Credentials regenerateCredentials() {
        byte[] userBytes = new byte[4];
        byte[] passwordBytes = new byte[18];
        random.nextBytes(userBytes);
        random.nextBytes(passwordBytes);
        String username = "jp_" + toHex(userBytes).toLowerCase(Locale.ROOT);
        String password = Base64.encodeToString(passwordBytes,
                Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        Credentials credentials = new Credentials(username, password);
        storeCredentials(credentials);
        return credentials;
    }

    private void storeCredentials(Credentials credentials) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal((credentials.username + "\n" + credentials.password)
                    .getBytes(StandardCharsets.UTF_8));
            secrets.edit()
                    .putString("credentials", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString("credentials_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect proxy credentials", exception);
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    private static long boundedLong(long value, long min, long max, long fallback) {
        return value < min || value > max ? fallback : value;
    }

    public static final class Credentials {
        public final String username;
        public final String password;

        public Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
