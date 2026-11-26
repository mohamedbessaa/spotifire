package fr.spotifeur.social

import kotlin.math.abs

/**
 * A playing track's position, measured: audio plays at a steady rate, so each reading is that straight line plus
 * reporting noise (on some phones a ±200 ms wobble from one reading to the next). The filter keeps, for the last
 * [window] readings, "position minus rate × time" (constant for perfect readings) and averages them, dropping
 * readings far from the rest. A seek, a new track, a pause or a speed change starts it over.
 */
class PositionFilter(private val window: Int = 12) {
    private val anchors = ArrayDeque<Double>()
    private var rate = 1f
    private var playing = false
    private var track: String? = null
    private var held = 0L

    private var lastRead = 0L
    private var lastPosition = Long.MIN_VALUE

    fun add(readAt: Long, position: Long, rate: Float, playing: Boolean, track: String) {
        // Stuck while playing (audio starting after a seek, a stalled output): the same number again says nothing new.
        val frozen = playing && this.playing && track == this.track && abs(position - lastPosition) <= 50 && readAt - lastRead >= 300
        if (frozen) return
        lastRead = readAt; lastPosition = position
        val jumped = anchors.isNotEmpty() && abs(position - estimateRaw(readAt)) > 1000
        // A real speed change starts over; a listen-together trim (at most 0.1%) moves the line by a few ms only.
        if (track != this.track || playing != this.playing || abs(rate - this.rate) > 0.005f || jumped) anchors.clear()
        this.track = track; this.playing = playing; this.rate = rate
        if (!playing) { held = position; return }
        anchors.addLast(position - rate.toDouble() * readAt)
        while (anchors.size > window) anchors.removeFirst()
    }

    private fun estimateRaw(at: Long) = anchors.average() + rate * at

    /** The filtered position at [at] (same clock as the readings); null before any reading. */
    fun estimate(at: Long): Double? {
        if (!playing) return if (track == null) null else held.toDouble()
        if (anchors.isEmpty()) return null
        val median = anchors.sorted()[anchors.size / 2]
        val kept = anchors.filter { abs(it - median) <= 400 }
        return kept.average() + rate * at
    }

    fun reset() { anchors.clear(); track = null; lastPosition = Long.MIN_VALUE }
}
