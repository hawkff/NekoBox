package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateInstallTest {
    private val installed = PackageIdentity("xyz.nekobyte.nekobox", 100, setOf("release-key"))

    @Test
    fun anUpdateIsTheSamePackageLaterAndSignedAlike() {
        assertTrue(isUpdateOf(installed.copy(versionCode = 105), installed))
        assertFalse("another package", isUpdateOf(installed.copy(packageName = "xyz.nekobyte.nekobox.debug", versionCode = 105), installed))
        assertFalse("the same version", isUpdateOf(installed, installed))
        assertFalse("an older version", isUpdateOf(installed.copy(versionCode = 95), installed))
        assertFalse("another signer", isUpdateOf(installed.copy(versionCode = 105, signers = setOf("other-key")), installed))
        assertFalse("an added signer", isUpdateOf(installed.copy(versionCode = 105, signers = setOf("release-key", "other-key")), installed))
        assertFalse("no signer", isUpdateOf(installed.copy(versionCode = 105, signers = emptySet()), installed.copy(signers = emptySet())))
    }
}
