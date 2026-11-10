package xyz.nekobyte.nekobox.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** The local port a connected service listens on for the app's own requests, and the login it takes: none when empty. */
@Parcelize
class LocalProxy(val port: Int, val user: String, val password: String) : Parcelable
