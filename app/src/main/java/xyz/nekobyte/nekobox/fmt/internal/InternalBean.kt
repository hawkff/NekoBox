package xyz.nekobyte.nekobox.fmt.internal

import xyz.nekobyte.nekobox.fmt.AbstractBean

abstract class InternalBean : AbstractBean() {
    override fun displayAddress() = ""
    override fun canICMPing() = false
    override fun canTCPing() = false
    override fun canMapping() = false
}
