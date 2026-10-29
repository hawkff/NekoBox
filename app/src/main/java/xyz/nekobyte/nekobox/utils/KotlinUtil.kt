package xyz.nekobyte.nekobox.utils

import xyz.nekobyte.nekobox.NekoBox

const val KB = 1024L
const val MB = KB * 1024
const val GB = MB * 1024

// Builds before 2.0.0 showed the dashboard in a WebView; its data, the Clash API secret included, goes.
fun NekoBox.removeWebviewData() {
    filesDir.parentFile?.listFiles { file -> file.name.startsWith("app_webview") || file.name.startsWith("app_hws_webview") }
        ?.forEach { it.deleteRecursively() }
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
