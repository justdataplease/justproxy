package com.justproxy.app.shizuku;

import android.content.Context;
import android.telephony.TelephonyManager;

/** Uses Android's public default-subscription TelephonyManager state API. */
final class AndroidMobileDataStateReader implements MobileDataStateReader {
    private final TelephonyManager telephonyManager;

    AndroidMobileDataStateReader(Context context) {
        telephonyManager = (TelephonyManager) context.getApplicationContext()
                .getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public State read() {
        if (telephonyManager == null) return State.UNKNOWN;
        try {
            return telephonyManager.isDataEnabled() ? State.ENABLED : State.DISABLED;
        } catch (SecurityException | UnsupportedOperationException exception) {
            return State.UNKNOWN;
        } catch (RuntimeException exception) {
            // OEM telephony implementations can throw while the default subscription changes.
            return State.UNKNOWN;
        }
    }
}
