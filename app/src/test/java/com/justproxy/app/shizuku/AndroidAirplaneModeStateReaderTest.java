package com.justproxy.app.shizuku;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AndroidAirplaneModeStateReaderTest {
    @Test
    public void decodesOnlyCanonicalGlobalSettingValues() {
        assertEquals(
                AirplaneModeStateReader.State.DISABLED,
                AndroidAirplaneModeStateReader.fromSetting(0));
        assertEquals(
                AirplaneModeStateReader.State.ENABLED,
                AndroidAirplaneModeStateReader.fromSetting(1));
        assertEquals(
                AirplaneModeStateReader.State.UNKNOWN,
                AndroidAirplaneModeStateReader.fromSetting(-1));
        assertEquals(
                AirplaneModeStateReader.State.UNKNOWN,
                AndroidAirplaneModeStateReader.fromSetting(2));
    }
}
