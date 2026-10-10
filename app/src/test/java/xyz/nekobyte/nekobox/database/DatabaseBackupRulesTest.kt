package xyz.nekobyte.nekobox.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DatabaseBackupRulesTest {
    @Test
    fun excludesEntireDatabaseDirectoriesForBothBackupFormats() {
        for ((file, scopes) in mapOf(
            "backup_descriptor.xml" to listOf("full-backup-content"),
            "backup_rules.xml" to listOf("cloud-backup", "device-transfer"),
        )) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/xml/$file"))
            for (scope in scopes) {
                val sections = document.getElementsByTagName(scope)
                assertEquals(1, sections.length)
                val excludes = (sections.item(0) as Element).getElementsByTagName("exclude")
                val domains = (0 until excludes.length).map { excludes.item(it) as Element }
                    .filter { it.getAttribute("path") == "." }
                    .map { it.getAttribute("domain") }.toSet()
                assertEquals("$file/$scope", setOf("database", "device_database"), domains)
            }
        }
    }
}
