package xyz.nekobyte.nekobox.net

/** What the core's RFC 5780 NAT behavior test found, named as the classic NAT types. */
object NatBehavior {
    // The behaviors and problems as the core names them.
    const val ENDPOINT_INDEPENDENT = "endpoint_independent"
    const val ADDRESS_DEPENDENT = "address_dependent"
    const val ADDRESS_AND_PORT_DEPENDENT = "address_and_port_dependent"
    const val UNREACHABLE = "unreachable"
    const val UNSUPPORTED = "unsupported"

    enum class Type { NONE, FULL_CONE, RESTRICTED_CONE, PORT_RESTRICTED_CONE, CONE, SYMMETRIC, UNKNOWN }

    /** A NAT that keeps one public port for every destination is a cone, which its filtering narrows; any other is symmetric. */
    fun type(noNat: Boolean, mapping: String, filtering: String) = when {
        noNat -> Type.NONE

        mapping == ENDPOINT_INDEPENDENT -> when (filtering) {
            ENDPOINT_INDEPENDENT -> Type.FULL_CONE
            ADDRESS_DEPENDENT -> Type.RESTRICTED_CONE
            ADDRESS_AND_PORT_DEPENDENT -> Type.PORT_RESTRICTED_CONE
            else -> Type.CONE
        }

        mapping.isNotEmpty() -> Type.SYMMETRIC

        else -> Type.UNKNOWN
    }
}
