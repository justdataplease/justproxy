package com.justproxy.app.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class WireGuardProfileNameTest {
    @Test
    public void producesPortableConfigurationFilename() {
        WireGuardProfileName name = WireGuardProfileName.of("Jason-PC_2");

        assertEquals("Jason-PC_2", name.getValue());
        assertEquals("Jason-PC_2.conf", name.toFileName());
        assertEquals(name, WireGuardProfileName.of("Jason-PC_2"));
    }

    @Test
    public void allowsReadableNamesWithoutSanitizingThemSilently() {
        WireGuardProfileName name = WireGuardProfileName.of("Office PC.v2");

        assertEquals("Office PC.v2.conf", name.toFileName());
    }

    @Test
    public void rejectsTraversalSeparatorsControlCharactersAndUnsafeEndings() {
        assertInvalid("../office");
        assertInvalid("office/pc");
        assertInvalid("office\\pc");
        assertInvalid("office\nPC");
        assertInvalid(" office");
        assertInvalid("office ");
        assertInvalid("office.");
        assertInvalid("office..pc");
    }

    @Test
    public void rejectsWindowsDeviceNamesEvenWhenTheyHaveAnExtension() {
        assertInvalid("CON");
        assertInvalid("nul.backup");
        // This is not the reserved basename LPT9 and is safe.
        assertEquals("Lpt9-profile.conf", WireGuardProfileName.of("Lpt9-profile").toFileName());
    }

    private static void assertInvalid(String name) {
        try {
            WireGuardProfileName.of(name);
            fail("expected name to be rejected: " + name);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
