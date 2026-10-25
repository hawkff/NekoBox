package xyz.nekobyte.nekobox.ui

import android.content.Context
import android.text.format.DateUtils
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.database.SubscriptionBean
import xyz.nekobyte.nekobox.utils.toBytesString

/** What providers report about subscriptions, in the words the group list and editor share. */
object GroupWords {
    /** Bytes used, the total when the provider sets one, and when the subscription ends, in milliseconds. */
    class Quota(val used: Long, val total: Long?, val ends: Long?) {
        /** Whether little is left: a tenth of the traffic, or three days. */
        fun low(now: Long) = (total != null && used * 10 >= total * 9) || (daysLeft(now)?.let { it <= 3 } == true)

        fun daysLeft(now: Long) = ends?.let { ((it - now) / DateUtils.DAY_IN_MILLIS).toInt() }
    }

    /** From the provider's `subscription-userinfo`: upload and download, total and expire. */
    fun quota(subscription: SubscriptionBean?): Quota? {
        subscription ?: return null
        val ends = subscription.expiry()?.times(1000)
        val info = subscription.subscriptionUserinfo.orEmpty()
        fun field(name: String) = Regex("$name=([0-9]+)").find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val used = field("upload") + field("download")
        val total = field("total")
        if (used <= 0 && total <= 0 && ends == null) return null
        return Quota(used, total.takeIf { it > 0 }, ends)
    }

    /** "8.70 GiB used of 50.00 GiB", "108.00 MiB used", or null when nothing was used and there is no total. Binary units, as panels count. */
    fun traffic(context: Context, quota: Quota?): String? {
        quota ?: return null
        val total = quota.total
        if (quota.used <= 0 && total == null) return null
        val used = quota.used.toBytesString()
        return if (total != null) context.getString(R.string.group_traffic_of, used, total.toBytesString()) else context.getString(R.string.group_traffic_used, used)
    }

    /** "ends 20 Nov" or "ended 3 Oct". */
    fun ends(context: Context, quota: Quota?, now: Long): String? = quota?.ends?.let {
        val date = DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        context.getString(if (it < now) R.string.group_ended else R.string.group_ends, date)
    }

    /** "updated 3 days ago", "updated just now" or "not updated yet". */
    fun updated(context: Context, subscription: SubscriptionBean, now: Long): String {
        val at = (subscription.lastUpdated ?: 0) * 1000L
        if (at <= 0) return context.getString(R.string.group_not_updated)
        if (now - at < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.group_updated_now)
        return context.getString(R.string.group_updated_ago, DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS))
    }

    /** An update interval in minutes as people say it: "1 day", "6 hours", "90 minutes". */
    fun interval(context: Context, minutes: Int): String {
        val resources = context.resources
        return when {
            minutes % (24 * 60) == 0 -> (minutes / (24 * 60)).let { resources.getQuantityString(R.plurals.group_days, it, it) }
            minutes % 60 == 0 -> (minutes / 60).let { resources.getQuantityString(R.plurals.group_hours, it, it) }
            else -> resources.getQuantityString(R.plurals.group_minutes, minutes, minutes)
        }
    }
}
