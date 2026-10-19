package sumicya.fcmself

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoogleFirewallPolicyTest {

    private val googleAppIds = setOf(10_123, 10_456)

    @Test
    fun dropsOnlyRejectAllRulesForGoogleAppsWhenTheirNetworkSwitchesAreUnavailable() {
        assertTrue(
            shouldDropGoogleFirewallReject(
                uid = 10_123,
                rule = 2,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = true,
            ),
        )
        assertFalse(
            shouldDropGoogleFirewallReject(
                uid = 10_123,
                rule = 1,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = true,
            ),
        )
        assertFalse(
            shouldDropGoogleFirewallReject(
                uid = 10_123,
                rule = 2,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = false,
            ),
        )
        assertFalse(
            shouldDropGoogleFirewallReject(
                uid = 10_999,
                rule = 2,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = true,
            ),
        )
    }

    @Test
    fun recognizesGoogleAppIdForSecondaryAndroidUsers() {
        assertTrue(
            shouldDropGoogleFirewallReject(
                uid = 110_123,
                rule = 2,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = true,
            ),
        )
    }

    @Test
    fun ignoresInvalidUids() {
        assertFalse(
            shouldDropGoogleFirewallReject(
                uid = -1,
                rule = 2,
                googleAppIds = googleAppIds,
                batteryIgnoresGmsUserSet = true,
            ),
        )
    }
}
