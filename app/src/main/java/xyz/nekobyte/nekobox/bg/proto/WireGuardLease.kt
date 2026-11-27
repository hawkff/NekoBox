package xyz.nekobyte.nekobox.bg.proto

import kotlinx.coroutines.delay
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.R
import java.io.Closeable
import java.io.File

/**
 * WireGuard sessions ([xyz.nekobyte.nekobox.fmt.wireguard.WireGuardIdentities]) a core holds from before it starts
 * until after it closed, across the app's processes: a server keeps one session per client key, so a second core
 * with one of them would take it from the first.
 */
internal object WireGuardLease {
    // Overridable for tests.
    internal var directory: () -> File = { File(NekoBox.application.noBackupFilesDir, "wireguard-locks") }

    /** All of [identities], or null when a core holds one of them. */
    fun tryAcquire(identities: Set<String>): Closeable? = try {
        ProcessLease.acquire(directory(), identities) { it }
    } catch (_: LeaseBusyException) {
        null
    }

    /** All of [identities], waiting up to [timeoutMs] for a core that holds one of them to close. */
    suspend fun acquire(identities: Set<String>, timeoutMs: Long): Closeable {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            tryAcquire(identities)?.let { return it }
            if (System.nanoTime() >= deadline) throw LeaseBusyException(NekoBox.application.getString(R.string.vpn_wireguard_test_running))
            delay(100)
        }
    }
}
