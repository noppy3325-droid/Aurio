package jp.noppy.lumamusic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRoutingTest {
    @Test fun `a manual switch keeps playback going while connected USB loss pauses`() {
        assertFalse(pauseAfterUsbRouteChange(true, 10, 4, previousConnected = true, switching = true))
        assertTrue(pauseAfterUsbRouteChange(true, 10, 4, previousConnected = false, switching = true))
        assertTrue(pauseAfterUsbRouteChange(true, 10, 4, previousConnected = true, switching = false))
        assertFalse(pauseAfterUsbRouteChange(false, 4, 7, previousConnected = true, switching = false))
    }

    @Test fun `selection reports pending until verified and distinguishes platform fallback`() {
        assertEquals(RouteSelectionStatus.SWITCHING, routeSelectionStatus(5, 7, switching = true))
        assertEquals(RouteSelectionStatus.ACTIVE, routeSelectionStatus(5, 5, switching = true))
        assertEquals(RouteSelectionStatus.MISMATCH, routeSelectionStatus(5, 7, switching = false))
        assertEquals(RouteSelectionStatus.WAITING, routeSelectionStatus(5, null, switching = false))
        assertEquals(RouteSelectionStatus.AUTOMATIC, routeSelectionStatus(null, 7, switching = false))
    }
}
