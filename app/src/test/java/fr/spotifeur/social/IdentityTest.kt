package fr.spotifeur.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    // NIP-06's own test vector.
    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean"

    @Test
    fun recoveryWordsGiveTheNip06Key() {
        val keys = SocialKeys.fromWords(words)!!
        assertEquals("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a", keys.privateKeyHex)
        assertEquals("17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917", keys.publicKeyHex)
        assertEquals(words, keys.words)
    }

    @Test
    fun aNewIdentityHasTwelveWordsThatRestoreIt() {
        val created = SocialKeys.create()
        val words = created.words!!
        assertEquals(12, words.split(" ").size)
        assertEquals(created.publicKeyHex, SocialKeys.fromWords(words)!!.publicKeyHex)
        assertNotEquals(created.publicKeyHex, SocialKeys.create().publicKeyHex)
    }

    @Test
    fun wrongWordsAreRefused() {
        assertNull(SocialKeys.fromWords("leader monkey parrot ring guide accident before fence cannon height naive naive"))
        assertNull(SocialKeys.fromWords("not twelve words"))
    }

    @Test
    fun thePasswordExportOpensOnlyWithItsPassword() {
        val keys = SocialKeys.fromWords(words)!!
        val export = keys.exportEncrypted("correct horse battery")
        assertTrue(export.startsWith("ncryptsec1"))
        assertEquals(keys.publicKeyHex, SocialKeys.fromExport(export, "correct horse battery")!!.publicKeyHex)
        assertNull(SocialKeys.fromExport(export, "wrong password"))
    }

    @Test
    fun theHandleTagIsFourStableCrockfordCharacters() {
        val pub = SocialKeys.fromWords(words)!!.publicKeyHex
        val tag = Handles.tag(pub)
        assertEquals(4, tag.length)
        assertTrue(tag.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertEquals(tag, Handles.tag(pub))
        assertEquals("Sara#$tag", Handles.handle("Sara", pub))
        assertNotEquals(tag, Handles.tag(SocialKeys.create().publicKeyHex))
    }
}
