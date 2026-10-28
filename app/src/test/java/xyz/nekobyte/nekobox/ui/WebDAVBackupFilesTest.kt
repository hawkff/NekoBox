package xyz.nekobyte.nekobox.ui

import android.app.Application
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class WebDAVBackupFilesTest {
    @Test
    fun listingAcceptsOnlyBackupFilesDirectlyInTheConfiguredDirectory() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/NekoBox/nekobox_backup_20261001_120000.json</D:href></D:response>
              <D:response><D:href>nekobox_backup_20261002_120000.zip</D:href></D:response>
              <D:response><D:href>https://other.example/dav/NekoBox/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/other/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/sub/nekobox_backup_other.json</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/nekobox_backup_other.json?secret=x</D:href></D:response>
              <D:response><D:href>/dav/NekoBox/not-a-backup.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        val files = WebDAVSecurity.backupFiles(xml, "https://dav.example/dav/NekoBox".toHttpUrl())
        assertEquals(listOf("nekobox_backup_20261002_120000.zip", "nekobox_backup_20261001_120000.json"), files)
    }

    @Test
    fun xmlEntitiesAndFileTraversalAreRejected() {
        val xml = """<!DOCTYPE root [<!ENTITY file SYSTEM "file:///data/private">]><root>&file;</root>"""
        assertThrows(IllegalArgumentException::class.java) { WebDAVSecurity.backupFiles(xml, "https://dav.example/backups".toHttpUrl()) }
        for (name in listOf("../nekobox_backup_a.json", "nekobox_backup_a/other.json", "nekobox_backup_a\\other.zip", "nekobox_backup_a\u0000.zip", "nekobox_backup_a.txt")) {
            assertFalse(name, WebDAVSecurity.isBackupName(name))
        }
        assertTrue(WebDAVSecurity.isBackupName("nekobox_backup_20261001_120000.zip"))
    }

    @Test
    fun arbitraryAndDefaultNamespacePrefixesWork() {
        for (prefix in listOf("D", "d", "w", "custom", "")) {
            val qualifier = if (prefix.isEmpty()) "" else "$prefix:"
            val namespace = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
            val xml = """<${qualifier}multistatus $namespace="DAV:"><${qualifier}response><${qualifier}href>/backups/nekobox_backup_20261011_120000.zip</${qualifier}href></${qualifier}response></${qualifier}multistatus>"""
            assertEquals(listOf("nekobox_backup_20261011_120000.zip"), WebDAVSecurity.backupFiles(xml, "https://dav.example/backups".toHttpUrl()))
        }
    }

    @Test
    fun unrelatedNamespacesAndOversizedListingsAreRejected() {
        val directory = "https://dav.example/backups".toHttpUrl()
        val foreign = """<multistatus xmlns="other:"><href>nekobox_backup_20261011_120000.zip</href></multistatus>"""
        assertTrue(WebDAVSecurity.backupFiles(foreign, directory).isEmpty())
        val large = "<multistatus xmlns=\"DAV:\">" + (0..1000).joinToString("") { "<href>nekobox_backup_$it.json</href>" } + "</multistatus>"
        assertThrows(IllegalArgumentException::class.java) { WebDAVSecurity.backupFiles(large, directory) }
    }
}
