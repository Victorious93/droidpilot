package com.mobilemcp.pro

import android.app.Application

/**
 * Process entry point.
 *
 * Deliberately almost empty. Work done here runs on every process start — including starts
 * triggered by the Accessibility service binding, which happen without the user opening the
 * app — so anything expensive placed here is paid for repeatedly and invisibly. The pairing
 * secret is unwrapped by the foreground service on a background dispatcher instead.
 */
class DroidPilotApplication : Application()
