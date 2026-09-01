package com.justproxy.app.analytics;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Metadata for one completed proxy connection.
 *
 * <p>This model deliberately has no request body, response body, headers, credentials, or URL
 * fields. Targets are reduced to host/port and outcomes are reduced to short machine-readable
 * codes before they are retained.</p>
 */
public final class ProxySessionRecord {
    private static final int MAX_CLIENT_LENGTH = 128;
    private static final int MAX_TARGET_LENGTH = 255;
    private static final int MAX_CODE_LENGTH = 32;

    private final long id;
    private final long startedAtMillis;
    private final long endedAtMillis;
    private final String clientAddress;
    private final String protocol;
    private final String target;
    private final long uploadedBytes;
    private final long downloadedBytes;
    private final String result;

    public ProxySessionRecord(
            long startedAtMillis,
            long endedAtMillis,
            String clientAddress,
            String protocol,
            String target,
            long uploadedBytes,
            long downloadedBytes,
            String result) {
        this(
                -1L,
                startedAtMillis,
                endedAtMillis,
                clientAddress,
                protocol,
                target,
                uploadedBytes,
                downloadedBytes,
                result);
    }

    ProxySessionRecord(
            long id,
            long startedAtMillis,
            long endedAtMillis,
            String clientAddress,
            String protocol,
            String target,
            long uploadedBytes,
            long downloadedBytes,
            String result) {
        if (startedAtMillis < 0L || endedAtMillis < startedAtMillis) {
            throw new IllegalArgumentException("Session timestamps are invalid");
        }
        if (uploadedBytes < 0L || downloadedBytes < 0L) {
            throw new IllegalArgumentException("Byte counts cannot be negative");
        }

        this.id = id;
        this.startedAtMillis = startedAtMillis;
        this.endedAtMillis = endedAtMillis;
        this.clientAddress = sanitizeFreeform(clientAddress, MAX_CLIENT_LENGTH);
        this.protocol = sanitizeCode(protocol, "UNKNOWN");
        this.target = sanitizeTarget(target);
        this.uploadedBytes = uploadedBytes;
        this.downloadedBytes = downloadedBytes;
        this.result = sanitizeCode(result, "UNKNOWN");
    }

    public long getId() {
        return id;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getEndedAtMillis() {
        return endedAtMillis;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public String getProtocol() {
        return protocol;
    }

    /** Returns only a host or host:port endpoint, never a full URL. */
    public String getTarget() {
        return target;
    }

    public long getUploadedBytes() {
        return uploadedBytes;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    /** Returns a short machine-readable result code, not an exception message. */
    public String getResult() {
        return result;
    }

    private static String sanitizeCode(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }

        StringBuilder sanitized = new StringBuilder(Math.min(trimmed.length(), MAX_CODE_LENGTH));
        for (int i = 0; i < trimmed.length() && sanitized.length() < MAX_CODE_LENGTH; i++) {
            char character = trimmed.charAt(i);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-'
                    || character == '.') {
                sanitized.append(character);
            } else {
                return fallback;
            }
        }
        return sanitized.length() == 0 ? fallback : sanitized.toString().toUpperCase(Locale.US);
    }

    private static String sanitizeFreeform(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maximumLength));
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length() && sanitized.length() < maximumLength; i++) {
            char character = trimmed.charAt(i);
            if (!Character.isISOControl(character)) {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }

    private static String sanitizeTarget(String value) {
        String target = sanitizeFreeform(value, 2048);
        if (target.isEmpty()) {
            return "";
        }

        String endpoint = endpointFromUri(target);
        if (endpoint == null) {
            endpoint = target;
            int scheme = endpoint.indexOf("://");
            if (scheme >= 0) {
                endpoint = endpoint.substring(scheme + 3);
            } else if (endpoint.startsWith("//")) {
                endpoint = endpoint.substring(2);
            }
            endpoint = beforeFirst(endpoint, '/', '?', '#');
            int userInfo = endpoint.lastIndexOf('@');
            if (userInfo >= 0) {
                endpoint = endpoint.substring(userInfo + 1);
            }
        }

        endpoint = sanitizeFreeform(endpoint, MAX_TARGET_LENGTH);
        return endpoint;
    }

    private static String endpointFromUri(String value) {
        if (value.indexOf("://") < 0 && !value.startsWith("//")) {
            return null;
        }
        try {
            URI uri = new URI(value.startsWith("//") ? "proxy:" + value : value);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return uri.getPort() >= 0 ? formattedHost + ":" + uri.getPort() : formattedHost;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static String beforeFirst(String value, char... delimiters) {
        int end = value.length();
        for (char delimiter : delimiters) {
            int index = value.indexOf(delimiter);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return value.substring(0, end);
    }
}
