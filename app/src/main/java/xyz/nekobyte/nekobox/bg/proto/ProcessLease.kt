package xyz.nekobyte.nekobox.bg.proto

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** A name [ProcessLease.acquire] found held, by this process or another. */
internal class LeaseBusyException(message: String) : IllegalStateException(message)

/**
 * Exclusive use of names across the app's processes: a reservation in this process and a lock on a file per name.
 * Lock files outlive what they guard: removing them would split ownership across inodes.
 */
internal class ProcessLease private constructor(
    private val path: String,
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    private var closed = false

    override fun close() = synchronized(reservations) {
        if (!closed) {
            closed = true
            try {
                lock.release()
            } finally {
                try {
                    file.close()
                } finally {
                    reservations.remove(path)
                }
            }
        }
    }

    companion object {
        // Reserve before opening: closing even an *unlocked* second descriptor can release
        // this process's POSIX lock on the inode.
        private val reservations = HashSet<String>()

        /** Every one of [names] in [directory], or none; [busy] words the failure for the name held elsewhere. */
        fun acquire(directory: File, names: Collection<String>, busy: (String) -> String): Closeable {
            directory.mkdirs()
            check(directory.isDirectory) { "Cannot create lock directory ${directory.name}" }
            val acquired = ArrayList<ProcessLease>()
            try {
                for (name in names.distinct().sorted()) {
                    val path = File(directory, "$name.lock").canonicalPath
                    synchronized(reservations) {
                        if (!reservations.add(path)) throw LeaseBusyException(busy(name))
                        var file: RandomAccessFile? = null
                        try {
                            file = RandomAccessFile(path, "rw")
                            val lock = file.channel.tryLock() ?: throw LeaseBusyException(busy(name))
                            acquired += ProcessLease(path, file, lock)
                        } catch (e: Throwable) {
                            try {
                                file?.close()
                            } catch (closeError: Throwable) {
                                e.addSuppressed(closeError)
                            } finally {
                                reservations.remove(path)
                            }
                            throw e
                        }
                    }
                }
            } catch (e: Throwable) {
                try {
                    closeAll(acquired)
                } catch (closeError: Throwable) {
                    e.addSuppressed(closeError)
                }
                throw e
            }
            return Closeable { closeAll(acquired) }
        }

        private fun closeAll(leases: List<ProcessLease>) {
            var failure: Throwable? = null
            for (lease in leases.asReversed()) {
                try {
                    lease.close()
                } catch (e: Throwable) {
                    if (failure == null) failure = e else failure.addSuppressed(e)
                }
            }
            failure?.let { throw it }
        }
    }
}
