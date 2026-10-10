package xyz.nekobyte.nekobox.bg

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.text.isDigitsOnly
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File
import java.io.IOException

object Executable {
    private val EXECUTABLES = setOf(
        "libnaive.so",
        "libhysteria.so",
        "libmieru.so",
    )

    fun killAll(alsoKillBg: Boolean = false) {
        // kill bg may fail
        for (process in File("/proc").listFiles { _, name -> name.isDigitsOnly() } ?: return) {
            val exe = File(
                try {
                    File(process, "cmdline").inputStream().bufferedReader().use {
                        it.readText()
                    }
                } catch (_: IOException) {
                    continue
                }.split(Character.MIN_VALUE, limit = 2).first(),
            )
            if (EXECUTABLES.contains(exe.name) || (alsoKillBg && exe.name.endsWith(":bg"))) {
                try {
                    Os.kill(process.name.toInt(), OsConstants.SIGKILL)
                    Logs.w("SIGKILL ${exe.name} (${process.name}) succeed")
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.ESRCH) {
                        Logs.w("SIGKILL ${exe.absolutePath} (${process.name}) failed")
                        Logs.w(e)
                    }
                }
            }
        }
    }
}
