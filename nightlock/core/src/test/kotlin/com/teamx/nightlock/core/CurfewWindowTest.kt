package com.teamx.nightlock.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurfewWindowTest {

    private val night = CurfewWindow.DEFAULT // 23:00 -> 06:00

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    @Test
    fun `default window is 2300 to 0600 and wraps midnight`() {
        assertEquals(LocalTime.of(23, 0), night.start)
        assertEquals(LocalTime.of(6, 0), night.end)
        assertTrue(night.wrapsMidnight)
        assertFalse(night.isEmpty)
    }

    @Test
    fun `locked from the first second of 2300 until the first second of 0600`() {
        assertFalse(night.contains(LocalTime.of(22, 59, 59)))
        assertTrue(night.contains(LocalTime.of(23, 0, 0)))
        assertTrue(night.contains(LocalTime.of(23, 30)))
        assertTrue(night.contains(LocalTime.MIDNIGHT))
        assertTrue(night.contains(LocalTime.of(3, 0)))
        assertTrue(night.contains(LocalTime.of(5, 59, 59)))
        assertFalse(night.contains(LocalTime.of(6, 0, 0)))
        assertFalse(night.contains(LocalTime.of(12, 0)))
    }

    @Test
    fun `next transition is the unlock time while locked`() {
        assertEquals(at(5, 6), night.nextTransition(at(4, 23, 30)))
        assertEquals(at(4, 6), night.nextTransition(at(4, 2, 15)))
    }

    @Test
    fun `next transition is the lock time while unlocked`() {
        assertEquals(at(4, 23), night.nextTransition(at(4, 6)))
        assertEquals(at(4, 23), night.nextTransition(at(4, 20)))
    }

    @Test
    fun `a boundary moment schedules the following boundary, never itself`() {
        // At exactly 23:00 the phone locks, so the next thing to wake for is 06:00.
        assertEquals(at(5, 6), night.nextTransition(at(4, 23, 0)))
        // At exactly 06:00 it is free, so the next thing to wake for is 23:00.
        assertEquals(at(4, 23), night.nextTransition(at(4, 6, 0)))
    }

    @Test
    fun `a night keeps one identity across midnight`() {
        val before = night.nightId(at(4, 23, 30))
        val after = night.nightId(at(5, 2, 0))
        assertEquals(LocalDate.of(2026, 3, 4), before)
        assertEquals(before, after)
    }

    @Test
    fun `the evening after an unlock belongs to the next night`() {
        assertEquals(LocalDate.of(2026, 3, 5), night.nightId(at(5, 6, 0)))
        assertEquals(LocalDate.of(2026, 3, 5), night.nightId(at(5, 22, 0)))
    }

    @Test
    fun `a same day window does not wrap`() {
        val daytime = CurfewWindow(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertFalse(daytime.wrapsMidnight)
        assertTrue(daytime.contains(LocalTime.of(12, 0)))
        assertFalse(daytime.contains(LocalTime.of(8, 0)))
        assertFalse(daytime.contains(LocalTime.of(23, 0)))
        assertEquals(at(4, 17), daytime.nextTransition(at(4, 12)))
        assertEquals(LocalDate.of(2026, 3, 4), daytime.nightId(at(4, 2)))
    }

    @Test
    fun `an empty window never locks`() {
        val empty = CurfewWindow(LocalTime.of(23, 0), LocalTime.of(23, 0))
        assertTrue(empty.isEmpty)
        assertFalse(empty.contains(LocalTime.of(23, 0)))
        assertFalse(empty.contains(LocalTime.of(3, 0)))
    }
}
