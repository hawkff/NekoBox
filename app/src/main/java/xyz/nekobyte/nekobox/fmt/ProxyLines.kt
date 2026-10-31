package xyz.nekobyte.nekobox.fmt

import xyz.nekobyte.nekobox.fmt.http.HttpBean
import xyz.nekobyte.nekobox.fmt.http.parseHttp
import xyz.nekobyte.nekobox.fmt.socks.SOCKSBean
import xyz.nekobyte.nekobox.fmt.socks.parseSOCKS

/**
 * Proxies as providers list them, one a line: host:port, host:port:user:password, user:password@host:port,
 * user:password:host:port, or a socks5://, socks4://, http:// or https:// link. A line without a scheme is a
 * proxy of the [Plain] kind picked for the paste. Blank lines and lines starting with # are not proxies and are
 * not counted as skipped.
 */
object ProxyLines {
    enum class Plain { SOCKS5, HTTP }

    class Result(val proxies: List<AbstractBean>, val skipped: Int)

    fun parse(text: String, plain: Plain): Result {
        val proxies = ArrayList<AbstractBean>()
        var skipped = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val bean = runCatching { parseLine(line, plain) }.getOrNull()
            if (bean == null) skipped++ else proxies += bean
        }
        // Rotating and sticky residential sessions share one gateway and differ by login: the login tells them apart.
        val shared = proxies.groupingBy { it.serverAddress to it.serverPort }.eachCount().filterValues { it > 1 }.keys
        for (bean in proxies) {
            val user = when (bean) {
                is SOCKSBean -> bean.username
                is HttpBean -> bean.username
                else -> null
            }
            if (bean.name.isNullOrBlank() && !user.isNullOrBlank() && (bean.serverAddress to bean.serverPort) in shared) {
                bean.name = "${bean.serverAddress}:${bean.serverPort} · $user"
            }
        }
        return Result(proxies, skipped)
    }

    internal fun parseLine(line: String, plain: Plain): AbstractBean? {
        if (line.any { it.isWhitespace() }) return null
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty()) {
            val bean = when (scheme) {
                "socks", "socks5", "socks5h", "socks4", "socks4a" -> parseSOCKS(line)
                "http", "https" -> parseHttp(line)
                else -> return null
            }
            return bean.takeIf { valid(it.serverAddress, it.serverPort) }
        }
        val at = line.lastIndexOf('@')
        val login: Pair<String, String>
        val address: Pair<String, Int>
        if (at >= 0) {
            val credentials = line.substring(0, at)
            login = credentials.substringBefore(':') to credentials.substringAfter(':', "")
            address = hostPort(line.substring(at + 1)) ?: return null
        } else if (line.startsWith("[")) {
            // [IPv6]:port, then an optional :user:password.
            val host = line.substring(1, line.indexOf(']').takeIf { it > 0 } ?: return null)
            val rest = line.substringAfter("]:", "").split(':', limit = 3)
            address = host to (port(rest[0]) ?: return null)
            login = rest.getOrElse(1) { "" } to rest.getOrElse(2) { "" }
        } else {
            val fields = line.split(':')
            when {
                fields.size == 2 -> {
                    address = fields[0] to (port(fields[1]) ?: return null)
                    login = "" to ""
                }

                // user:password:host:port, when the third field names the host and the first does not.
                fields.size == 4 && port(fields[3]) != null && (port(fields[1]) == null || (hostLike(fields[2]) && !hostLike(fields[0]))) -> {
                    address = fields[2] to port(fields[3])!!
                    login = fields[0] to fields[1]
                }

                // host:port:user:password, the password free to hold colons.
                fields.size >= 3 && port(fields[1]) != null -> {
                    address = fields[0] to port(fields[1])!!
                    login = fields[2] to fields.drop(3).joinToString(":")
                }

                else -> return null
            }
        }
        if (!valid(address.first, address.second)) return null
        val (user, password) = login
        return when (plain) {
            Plain.SOCKS5 -> SOCKSBean().apply {
                protocol = SOCKSBean.PROTOCOL_SOCKS5
                username = user
                this.password = password
            }

            Plain.HTTP -> HttpBean().apply {
                username = user
                this.password = password
            }
        }.apply {
            serverAddress = address.first
            serverPort = address.second
            initializeDefaultValues()
        }
    }

    private fun hostPort(text: String): Pair<String, Int>? {
        if (text.startsWith("[")) {
            val end = text.indexOf(']').takeIf { it > 0 } ?: return null
            return text.substring(1, end) to (port(text.substringAfter("]:", "")) ?: return null)
        }
        val colon = text.lastIndexOf(':').takeIf { it > 0 } ?: return null
        return text.substring(0, colon) to (port(text.substring(colon + 1)) ?: return null)
    }

    private fun port(text: String) = text.toIntOrNull()?.takeIf { it in 1..65535 }

    // A host name has a dot; so does an IPv4 address.
    private fun hostLike(text: String) = '.' in text

    private fun valid(host: String?, port: Int?) = !host.isNullOrBlank() && host.none { it == '/' || it == '@' } && port != null && port in 1..65535
}
