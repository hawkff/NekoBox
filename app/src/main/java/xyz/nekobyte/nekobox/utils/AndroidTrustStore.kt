package xyz.nekobyte.nekobox.utils

import android.os.Build
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.ByteArrayOutputStream
import java.security.KeyStore

/**
 * The roots the app's own Go requests trust (subscriptions, rule assets, update checks), read from KeyStore
 * "AndroidCAStore": its "system:" aliases are Android's own roots, its "user:" ones were installed in Settings.
 * The core's proxy connections and Tailscale keep the core's certificate store.
 */
object AndroidTrustStore {

    // Android before 9 lacks roots that current servers chain to, so the core's copy of Mozilla's list stands in for
    // Android's own roots there, as ConfigBuilder does for the core.
    val useMozilla get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    /** Every certificate of AndroidCAStore by alias; none when the store cannot be read. */
    fun read(): Map<String, ByteArray> = try {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        store.aliases().asSequence().mapNotNull { alias -> store.getCertificate(alias)?.let { alias to it.encoded } }.toMap()
    } catch (e: Exception) {
        Logs.w("AndroidCAStore", e)
        emptyMap()
    }

    /** Aliases of unknown kinds are left out. */
    fun trustedAliases(aliases: Collection<String>, mozilla: Boolean, trustUserCertificates: Boolean) = aliases.filter {
        (it.startsWith("system:") && !mozilla) || (it.startsWith("user:") && trustUserCertificates)
    }

    /** The trusted certificates' DER, one after another. */
    fun trustedDer(certificates: Map<String, ByteArray>, trustUserCertificates: Boolean) = ByteArrayOutputStream().apply {
        trustedAliases(certificates.keys, useMozilla, trustUserCertificates).forEach { write(certificates.getValue(it)) }
    }.toByteArray()
}
