package xyz.nekobyte.nekobox.ui

/**
 * The Proxies screen: lists of proxies the user pays for or runs (SOCKS5 and HTTP with a login, such as
 * Mullvad's or a residential provider's), kept apart from Home. It is Home's screen showing only those lists.
 */
class ProxiesFragment : ConfigurationFragment(proxies = true)
