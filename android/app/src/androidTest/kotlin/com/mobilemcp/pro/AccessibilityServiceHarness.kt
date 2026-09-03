package com.mobilemcp.pro

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilemcp.pro.automation.AutomatorRegistry
import com.mobilemcp.pro.automation.DeviceAutomator
import com.mobilemcp.pro.service.DroidPilotAccessibilityService
import java.io.FileInputStream

/**
 * Turns DroidPilot's Accessibility service on for the duration of an instrumented test.
 *
 * An Accessibility service is normally enabled by the user in system settings, which a test
 * cannot do. `UiAutomation.executeShellCommand` runs with shell privileges, which is enough
 * to write the two secure settings the platform reads — so the test enables the service
 * itself rather than relying on a CI step that a later `installDebug` would undo.
 *
 * This harness deliberately has no "skip if unavailable" path. A test that quietly passes
 * because the service never connected would report coverage of exactly the layer that has
 * none, which is worse than having no test at all.
 */
object AccessibilityServiceHarness {

    // Generous, because a cold CI emulator has to start the app process and bind the
    // service under software-assisted virtualisation. The cost of being wrong in this
    // direction is a slower failure; the cost in the other is a red run that says nothing.
    private const val ENABLE_TIMEOUT_MILLIS = 90_000L
    private const val POLL_INTERVAL_MILLIS = 250L

    /**
     * Enables the service and blocks until it has bound itself into [AutomatorRegistry].
     *
     * @throws IllegalStateException if the service does not connect in time — the correct
     *   outcome, because every test relying on it would otherwise be meaningless.
     */
    fun enableAndAwait(): DeviceAutomator {
        AutomatorRegistry.get()?.let { return it }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = "${context.packageName}/${DroidPilotAccessibilityService::class.java.name}"

        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")

        val deadline = System.currentTimeMillis() + ENABLE_TIMEOUT_MILLIS
        var systemEverReportedEnabled = false
        while (System.currentTimeMillis() < deadline) {
            AutomatorRegistry.get()?.let { return it }
            if (systemReportsServiceEnabled(context)) systemEverReportedEnabled = true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        // A setup failure here is the single most likely reason for a red instrumented
        // run, and it happens on a machine nobody is watching — so the message carries
        // everything needed to diagnose it from the CI log alone, rather than requiring a
        // second run with extra logging added.
        error(
            buildString {
                appendLine("The Accessibility service did not connect within ${ENABLE_TIMEOUT_MILLIS}ms.")
                // The decisive distinction. If the system enabled the service but the
                // registry stayed empty, the fault is in the app (onServiceConnected did
                // not register). If the system never enabled it, the settings write did not
                // take. These need entirely different fixes, so the message says which.
                appendLine(
                    if (systemEverReportedEnabled) {
                        "  DIAGNOSIS: the system DID enable the service, but it never registered " +
                            "itself. Look at DroidPilotAccessibilityService.onServiceConnected."
                    } else {
                        "  DIAGNOSIS: the system never reported the service as enabled, so the " +
                            "settings write did not take effect."
                    },
                )
                appendLine("  expected component : $component")
                appendLine("  enabled services   : ${settingOrError("enabled_accessibility_services")}")
                appendLine("  accessibility_enabled: ${settingOrError("accessibility_enabled")}")
                appendLine("  test process       : ${InstrumentationRegistry.getInstrumentation().context.packageName}")
                appendLine("  target process     : ${context.packageName}")
                append("  installed services : ${settingOrError("enabled_accessibility_services", secure = false)}")
            },
        )
    }

    /** Asks the platform — not our own registry — whether it considers the service enabled. */
    private fun systemReportsServiceEnabled(context: android.content.Context): Boolean = try {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        manager?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )?.any {
            it.resolveInfo.serviceInfo.name == DroidPilotAccessibilityService::class.java.name
        } == true
    } catch (e: Exception) {
        false
    }

    /** Reads a setting, returning the failure text rather than throwing from an error path. */
    private fun settingOrError(key: String, secure: Boolean = true): String = try {
        shell("settings get ${if (secure) "secure" else "global"} $key").trim().ifBlank { "(unset)" }
    } catch (e: Exception) {
        "(could not read: ${e.javaClass.simpleName})"
    }

    /** Disables the service again, so tests do not leak state into one another. */
    fun disable() {
        shell("settings put secure enabled_accessibility_services ''")
        shell("settings put secure accessibility_enabled 0")
    }

    /**
     * Runs a shell command and returns its output.
     *
     * The returned descriptor must be drained and closed: `executeShellCommand` is
     * asynchronous, and abandoning the pipe can leave the command unfinished — which would
     * make the settings write above racy.
     */
    private fun shell(command: String): String {
        val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            (stream as FileInputStream).readBytes().decodeToString()
        }
    }
}
