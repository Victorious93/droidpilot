package com.mobilemcp.pro.core.identity

import com.mobilemcp.pro.core.permission.PairedDeviceRegistry
import java.security.MessageDigest

/**
 * Who a remote peer is, for the purpose of deciding what it may do.
 *
 * DroidPilot has exactly one credential: the pairing secret. A peer that presents it during
 * the connection upgrade is, by construction, the client the owner paired — there is
 * nothing else to be. So identity here is not an extra fact the peer asserts about itself;
 * it is derived from the credential it proved it holds. A peer cannot claim to be a
 * different device, because it does not supply the id at all.
 *
 * That has a consequence worth stating plainly, because it is the most useful security
 * property in this file: **regenerating the pairing secret revokes every grant.** Grants are
 * stored against a device id, the id is a function of the secret, and a new secret produces
 * a new id. The old grants remain on disk referring to an identity that can no longer
 * connect, and [SecretBoundPairedDeviceRegistry] reports them as unpaired. "Regenerate
 * secret" is therefore also the panic button for authorisation, with no extra code needed to
 * make it so.
 *
 * The derivation is domain-separated so that this hash can never coincide with any other
 * use of the secret — notably [com.mobilemcp.pro.security.PairingSecret.fingerprint], which
 * is a short display value and is deliberately *not* reused as an identifier: four bytes is
 * fine for a human comparing two screens and far too short to key authorisation on.
 */
object DeviceIdentity {

    private const val DOMAIN = "droidpilot/device-id/v1"

    /** The stable id of the device holding [secret]. Not secret; safe to log and display. */
    fun forSecret(secret: ByteArray): String =
        MessageDigest.getInstance("SHA-256").run {
            update(DOMAIN.toByteArray(Charsets.UTF_8))
            update(secret)
            digest().joinToString("") { "%02x".format(it) }
        }

    /** A short form for the UI, where the full 64 characters are noise. */
    fun shortForm(deviceId: String): String = deviceId.take(12)
}

/**
 * Treats exactly one device as paired: the one holding the current pairing secret.
 *
 * The secret is read through a lambda rather than captured, so that regenerating it takes
 * effect on the very next authorisation check rather than at the next restart. A grant
 * issued under the old secret stops being honoured immediately.
 */
class SecretBoundPairedDeviceRegistry(
    private val currentSecret: () -> ByteArray?,
) : PairedDeviceRegistry {

    override fun isPaired(deviceId: String): Boolean {
        val secret = currentSecret() ?: return false
        return deviceId.isNotBlank() && deviceId == DeviceIdentity.forSecret(secret)
    }

    /** The id of the currently paired device, or `null` when no secret exists yet. */
    fun currentDeviceId(): String? = currentSecret()?.let(DeviceIdentity::forSecret)
}
