package xyz.nekobyte.nekobox.utils

import xyz.nekobyte.nekobox.NekoBox
import xyz.nekobyte.nekobox.ktx.Logs
import java.io.File

const val KB = 1024L
const val MB = KB * 1024
const val GB = MB * 1024

fun NekoBox.cleanWebview() {
    var pathToClean = "app_webview"
    if (isBgProcess) pathToClean += "_$process"
    try {
        val dataDir = filesDir.parentFile!!
        File(dataDir, "$pathToClean/BrowserMetrics").recreate(true)
        File(dataDir, "$pathToClean/BrowserMetrics-spare.pma").recreate(false)
    } catch (e: Exception) {
        Logs.e(e)
    }
}

fun File.recreate(dir: Boolean) {
    if (parentFile?.isDirectory != true) return
    if (dir && !isFile) {
        if (exists()) deleteRecursively()
        createNewFile()
    } else if (!dir && !isDirectory) {
        if (exists()) delete()
        mkdir()
    }
}

// Traffic display

fun Long.toBytesString(): String {
    val size = this.toDouble()
    return when {
        this >= GB -> String.format("%.2f GiB", size / GB)
        this >= MB -> String.format("%.2f MiB", size / MB)
        this >= KB -> String.format("%.2f KiB", size / KB)
        else -> "$this Bytes"
    }
}

// List

fun String.listByLineOrComma(): List<String> = this.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
