package fr.spotifeur.spotifire.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNotesTest {
    @Test
    fun wrappedItemsAreJoinedAndMarkdownIsDropped() {
        val notes = "### Fixed\n\n- Friends stays **connected**: a card lets it run without\n  battery limits.\n- Jam [invites](https://x) last\n  2 hours.\n"
        assertEquals("Fixed\n\n• Friends stays connected: a card lets it run without battery limits.\n• Jam invites last 2 hours.", plain(notes))
    }
}
