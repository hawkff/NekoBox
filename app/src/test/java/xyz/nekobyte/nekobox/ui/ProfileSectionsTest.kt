package xyz.nekobyte.nekobox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSectionsTest {

    @Test
    fun namesThatDifferOnlyByANumberFoldTogether() {
        // Shaped like a panel subscription: the members of an auto-select config, single countries and numbered bypass servers.
        val names = listOf(
            "Auto select ( best ) proxy",
            "Auto select ( best ) proxy-2",
            "Auto select ( best ) proxy-5",
            "🇺🇸 United States",
            "🇱🇹 Lithuania",
            " 🇮🇹 Italy",
            "🇳🇱 Netherlands - Torrent ✔",
            "🇸🇴 Bypass № 1 ★",
            "🇸🇴 Bypass № 2 ★",
            "🇸🇴 Bypass № 7",
            "🇸🇴 Bypass № 16 ★",
        )
        val sections = ProfileSections.of(names)
        assertEquals(listOf("Auto select ( best )", null, "🇸🇴 Bypass"), sections.map { it.title })
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7, 8, 9, 10)), sections.map { it.members })
        assertTrue(sections[1].allFlags)
        assertEquals(ProfileSections.REST, sections[1].key)
    }

    @Test
    fun namesThatShareNothingStayFlat() {
        assertTrue(ProfileSections.of(listOf("Germany", "France", "Japan")).isEmpty())
        // Two of a kind are not worth a fold, and a single section would only repeat the group.
        assertTrue(ProfileSections.of(listOf("DE 1", "DE 2", "France")).isEmpty())
        assertTrue(ProfileSections.of(listOf("DE 1", "DE 2", "DE 3")).isEmpty())
    }

    @Test
    fun aTitleFallsBackToTheStemAndTheRestWithoutFlagsIsOther() {
        val sections = ProfileSections.of(listOf("Server-1", "Server-2", "Server-3", "Home"))
        assertEquals(listOf("Server", null), sections.map { it.title })
        assertFalse(sections[1].allFlags)
    }
}
