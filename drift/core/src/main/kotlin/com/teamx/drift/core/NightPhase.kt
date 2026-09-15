package com.teamx.drift.core

/**
 * How interesting the phone is allowed to be right now.
 *
 * The night is a ramp rather than a switch: apps drop away in stages, so the phone gets
 * quieter as bedtime approaches instead of slamming shut at one moment.
 */
enum class NightPhase {
    /** Daytime. Nothing is held back. */
    OPEN,

    /** Bedtime is coming. The apps you asked to be protected from step aside. */
    WIND_DOWN,

    /** Nearly there. Only the things you called essential are left. */
    QUIET,

    /** Asleep. Calls, alarms and emergencies only. */
    SLEEP,
    ;

    /** True for every phase that closes something. */
    val restricts: Boolean get() = this != OPEN

    /** Ordering is severity: a later phase holds back strictly more than an earlier one. */
    fun isAtLeast(other: NightPhase): Boolean = ordinal >= other.ordinal
}
