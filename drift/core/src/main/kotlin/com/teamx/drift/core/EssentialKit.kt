package com.teamx.drift.core

/**
 * The jobs a phone still has to do when it has stopped being entertaining.
 *
 * Modelled on what ultra power saving modes leave running — phone, messages, contacts,
 * clock, calculator — because that set is a good answer to "what is genuinely needed at
 * 02:00" that somebody else has already thought hard about.
 *
 * These are roles rather than apps. Which package fills each one is resolved on the
 * device, so this stays right whichever dialer or clock somebody actually uses.
 */
enum class EssentialRole {
    PHONE,
    MESSAGES,
    CONTACTS,
    CLOCK,
    CALCULATOR,
    CAMERA,
    EMAIL,
    MAPS,
    CALENDAR,
    MUSIC,
    FILES,
    ;

    companion object {
        /**
         * The default kit: what a super power saving mode typically keeps.
         *
         * Camera, maps and email are deliberately left out — each is defensible at 02:00
         * and each is also a doorway back into the phone, so they are opt-in rather than
         * on by default.
         */
        val SUPER_SAVER: Set<EssentialRole> = setOf(PHONE, MESSAGES, CONTACTS, CLOCK, CALCULATOR)

        /** Everything on offer, for the picker. */
        val ALL: Set<EssentialRole> = entries.toSet()
    }
}
