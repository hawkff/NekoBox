package xyz.nekobyte.nekobox.fmt.tailscale

import java.security.MessageDigest

/**
 * The machine name of a node without one: two random words, as Mullvad names devices. The node's
 * identity marker picks the words, so the name stays the same across starts and changes when the
 * identity is reset. It says nothing about the phone or its owner, unlike the device name that the
 * official Tailscale client sends.
 */
internal object TailscaleNames {
    private val ADJECTIVES = listOf(
        "able", "agile", "amber", "ample", "bold", "brave", "bright", "brisk",
        "calm", "candid", "clever", "cosmic", "crisp", "curious", "daring", "deft",
        "eager", "early", "easy", "fair", "fast", "fond", "frank", "gentle",
        "glad", "golden", "grand", "happy", "hardy", "honest", "jolly", "keen",
        "kind", "lively", "loyal", "lucid", "lucky", "mellow", "merry", "mighty",
        "modest", "neat", "nimble", "noble", "polite", "proud", "quick", "quiet",
        "rapid", "ready", "regal", "round", "sharp", "silent", "sleek", "smart",
        "snowy", "steady", "sunny", "swift", "tidy", "vivid", "warm", "witty",
    )
    private val ANIMALS = listOf(
        "badger", "beaver", "bison", "bobcat", "crane", "cricket", "dingo", "dolphin",
        "eagle", "egret", "falcon", "ferret", "finch", "fox", "gazelle", "gecko",
        "gopher", "heron", "hippo", "ibis", "iguana", "jackal", "jaguar", "koala",
        "lemur", "leopard", "lion", "llama", "lynx", "magpie", "marten", "mink",
        "moose", "narwhal", "newt", "ocelot", "otter", "owl", "panda", "panther",
        "parrot", "pelican", "penguin", "puffin", "quail", "rabbit", "raven", "robin",
        "salmon", "seal", "sparrow", "stork", "swan", "tapir", "tiger", "toucan",
        "turtle", "walrus", "weasel", "whale", "wolf", "wombat", "yak", "zebra",
    )

    fun of(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())

        // 65536 is a multiple of 64, so every word is equally likely.
        fun pick(words: List<String>, offset: Int) = words[((digest[offset].toInt() and 0xFF) shl 8 or (digest[offset + 1].toInt() and 0xFF)) % words.size]
        return pick(ADJECTIVES, 0) + "-" + pick(ANIMALS, 2)
    }
}
