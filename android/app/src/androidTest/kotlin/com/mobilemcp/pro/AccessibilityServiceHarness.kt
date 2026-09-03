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

    // Once the first attempt has exhausted the full budget, the service is not coming. A
    // later test still re-checks — the platform could in principle bind it late — but for
    // seconds rather than minutes. Without this, every test in the suite waits the whole
    // budget on the way to the same failure, and twenty-seven of them exceed the job's
    // wall-clock limit, so a diagnosable failure turns into a timed-out runner with no
    // report at all.
    private const val RETRY_TIMEOUT_MILLIS = 5_000L

    private const val POLL_INTERVAL_MILLIS = 250L

    /** The diagnosis from the first failed attempt, reused so later tests fail fast. */
    @Volatile
    private var firstFailure: String? = null

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

        val alreadyFailed = firstFailure != null
        if (!alreadyFailed) {
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }

        val budget = if (alreadyFailed) RETRY_TIMEOUT_MILLIS else ENABLE_TIMEOUT_MILLIS
        val deadline = System.currentTimeMillis() + budget
        var systemEverReportedEnabled = false
        while (System.currentTimeMillis() < deadline) {
            AutomatorRegistry.get()?.let { return it }
            if (systemReportsServiceEnabled(context)) systemEverReportedEnabled = true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        firstFailure?.let { error(it) }

        // A setup failure here is the single most likely reason for a red instrumented
        // run, and it happens on a machine nobody is watching — so the message carries
        // everything needed to diagnose it from the CI log alone, rather than requiring a
        // second run with extra logging added.
        val diagnosis = buildString {
            appendLine("The Accessibility service did not connect within ${budget}ms.")
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
            appendLine("  test package       : ${InstrumentationRegistry.getInstrumentation().context.packageName}")
            appendLine("  target package     : ${context.packageName}")
            // The service registers itself into a process-global object, so the tests can
            // only see it if the service is instantiated in this very process. Package
            // names do not establish that; the process name does.
            appendLine("  this process       : ${processName()} (pid ${android.os.Process.myPid()})")
            appendLine("  --- logcat (accessibility + DroidPilot) ---")
            append(recentLog())
        }
        firstFailure = diagnosis
        error(diagnosis)
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

    /**
     * The last few hundred lines of logcat, filtered to the things that explain a failure
     * to bind: the platform's own accessibility manager, the activity manager's service
     * errors, and DroidPilot's own tag.
     *
     * Without this, a service that throws on the way up is invisible from the CI log —
     * the only symptom is a timeout that looks identical to "the settings write did not
     * take", and diagnosing it costs another ten-minute emulator run.
     */
    private fun recentLog(): String = try {
        shell("logcat -d -t 400")
            .lineSequence()
            .filter { line ->
                RELEVANT_LOG_TAGS.any { it in line }
            }
            .toList()
            .takeLast(60)
            .joinToString("\n") { "    $it" }
            .ifBlank { "    (nothing matching in the last 400 lines)" }
    } catch (e: Exception) {
        "    (could not read logcat: ${e.javaClass.simpleName})"
    }

    private val RELEVANT_LOG_TAGS = listOf(
        "AccessibilityManagerService",
        "AccessibilityService",
        "DroidPilot",
        "ActivityManager",
        "AndroidRuntime",
        "PackageManager",
    )

    /** The name of the process these tests are running in, read from the kernel. */
    private fun processName(): String = try {
        java.io.File("/proc/self/cmdline").readBytes().decodeToString().trimEnd('\u0000')
    } catch (e: Exception) {
        "(unreadable: ${e.javaClass.simpleName})"
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
