package xyz.nekobyte.nekobox.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTrustStoreTest {

    private val aliases = listOf("system:a.0", "user:b.0", "other:c.0", "d.0")

    @Test
    fun trustedAliasesKeepAndroidRootsAndUserOnesOnlyWhenTrusted() {
        assertEquals(listOf("system:a.0"), AndroidTrustStore.trustedAliases(aliases, mozilla = false, trustUserCertificates = false))
        assertEquals(listOf("system:a.0", "user:b.0"), AndroidTrustStore.trustedAliases(aliases, mozilla = false, trustUserCertificates = true))
    }

    @Test
    fun trustedAliasesLeaveAndroidRootsToTheMozillaList() {
        assertEquals(emptyList<String>(), AndroidTrustStore.trustedAliases(aliases, mozilla = true, trustUserCertificates = false))
        assertEquals(listOf("user:b.0"), AndroidTrustStore.trustedAliases(aliases, mozilla = true, trustUserCertificates = true))
    }
}
