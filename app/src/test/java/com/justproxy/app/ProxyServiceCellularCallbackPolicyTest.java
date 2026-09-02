package com.justproxy.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProxyServiceCellularCallbackPolicyTest {
    @Test
    public void availabilityBeforeRotationResultCannotRestartDataPlanes() {
        assertFalse(ProxyService.shouldStartDataPlanesOnCellularAvailable(
                true, false, false));
        assertFalse(ProxyService.shouldStartDataPlanesOnCellularAvailable(
                true, true, false));
    }

    @Test
    public void availabilityAfterRotationResultCanStartFreshDataPlanes() {
        assertTrue(ProxyService.shouldStartDataPlanesOnCellularAvailable(
                false, false, false));
    }

    @Test
    public void duplicateHealthyAvailabilityDoesNotRestartDataPlanes() {
        assertFalse(ProxyService.shouldStartDataPlanesOnCellularAvailable(
                false, true, true));
        assertTrue(ProxyService.shouldStartDataPlanesOnCellularAvailable(
                false, true, false));
    }
}
