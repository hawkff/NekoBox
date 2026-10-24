package xyz.nekobyte.nekobox.net

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.nekobyte.nekobox.net.NatBehavior.ADDRESS_AND_PORT_DEPENDENT
import xyz.nekobyte.nekobox.net.NatBehavior.ADDRESS_DEPENDENT
import xyz.nekobyte.nekobox.net.NatBehavior.ENDPOINT_INDEPENDENT
import xyz.nekobyte.nekobox.net.NatBehavior.Type

class NatBehaviorTest {
    @Test
    fun theMappingDecidesConeOrSymmetricAndTheFilteringWhichCone() {
        assertEquals(Type.FULL_CONE, NatBehavior.type(false, ENDPOINT_INDEPENDENT, ENDPOINT_INDEPENDENT))
        assertEquals(Type.RESTRICTED_CONE, NatBehavior.type(false, ENDPOINT_INDEPENDENT, ADDRESS_DEPENDENT))
        assertEquals(Type.PORT_RESTRICTED_CONE, NatBehavior.type(false, ENDPOINT_INDEPENDENT, ADDRESS_AND_PORT_DEPENDENT))
        // The filtering test stopped early.
        assertEquals(Type.CONE, NatBehavior.type(false, ENDPOINT_INDEPENDENT, ""))
        for (mapping in listOf(ADDRESS_DEPENDENT, ADDRESS_AND_PORT_DEPENDENT)) {
            for (filtering in listOf(ENDPOINT_INDEPENDENT, ADDRESS_DEPENDENT, ADDRESS_AND_PORT_DEPENDENT, "")) {
                assertEquals("$mapping $filtering", Type.SYMMETRIC, NatBehavior.type(false, mapping, filtering))
            }
        }
        assertEquals(Type.UNKNOWN, NatBehavior.type(false, "", ""))
        assertEquals(Type.NONE, NatBehavior.type(true, "", ""))
    }
}
