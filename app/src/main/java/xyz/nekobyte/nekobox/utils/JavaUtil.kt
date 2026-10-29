package xyz.nekobyte.nekobox.utils

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import xyz.nekobyte.nekobox.BuildConfig

object JavaUtil {
    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentProcessName").invoke(null) as? String
                ?: BuildConfig.APPLICATION_ID
        } catch (_: Exception) {
            BuildConfig.APPLICATION_ID
        }
    }

    @JvmField
    val gson = GsonBuilder()
        .setPrettyPrinting()
        .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setLenient()
        .disableHtmlEscaping()
        .create()
}
