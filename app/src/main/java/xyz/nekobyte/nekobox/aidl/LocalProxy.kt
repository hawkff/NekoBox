package xyz.nekobyte.nekobox.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * The local port a connected service listens on for the app's own requests, and the login it takes: none when empty.
 * [start] is another one for every start of the service, with a login or without: by it a client tells whether the
 * start it was set up in still runs.
 */
@Parcelize
class LocalProxy(val port: Int, val user: String, val password: String, val start: String = UUID.randomUUID().toString()) : Parcelable
