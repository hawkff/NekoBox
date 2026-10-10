package xyz.nekobyte.nekobox.bg

import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import libcore.Libcore
import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.ktx.Logs
import xyz.nekobyte.nekobox.utils.Commandline
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

internal fun shouldFailAfterProcessExit(processUptimeMillis: Long) = processUptimeMillis < 1_000L

class GuardedProcessPool(private val onFatal: suspend (IOException) -> Unit) : CoroutineScope {
    companion object {
        private val pid by lazy {
            Class.forName("java.lang.ProcessManager\$ProcessImpl").getDeclaredField("pid")
                .apply { isAccessible = true }
        }
    }

    private inner class Guard(private val cmd: List<String>, private val env: Map<String, String>) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }

        fun start() {
            process = ProcessBuilder(cmd).directory(NekoBox.application.noBackupFilesDir).apply {
                environment().putAll(env)
            }.start()
        }

        private fun watchProcess(cmdName: String, exitChannel: Channel<Int>) {
            val proc = process
            thread(name = "stderr-$cmdName") {
                streamLogger(proc.errorStream) {
                    Libcore.nekoLogPrintln("[$cmdName] ${Commandline.redactProcessOutput(it)}")
                }
            }
            thread(name = "stdout-$cmdName") {
                streamLogger(proc.inputStream) {
                    Libcore.nekoLogPrintln("[$cmdName] ${Commandline.redactProcessOutput(it)}")
                }
            }
            // Each process owns a buffered channel so teardown cannot wait on a later generation.
            thread(name = "waitFor-$cmdName") {
                val code = proc.waitFor()
                if (exitChannel.trySendBlocking(code).isFailure) {
                    Logs.w("$cmdName: could not deliver exit code $code (channel closed)")
                }
            }
        }

        private fun signalProcess(signal: Int) {
            try {
                Os.kill(pid.get(process) as Int, signal)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ESRCH) Logs.w(e)
            } catch (e: ReflectiveOperationException) {
                Logs.w(e)
            }
        }

        private suspend fun terminateProcess(exitChannel: Channel<Int>): Int? = withContext(NonCancellable) {
            exitChannel.tryReceive().getOrNull()?.let { return@withContext it }
            if (Build.VERSION.SDK_INT < 24) {
                signalProcess(OsConstants.SIGTERM)
                withTimeoutOrNull(500) { exitChannel.receive() }?.let { return@withContext it }
            }
            process.destroy()
            withTimeoutOrNull(1000) { exitChannel.receive() }?.let { return@withContext it }
            if (Build.VERSION.SDK_INT >= 26) {
                process.destroyForcibly()
            } else {
                signalProcess(OsConstants.SIGKILL)
            }
            withTimeoutOrNull(1000) { exitChannel.receive() }
        }

        suspend fun looper() {
            var running = true
            var currentExitChannel: Channel<Int>? = null
            val cmdName = File(cmd.first()).nameWithoutExtension
            try {
                while (true) {
                    val exitChannel = Channel<Int>(capacity = 1)
                    currentExitChannel = exitChannel
                    watchProcess(cmdName, exitChannel)
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    currentExitChannel = null
                    exitChannel.close()
                    if (shouldFailAfterProcessExit(SystemClock.elapsedRealtime() - startTime)) {
                        throw IOException("$cmdName exited (exit code: $exitCode)")
                    }
                    when (exitCode) {
                        128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: ${Commandline.toRedactedString(cmd)} (last exit code: $exitCode)")
                    start()
                    running = true
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toRedactedString(cmd)}")
                this@GuardedProcessPool.launch(Dispatchers.Main.immediate) { onFatal(e) }
            } finally {
                val exitChannel = currentExitChannel
                if (running && exitChannel != null) {
                    terminateProcess(exitChannel)
                } else if (running) {
                    process.destroy()
                }
            }
        }
    }

    override val coroutineContext = Dispatchers.Main.immediate + Job()
    var processCount = 0

    @MainThread
    fun start(cmd: List<String>, env: Map<String, String> = emptyMap()) {
        Logs.i("start process: ${Commandline.toRedactedString(cmd)}")
        Guard(cmd, env).apply {
            start()
            launch { looper() }
        }
        processCount += 1
    }

    @MainThread
    fun close(scope: CoroutineScope) {
        cancel()
        coroutineContext[Job]!!.also { job -> scope.launch { job.cancelAndJoin() } }
    }
}
