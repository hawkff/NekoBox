package xyz.nekobyte.nekobox.utils

import android.content.Context
import android.util.Base64
import xyz.nekobyte.nekobox.ktx.Logs
import java.net.URLDecoder

object NGUtil {
    fun parseInt(str: String): Int = try {
        Integer.parseInt(str)
    } catch (e: Exception) {
        e.printStackTrace()
        0
    }

    fun decode(text: String): String {
        tryDecodeBase64(text)?.let { return it }
        if (text.endsWith('=')) {
            tryDecodeBase64(text.trimEnd('='))?.let { return it }
        }
        return ""
    }

    fun tryDecodeBase64(text: String): String? {
        try {
            return Base64.decode(text, Base64.NO_WRAP).toString(charset("UTF-8"))
        } catch (e: Exception) {
            Logs.i("Parse base64 standard failed $e")
        }
        try {
            return Base64.decode(text, Base64.NO_WRAP.or(Base64.URL_SAFE)).toString(charset("UTF-8"))
        } catch (e: Exception) {
            Logs.i("Parse base64 url safe failed $e")
        }
        return null
    }

    fun encode(text: String): String = try {
        Base64.encodeToString(text.toByteArray(charset("UTF-8")), Base64.NO_WRAP)
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }

    fun isIpv4Address(value: String): Boolean {
        val regV4 =
            Regex(
                "^([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])$",
            )
        return regV4.matches(value)
    }

    fun isIpv6Address(value: String): Boolean {
        var addr = value
        if (addr.indexOf("[") == 0 && addr.lastIndexOf("]") > 0) {
            addr = addr.drop(1)
            addr = addr.dropLast(addr.count() - addr.lastIndexOf("]"))
        }
        val regV6 =
            Regex(
                "^((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*::((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*|((?:[0-9A-Fa-f]{1,4}))((?::[0-9A-Fa-f]{1,4})){7}$",
            )
        return regV6.matches(addr)
    }

    fun urlDecode(url: String): String = try {
        URLDecoder.decode(url, "UTF-8")
    } catch (e: Exception) {
        url
    }

    fun readTextFromAssets(context: Context, fileName: String): String = context.assets.open(fileName).bufferedReader().use { it.readText() }
}
