package com.justproxy.app.shizuku;

/** Pure compatibility policy for persisted beta.2 and beta.3 recovery markers. */
final class RecoveryModePolicy {
    static final String AIRPLANE_MODE_VALUE = "airplane_mode";
    static final String LEGACY_MOBILE_DATA_VALUE = "mobile_data_legacy";

    enum Mode { AIRPLANE_MODE, LEGACY_MOBILE_DATA }

    private RecoveryModePolicy() { }

    static Mode fromStored(boolean recoveryRequired, String storedMode) {
        if (!recoveryRequired) return Mode.AIRPLANE_MODE;
        return AIRPLANE_MODE_VALUE.equals(storedMode)
                ? Mode.AIRPLANE_MODE
                : Mode.LEGACY_MOBILE_DATA;
    }

    static String storedValue(Mode mode) {
        return mode == Mode.AIRPLANE_MODE
                ? AIRPLANE_MODE_VALUE
                : LEGACY_MOBILE_DATA_VALUE;
    }
}
