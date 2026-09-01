package com.justproxy.app.wireguard;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A portable profile name that is safe to use as a Windows, Android, or Unix file stem. */
public final class WireGuardProfileName {
    private static final int MAX_LENGTH = 64;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final String value;

    private WireGuardProfileName(String value) {
        this.value = value;
    }

    public static WireGuardProfileName of(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "profile name must contain 1 to 64 characters with no surrounding whitespace");
        }
        if (!isAsciiLetterOrDigit(value.charAt(0))) {
            throw new IllegalArgumentException(
                    "profile name must start with an ASCII letter or digit");
        }
        char finalCharacter = value.charAt(value.length() - 1);
        if (!(isAsciiLetterOrDigit(finalCharacter)
                || finalCharacter == '-' || finalCharacter == '_')) {
            throw new IllegalArgumentException(
                    "profile name must end with a letter, digit, hyphen, or underscore");
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("profile name must not contain '..'");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(isAsciiLetterOrDigit(character) || character == ' '
                    || character == '-' || character == '_' || character == '.')) {
                throw new IllegalArgumentException(
                        "profile name may contain only ASCII letters, digits, spaces, '.', '-', and '_'");
            }
        }

        String basename = value;
        int extension = basename.indexOf('.');
        if (extension >= 0) {
            basename = basename.substring(0, extension);
        }
        if (WINDOWS_RESERVED_NAMES.contains(basename.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("profile name is reserved by Windows");
        }
        return new WireGuardProfileName(value);
    }

    public String getValue() {
        return value;
    }

    public String toFileName() {
        return value + ".conf";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof WireGuardProfileName
                && value.equals(((WireGuardProfileName) other).value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
    }
}
