package com.mobilemcp.pro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.automation.ElementSelector
import com.mobilemcp.pro.core.OperationResult
import com.mobilemcp.pro.core.SecurityServices
import com.mobilemcp.pro.core.identity.DeviceIdentity
import com.mobilemcp.pro.core.permission.Grant
import com.mobilemcp.pro.core.permission.GrantDuration
import com.mobilemcp.pro.core.permission.RemotePermission
import com.mobilemcp.pro.security.PairingSecretStore
import com.mobilemcp.pro.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
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
 * fake — and whether the card on screen then reflects it. A regression that wired a button to
 * the wrong permission, or a dialog whose item order stopped matching `when (which)`, would
 * pass every unit test in the suite and still leave the owner granting the wrong thing.
 *
 * Interaction goes through [DeviceAutomator] — the real Accessibility service every other
 * instrumented UI test in this suite already drives "Clear" and friends through (see
 * `AccessibilityAutomatorInstrumentedTest`) — rather than `androidx.test.uiautomator`
 * directly. The two are separate, independent accessibility clients; standing up a second
 * one here bought nothing this one does not already provide, and finding this screen's
 * buttons through it turned out not to work reliably in the CI emulator.
 */
@RunWith(AndroidJUnit4::class)
class GrantUiInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var automator: DeviceAutomator
    private lateinit var deviceId: String
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        automator = AccessibilityServiceHarness.enableAndAwait()
        // A grant is keyed to the identity derived from the pairing secret (see
        // DeviceIdentity), so one has to exist before the dialog can grant anything.
        deviceId = DeviceIdentity.forSecret(PairingSecretStore(context).getOrCreate())
        SecurityServices.authorization.revokeAll(deviceId)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(SETTLE_MILLIS)
    }

    @After
    fun tearDown() {
        scenario?.close()
        // Grants persist to disk (PersistentGrantStore); leaving one behind would let this
        // test's outcome depend on whichever test happened to run before it.
        SecurityServices.authorization.revokeAll(deviceId)
    }

    /** See the identically named helper on `AccessibilityAutomatorInstrumentedTest`. */
    private fun instrumentedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private suspend fun click(text: String) {
        when (val result = automator.clickElement(ElementSelector(text = text, exact = true), longPress = false)) {
            is OperationResult.Success -> Unit
            is OperationResult.Failure ->
                throw AssertionError("could not click '$text': ${result.code}: ${result.message}")
        }
    }

    private suspend fun awaitElement(selector: ElementSelector, timeoutMillis: Long = DIALOG_TIMEOUT_MILLIS) {
        when (val result = automator.waitForElement(selector, timeoutMillis)) {
            is OperationResult.Success ->
                assertTrue(
                    "expected to find an element matching $selector within ${timeoutMillis}ms",
                    result.value.found,
                )
            is OperationResult.Failure ->
                throw AssertionError("waitForElement failed for $selector: ${result.code}: ${result.message}")
        }
    }

    private suspend fun awaitText(text: String, timeoutMillis: Long = DIALOG_TIMEOUT_MILLIS) =
        awaitElement(ElementSelector(text = text, exact = true), timeoutMillis)

    private fun activeGrant(permission: RemotePermission): Grant? =
        SecurityServices.authorization.activeGrants(deviceId).firstOrNull { it.permission == permission }

    private suspend fun openGrantDialog(buttonText: String) {
        click(buttonText)
        awaitText(context.getString(R.string.grant_duration_title))
    }

    @Test
    fun grantingShellFor15MinutesThroughTheDialogAppliesARealGrant() = instrumentedTest {
        openGrantDialog(context.getString(R.string.btn_grant_shell))
        click(context.getString(R.string.grant_15_minutes))

        val grant = activeGrant(RemotePermission.REMOTE_SHELL)
        assertNotNull("the dialog should have produced a live grant", grant)
        val duration = grant!!.duration as? GrantDuration.Until
        assertNotNull("a 15-minute grant should carry an expiry", duration)
        assertTrue(
            "a 15-minute grant should expire in the future",
            duration!!.expiresAtMillis > System.currentTimeMillis(),
        )

        // The grants card names the permission by its wire name, as one line inside a
        // longer block of text — a substring match, not an exact one.
        awaitElement(ElementSelector(text = RemotePermission.REMOTE_SHELL.wireName, exact = false))
    }

    @Test
    fun grantingOnceProducesASingleUseGrant() = instrumentedTest {
        openGrantDialog(context.getString(R.string.btn_grant_shell))
        click(context.getString(R.string.grant_once))

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
    fun grantingRootShowsTheRootWarningBeforeGranting() = instrumentedTest {
        click(context.getString(R.string.btn_grant_root))
        awaitText(context.getString(R.string.grant_root_warning))

        click(context.getString(R.string.grant_until_revoked))

        val grant = activeGrant(RemotePermission.REMOTE_ROOT)
        assertNotNull(grant)
        assertEquals(GrantDuration.UntilRevoked, grant!!.duration)
    }

    @Test
    fun cancellingTheDurationDialogGrantsNothing() = instrumentedTest {
        openGrantDialog(context.getString(R.string.btn_grant_shell))
        click(context.getString(R.string.btn_cancel))

        assertNull("cancelling the dialog must not create a grant", activeGrant(RemotePermission.REMOTE_SHELL))
    }

    @Test
    fun revokeAllClearsAnExistingGrantAndUpdatesTheDisplay() = instrumentedTest {
        SecurityServices.authorization.grant(deviceId, RemotePermission.REMOTE_SHELL, GrantDuration.UntilRevoked)
        // The card only reads grants from onResume; recreate so the screen reflects the
        // grant that was just issued directly through the manager, the same way it would
        // after the owner backgrounds and returns to the app.
        scenario?.recreate()
        Thread.sleep(SETTLE_MILLIS)

        click(context.getString(R.string.btn_revoke_all))

        assertNull(activeGrant(RemotePermission.REMOTE_SHELL))
        awaitText(context.getString(R.string.grants_none))
    }

    private companion object {
        const val SETTLE_MILLIS = 1_500L
        const val DIALOG_TIMEOUT_MILLIS = 5_000L
    }
}
