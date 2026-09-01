package com.justproxy.app.wireguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android-Keystore-backed encrypted storage for the single WireGuard peer. */
public final class WireGuardPeerStore {
    private static final String PREFERENCES_NAME = "wireguard_peer_secrets";
    private static final String KEY_ALIAS = "justproxy.wireguard.peer.v1";
    private static final String RECORD = "peer_record_v1";
    private static final String RECORD_IV = "peer_record_iv_v1";
    private static final byte[] ASSOCIATED_DATA =
            "JustProxy/WireGuardPeer/v1".getBytes(StandardCharsets.UTF_8);
    private static final Object STORE_LOCK = new Object();

    private final SharedPreferences preferences;

    public WireGuardPeerStore(Context context) {
        Context application = context.getApplicationContext();
        preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public Optional<WireGuardPeerRecord> load() {
        synchronized (STORE_LOCK) {
            String encryptedText = preferences.getString(RECORD, null);
            String ivText = preferences.getString(RECORD_IV, null);
            if (encryptedText == null && ivText == null) {
                return Optional.empty();
            }
            if (encryptedText == null || ivText == null) {
                throw new IllegalStateException("Stored WireGuard peer is incomplete; revoke it");
            }
            try {
                byte[] encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);
                byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
                if (iv.length != 12) {
                    throw new IllegalStateException("Stored WireGuard peer has an invalid IV");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(),
                        new GCMParameterSpec(128, iv));
                cipher.updateAAD(ASSOCIATED_DATA);
                byte[] clear = cipher.doFinal(encrypted);
                return Optional.of(WireGuardPeerRecordCodec.decode(clear));
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Stored WireGuard peer cannot be decrypted; revoke it", exception);
            }
        }
    }

    /** Atomically replaces the encrypted one-peer record with a fresh random GCM nonce. */
    public void save(WireGuardPeerRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        synchronized (STORE_LOCK) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                cipher.updateAAD(ASSOCIATED_DATA);
                byte[] encrypted = cipher.doFinal(WireGuardPeerRecordCodec.encode(record));
                boolean committed = preferences.edit()
                        .putString(RECORD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .putString(RECORD_IV,
                                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                        .commit();
                if (!committed) {
                    throw new IllegalStateException("Unable to commit encrypted WireGuard peer");
                }
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to protect WireGuard peer", exception);
            }
        }
    }

    /**
     * Cryptographically revokes the local record by deleting its dedicated Keystore key, then
     * removes the now-unreadable ciphertext from the dedicated preferences file.
     */
    public void revoke() {
        synchronized (STORE_LOCK) {
            try {
                KeyStore keyStore = openKeyStore();
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
                if (!preferences.edit().clear().commit()) {
                    throw new IllegalStateException(
                            "WireGuard key was revoked but ciphertext cleanup failed");
                }
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to revoke WireGuard peer", exception);
            }
        }
    }

    private static SecretKey getExistingKey() throws Exception {
        java.security.Key key = openKeyStore().getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new IllegalStateException("WireGuard protection key is missing");
        }
        return (SecretKey) key;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = openKeyStore();
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static KeyStore openKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }
}
