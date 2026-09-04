package com.mobilemcp.pro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mobilemcp.pro.core.SecurityServices
import com.mobilemcp.pro.core.identity.DeviceIdentity
import com.mobilemcp.pro.core.permission.Grant
import com.mobilemcp.pro.core.permission.GrantDuration
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the on-device Grant UI — the buttons and dialog in [MainActivity] through which
 * the owner authorises a paired device — against the real [SecurityServices] graph.
 *
 * [com.mobilemcp.pro.core.permission.AuthorizationManagerTest] already covers every rule the
 * decision engine enforces, exhaustively, on the JVM. What that suite cannot see is whether
 * *this screen* actually drives it: whether tapping "Grant shell" and picking a duration in
 * the real `MaterialAlertDialogBuilder` window produces a grant keyed to the real device
 * identity — which is derived from a secret unwrapped through the Android Keystore, not a
 * fake — and whether the card on screen then reflects it. A regression that wired the button
 * to the wrong permission, or a dialog whose item order stopped matching `when (which)`,
 * would pass every unit test in the suite and still leave the owner granting the wrong thing.
 *
 * Interaction goes through [UiDevice] rather than Espresso — this project has no Espresso
 * dependency — which also happens to be the honest choice here: it drives the dialog the same
 * way a real owner's tap would, through the platform's own accessibility tree, rather than by
 * reaching into the Activity's view hierarchy directly.
 */
@RunWith(AndroidJUnit4::class)
class GrantUiInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private lateinit var deviceId: String
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        // A grant is keyed to the identity derived from the pairing secret (see
        // DeviceIdentity), so one has to exist before the dialog can grant anything.
        deviceId = DeviceIdentity.forSecret(PairingSecretStore(context).getOrCreate())
        SecurityServices.authorization.revokeAll(deviceId)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        find(ID_GRANT_SHELL, LAUNCH_TIMEOUT_MILLIS)
    }

    @After
    fun tearDown() {
        scenario?.close()
        // Grants persist to disk (PersistentGrantStore); leaving one behind would let this
        // test's outcome depend on whichever test happened to run before it.
        SecurityServices.authorization.revokeAll(deviceId)
    }

    private fun find(resourceId: String, timeoutMillis: Long = DIALOG_TIMEOUT_MILLIS): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.res(context.packageName, resourceId)), timeoutMillis)) {
            "no view with id '$resourceId' appeared within ${timeoutMillis}ms"
        }

    private fun findText(text: String, timeoutMillis: Long = DIALOG_TIMEOUT_MILLIS): UiObject2 =
        requireNotNull(device.wait(Until.findObject(By.text(text)), timeoutMillis)) {
            "no view with text '$text' appeared within ${timeoutMillis}ms"
        }

    private fun activeGrant(permission: RemotePermission): Grant? =
        SecurityServices.authorization.activeGrants(deviceId).firstOrNull { it.permission == permission }

    /** Opens the duration dialog for the button behind [buttonId] and asserts it appeared. */
    private fun openGrantDialog(buttonId: String) {
        find(buttonId, LAUNCH_TIMEOUT_MILLIS).click()
        findText(context.getString(R.string.grant_duration_title))
    }

    @Test
    fun grantingShellFor15MinutesThroughTheDialogAppliesARealGrant() {
        openGrantDialog(ID_GRANT_SHELL)
        findText(context.getString(R.string.grant_15_minutes)).click()

        val grant = activeGrant(RemotePermission.REMOTE_SHELL)
        assertNotNull("the dialog should have produced a live grant", grant)
        val duration = grant!!.duration as? GrantDuration.Until
        assertNotNull("a 15-minute grant should carry an expiry", duration)
        assertTrue("a 15-minute grant should expire in the future", duration!!.expiresAtMillis > System.currentTimeMillis())

        val grantsText = find(ID_TV_GRANTS).text
        assertTrue(
            "the grants card should now name the permission that was just granted",
            grantsText.contains(RemotePermission.REMOTE_SHELL.wireName),
        )
    }

    @Test
    fun grantingOnceProducesASingleUseGrant() {
        openGrantDialog(ID_GRANT_SHELL)
        findText(context.getString(R.string.grant_once)).click()

        val grant = activeGrant(RemotePermission.REMOTE_SHELL)
        assertNotNull(grant)
        assertEquals(GrantDuration.Once, grant!!.duration)
    }

    /**
     * Root is the one permission the dialog is asked to show an extra warning for before
     * granting — see `MainActivity.confirmGrant`. Losing that call is exactly the kind of
     * change a JVM test cannot see, since the warning is a dialog message, not a decision.
     */
    @Test
    fun grantingRootShowsTheRootWarningBeforeGranting() {
        find(ID_GRANT_ROOT, LAUNCH_TIMEOUT_MILLIS).click()
        findText(context.getString(R.string.grant_root_warning))

        findText(context.getString(R.string.grant_until_revoked)).click()

        val grant = activeGrant(RemotePermission.REMOTE_ROOT)
        assertNotNull(grant)
        assertEquals(GrantDuration.UntilRevoked, grant!!.duration)
    }

    @Test
    fun cancellingTheDurationDialogGrantsNothing() {
        openGrantDialog(ID_GRANT_SHELL)
        findText(context.getString(R.string.btn_cancel)).click()

        assertNull("cancelling the dialog must not create a grant", activeGrant(RemotePermission.REMOTE_SHELL))
    }

    @Test
    fun revokeAllClearsAnExistingGrantAndUpdatesTheDisplay() {
        SecurityServices.authorization.grant(deviceId, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        // The card only reads grants from onResume; recreate so the screen reflects the
        // grant that was just issued directly through the manager, the same way it would
        // after the owner backgrounds and returns to the app.
        scenario?.recreate()

        find(ID_BTN_REVOKE_ALL, LAUNCH_TIMEOUT_MILLIS).click()

        assertNull(activeGrant(RemotePermission.REMOTE_SHELL))
        assertEquals(context.getString(R.string.grants_none), find(ID_TV_GRANTS).text)
    }

    private companion object {
        const val ID_GRANT_SHELL = "btnGrantShell"
        const val ID_GRANT_ROOT = "btnGrantRoot"
        const val ID_BTN_REVOKE_ALL = "btnRevokeAll"
        const val ID_TV_GRANTS = "tvGrants"

        const val LAUNCH_TIMEOUT_MILLIS = 10_000L
        const val DIALOG_TIMEOUT_MILLIS = 5_000L
    }
}
