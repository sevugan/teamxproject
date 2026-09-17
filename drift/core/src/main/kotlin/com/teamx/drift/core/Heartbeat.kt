package com.teamx.drift.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** A stretch of time during which Drift was not running at all. */
data class Outage(val from: Instant, val to: Instant) {
    init {
        require(!to.isBefore(from)) { "an outage cannot end before it starts" }
    }

    val duration: Duration get() = Duration.between(from, to)
}

/**
 * Notices that Drift stopped running, by spotting the gap after it starts again.
 *
 * A phone that force-stops an app gives it no chance to say goodbye: the service simply
 * ceases, its alarms are cancelled, and the next thing that happens is the user opening
 * the app and wondering why nothing did anything. The only honest way to detect that is
 * to leave a timestamp behind on every tick and check how stale it is on the way back.
 */
object HeartbeatMonitor {

    /**
     * How long a gap has to be before it counts.
     *
     * Comfortably longer than the service's own tick, so an ordinary late tick, a doze
     * window or a reboot that takes a moment does not get reported as a failure.
     */
    val DEFAULT_TOLERANCE: Duration = Duration.ofMinutes(5)

    fun outageSince(
        lastBeat: Instant?,
        now: Instant,
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): Outage? {
        // Never beaten before: this is a first run, not an outage.
        if (lastBeat == null) return null
        // A clock that moved backwards says nothing useful about whether we were running.
        if (!now.isAfter(lastBeat)) return null
        val gap = Duration.between(lastBeat, now)
        return if (gap > tolerance) Outage(lastBeat, now) else null
    }
}

/**
 * How much an outage actually cost.
 *
 * Being stopped for six hours in the afternoon matters not at all; being stopped for
 * twenty minutes at 23:00 is the whole point of the app failing. This measures the part
 * of a gap that fell inside a stage which would have been holding something back.
 */
object OutageImpact {

    /** Sampling resolution. Long outages are sampled coarsely so the walk stays bounded. */
    private const val MAX_SAMPLES = 1000L

    fun restrictedTimeMissed(
        from: LocalDateTime,
        to: LocalDateTime,
        schedule: NightSchedule,
        step: Duration = Duration.ofMinutes(5),
    ): Duration {
        if (schedule.isEmpty || !to.isAfter(from)) return Duration.ZERO

        val span = Duration.between(from, to)
        // Keep the number of samples bounded however long the phone was off.
        val stride = maxOf(step, span.dividedBy(MAX_SAMPLES))

        var missed = Duration.ZERO
        var cursor = from
        while (cursor < to) {
            val next = minOf(cursor.plus(stride), to)
            if (schedule.phaseAt(cursor).restricts) {
                missed = missed.plus(Duration.between(cursor, next))
            }
            cursor = next
        }
        return missed
    }

    /** Whether any of the outage fell inside a stage that should have been enforcing. */
    fun missedANight(
        from: LocalDateTime,
        to: LocalDateTime,
        schedule: NightSchedule,
    ): Boolean = restrictedTimeMissed(from, to, schedule) > Duration.ZERO
}
