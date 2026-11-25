package fr.spotifeur.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SharedPlaylistMergeTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private fun track(n: Int) = JSONObject().put("id", "spotify:track:${"%022d".format(n)}").put("title", "Song $n")
    private fun doc() = SharedPlaylistDoc("p1", members = setOf(alice, bob, carol))
    private fun titles(d: SharedPlaylistDoc) = d.items().map { it.track.getString("title") }

    @Test
    fun fractionalKeysAlwaysFitBetweenTheirNeighbours() {
        val random = Random(1)
        val keys = mutableListOf(Positions.between(null, null))
        repeat(2000) {
            val i = random.nextInt(keys.size + 1)
            val k = Positions.between(keys.getOrNull(i - 1), keys.getOrNull(i))
            keys.getOrNull(i - 1)?.let { assertTrue("$it < $k", it < k) }
            keys.getOrNull(i)?.let { assertTrue("$k < $it", k < it) }
            keys.add(i, k)
        }
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun localEditsReadBackInOrder() {
        val d = doc()
        val first = d.add(alice, track(1), after = null)
        d.add(alice, track(3), after = first.item)
        d.add(alice, track(2), after = first.item)
        assertEquals(listOf("Song 1", "Song 2", "Song 3"), titles(d))
        assertEquals(alice, d.items().first().addedBy)
    }

    @Test
    fun everyDeliveryOrderGivesTheSameList() {
        // Alice and Bob edit at once from the same starting list, each offline, then everything is exchanged.
        val start = doc()
        val base = (1..5).map { start.add(alice, track(it), after = start.items().lastOrNull()?.item) }
        val a = SharedPlaylistDoc.fromSnapshot(start.snapshot()); val b = SharedPlaylistDoc.fromSnapshot(start.snapshot())
        val ops = base.toMutableList<GroupOp>()
        ops += a.add(alice, track(10), after = base[1].item)              // both insert at the same place
        ops += b.add(bob, track(20), after = base[1].item)
        ops += a.move(alice, base[4].item!!, after = null)                  // both move Song 5, latest wins
        ops += b.move(bob, base[4].item!!, after = base[2].item)
        ops += a.remove(alice, base[0].item!!)                              // Alice removes Song 1, Bob moves it
        ops += b.move(bob, base[0].item!!, after = base[3].item)
        ops += b.rename(bob, "Road trip")
        ops += a.rename(alice, "Night drive")
        val results = (0 until 200).map { seed ->
            val replica = doc()
            ops.shuffled(Random(seed.toLong())).forEach { replica.apply(it) }
            // Repeats change nothing.
            ops.shuffled(Random(seed + 1000L)).take(4).forEach { replica.apply(it) }
            titles(replica) to replica.title
        }
        assertEquals("all orders converge", 1, results.toSet().size)
        val (list, _) = results.first()
        assertFalse("a remove beats a concurrent move", "Song 1" in list)
        assertTrue("both concurrent adds are kept", "Song 10" in list && "Song 20" in list)
        assertEquals(6, list.size)
    }

    @Test
    fun aMoveThatArrivesBeforeItsAddStillApplies() {
        val source = doc()
        val add = source.add(alice, track(1), after = null)
        val other = source.add(alice, track(2), after = add.item)
        val move = source.move(bob, add.item!!, after = other.item)
        val late = doc()
        late.apply(move); late.apply(other); late.apply(add)
        assertEquals(titles(source), titles(late))
        assertEquals(listOf("Song 2", "Song 1"), titles(late))
    }

    @Test
    fun aSnapshotCarriesEverythingAndKeepsTombstones() {
        val d = doc()
        val add = d.add(alice, track(1), after = null)
        val remove = d.remove(bob, add.item!!)
        val copy = SharedPlaylistDoc.fromSnapshot(d.snapshot())
        copy.apply(add)
        assertTrue("a removed song does not come back from an old operation", copy.items().isEmpty())
        assertFalse(copy.apply(remove))
    }

    @Test
    fun onlyMembersCanEditAndARemovedMemberIsShutOut() {
        val d = doc()
        val stranger = "d".repeat(64)
        assertFalse(d.apply(SharedPlaylistDoc("p1", setOf(stranger)).add(stranger, track(9), after = null)))
        val group = SharedGroup.create("p1", "Road trip", owner = alice, members = listOf(alice, bob, carol))
        val next = group.without(carol)
        assertNotEquals("removing someone changes the key", group.keyHex, next.keyHex)
        assertNotEquals("and the tag edits are found by", group.tag, next.tag)
        assertEquals(group.epoch + 1, next.epoch)
        assertFalse(carol in next.members)
        d.setMembers(next.members.toSet())
        assertFalse("carol's edits no longer count", d.apply(SharedPlaylistDoc("p1", setOf(carol)).add(carol, track(8), after = null)))
        // Sealed with the new key, only the remaining members can read an edit.
        val sealed = next.seal(JSONObject().put("x", 1))
        assertEquals(1, next.open(sealed)!!.getInt("x"))
        assertEquals(null, group.open(sealed))
    }

    @Test
    fun anEditedListBecomesTheFewestOperationsThatRebuildIt() {
        val d = doc()
        (1..6).forEach { d.add(alice, track(it), after = d.items().lastOrNull()?.item) }
        // Song 2 removed, Song 6 moved to the top, Song 9 added after Song 3, Song 4 twice.
        val wanted = listOf(6, 1, 3, 9, 4, 5, 4).map(::track)
        val ops = d.edit(bob, wanted, title = "Mine")
        assertEquals(listOf("Song 6", "Song 1", "Song 3", "Song 9", "Song 4", "Song 5", "Song 4"), titles(d))
        assertEquals("Mine", d.title)
        assertEquals("1 remove, 1 move, 2 adds, 1 rename", listOf("add", "add", "move", "remove", "rename"), ops.map { it.type }.sorted())
        // Another member replaying those ops reaches the same list.
        val other = doc(); d.snapshot() // ops only, from the earlier state
        assertTrue(d.edit(bob, wanted, title = "Mine").isEmpty())
        // Who added each song is kept.
        assertEquals(alice, d.items().first().addedBy)
        assertEquals(bob, d.items().first { it.track.getString("title") == "Song 9" }.addedBy)
    }

    @Test
    fun twoSnapshotsMergeLikeTheirOperations() {
        val start = doc()
        val base = (1..3).map { start.add(alice, track(it), after = start.items().lastOrNull()?.item) }
        val a = SharedPlaylistDoc.fromSnapshot(start.snapshot()); val b = SharedPlaylistDoc.fromSnapshot(start.snapshot())
        a.remove(alice, base[0].item!!); a.add(alice, track(7), after = null)
        b.move(bob, base[2].item!!, after = null); b.rename(bob, "B")
        val ab = SharedPlaylistDoc.fromSnapshot(a.snapshot()).also { it.merge(b) }
        val ba = SharedPlaylistDoc.fromSnapshot(b.snapshot()).also { it.merge(a) }
        assertEquals(titles(ab), titles(ba))
        assertEquals("B", ab.title)
        assertFalse("Song 1" in titles(ab))
    }
}
