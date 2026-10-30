package xyz.nekobyte.nekobox.ui

/**
 * A group's profiles in sections, from their names alone: profiles whose names differ only by a
 * trailing number ("Bypass № 1" to "Bypass № 16", "Auto proxy-2") form one, and the rest form one
 * more. Subscriptions carry no other hint of which servers belong together.
 */
internal object ProfileSections {
    /** One section: [members] are indexes into the names. [title] is null for the rest, which [allFlags] says are all countries. */
    class Section(val key: String, val title: String?, val members: List<Int>, val allFlags: Boolean = false)

    // The key of the section holding the profiles no other section takes.
    const val REST = ""

    // Fewer profiles than this sharing a name stay with the rest: two "Germany" servers need no fold.
    private const val MIN_SIZE = 3

    // Words that only lead a number ("№ 3", "# 3", "- 3"); a title does not end with them.
    private val NUMBER_MARKS = setOf("№", "#", "No.", "-", "–", "|", "·", ":")

    private val SPACE = Regex("\\s+")

    /** The sections in the order their first profiles appear, or none when the names share nothing. */
    fun of(names: List<String>): List<Section> {
        val stems = names.map(::stem)
        val shared = stems.groupingBy { it }.eachCount().filter { (stem, count) -> stem.isNotEmpty() && count >= MIN_SIZE }.keys
        if (shared.isEmpty()) return emptyList()
        val members = LinkedHashMap<String, MutableList<Int>>()
        names.indices.forEach { members.getOrPut(stems[it].takeIf(shared::contains) ?: REST) { mutableListOf() } += it }
        // One section would only repeat the group.
        if (members.size < 2) return emptyList()
        return members.map { (key, indexes) ->
            if (key == REST) {
                Section(key, null, indexes, indexes.all { ProfileWords.nameFlag(names[it].trim().take(4)) != null })
            } else {
                Section(key, title(indexes.map(names::get), key), indexes)
            }
        }
    }

    // A trailing number with the marks around it: "-2", " № 16 ★". Symbols alone stay part of a name.
    private val TRAILING_NUMBER = Regex("^(.*?)[^\\p{L}\\p{N}]*\\p{N}+[^\\p{L}\\p{N}]*$")

    /** The name without a trailing number and the symbols around it. */
    fun stem(name: String): String {
        val trimmed = name.trim()
        return TRAILING_NUMBER.find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    // The words all names begin with, without a trailing number mark; the stem when they share none.
    private fun title(names: List<String>, stem: String): String {
        var common = names.first().trim().split(SPACE)
        for (name in names.drop(1)) {
            common = common.zip(name.trim().split(SPACE)).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return common.dropLastWhile { it in NUMBER_MARKS }.joinToString(" ").ifEmpty { stem }
    }
}
