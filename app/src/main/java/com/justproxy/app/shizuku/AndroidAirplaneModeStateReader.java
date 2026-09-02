package com.justproxy.app.shizuku;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/** Uses Android's public global airplane-mode setting as a read-only state source. */
final class AndroidAirplaneModeStateReader implements AirplaneModeStateReader {
    private static final int UNKNOWN_SETTING = -1;

    private final ContentResolver contentResolver;

    AndroidAirplaneModeStateReader(Context context) {
        contentResolver = context.getApplicationContext().getContentResolver();
    }

    @Override
    public State read() {
        try {
            int value = Settings.Global.getInt(
                    contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    UNKNOWN_SETTING);
            return fromSetting(value);
        } catch (RuntimeException exception) {
            // OEM settings providers can fail while system connectivity state is changing.
            return State.UNKNOWN;
        }
    }

    static State fromSetting(int value) {
        if (value == 0) return State.DISABLED;
        if (value == 1) return State.ENABLED;
        return State.UNKNOWN;
    }
}
