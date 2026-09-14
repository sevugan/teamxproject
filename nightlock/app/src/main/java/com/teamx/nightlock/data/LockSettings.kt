package com.teamx.nightlock.data

import android.content.Context
import android.content.SharedPreferences
import com.teamx.nightlock.core.CurfewWindow
import com.teamx.nightlock.core.EmergencyPolicy
import com.teamx.nightlock.core.EmergencyState
import com.teamx.nightlock.core.EmergencyUnlockRecord
import com.teamx.nightlock.core.LockConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the lock remembers, in one place: the curfew, the emergency rules, the
 * state of tonight's allowance and the audit trail of unlocks.
 *
 * Backed by SharedPreferences because every reader (an alarm receiver, the accessibility
 * guard, the foreground service) may be running before any UI exists.
 */
class LockSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0L)

    /** Bumped on every write so observers can recompute without polling. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // ---- curfew ---------------------------------------------------------------

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = write { putBoolean(KEY_ENABLED, value) }

    var startTime: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_START_MINUTES, DEFAULT_START_MINUTES))
        set(value) = write { putInt(KEY_START_MINUTES, value.toMinuteOfDay()) }

    var endTime: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_END_MINUTES, DEFAULT_END_MINUTES))
        set(value) = write { putInt(KEY_END_MINUTES, value.toMinuteOfDay()) }

    val window: CurfewWindow get() = CurfewWindow(startTime, endTime)

    // ---- emergency rules ------------------------------------------------------

    /** Unlocks allowed per night; null means unlimited. */
    var maxUnlocksPerNight: Int?
        get() = prefs.getInt(KEY_MAX_UNLOCKS, DEFAULT_MAX_UNLOCKS).takeIf { it >= 0 }
        set(value) = write { putInt(KEY_MAX_UNLOCKS, value ?: UNLIMITED) }

    var grantMinutes: Int
        get() = prefs.getInt(KEY_GRANT_MINUTES, DEFAULT_GRANT_MINUTES)
        set(value) = write { putInt(KEY_GRANT_MINUTES, value.coerceIn(1, 12 * 60)) }

    var holdSeconds: Int
        get() = prefs.getInt(KEY_HOLD_SECONDS, DEFAULT_HOLD_SECONDS)
        set(value) = write { putInt(KEY_HOLD_SECONDS, value.coerceIn(0, 60)) }

    val emergencyPolicy: EmergencyPolicy
        get() = EmergencyPolicy(
            maxUnlocksPerNight = maxUnlocksPerNight,
            grantDuration = Duration.ofMinutes(grantMinutes.toLong()),
            holdToConfirm = Duration.ofSeconds(holdSeconds.toLong()),
        )

    // ---- what stays reachable while locked ------------------------------------

    var blockSettings: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SETTINGS, false)
        set(value) = write { putBoolean(KEY_BLOCK_SETTINGS, value) }

    var allowedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_ALLOWED_PACKAGES, value) }

    // ---- the composed configuration -------------------------------------------

    val config: LockConfig
        get() = LockConfig(enabled = enabled, window = window, emergency = emergencyPolicy)

    // ---- tonight's emergency state --------------------------------------------

    var emergencyState: EmergencyState
        get() {
            val storedNight = prefs.getLong(KEY_NIGHT_ID, NO_VALUE)
            val storedBypass = prefs.getLong(KEY_BYPASS_UNTIL, NO_VALUE)
            return EmergencyState(
                nightId = storedNight.takeIf { it != NO_VALUE }?.let(LocalDate::ofEpochDay),
                usesThisNight = prefs.getInt(KEY_USES_TONIGHT, 0),
                bypassUntil = storedBypass.takeIf { it != NO_VALUE }?.let(Instant::ofEpochMilli),
            )
        }
        set(value) = write {
            putLong(KEY_NIGHT_ID, value.nightId?.toEpochDay() ?: NO_VALUE)
            putInt(KEY_USES_TONIGHT, value.usesThisNight)
            putLong(KEY_BYPASS_UNTIL, value.bypassUntil?.toEpochMilli() ?: NO_VALUE)
        }

    // ---- audit trail ----------------------------------------------------------

    /** Most recent first, capped so the log cannot grow without bound. */
    fun emergencyHistory(): List<EmergencyUnlockRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                EmergencyUnlockRecord(
                    grantedAt = Instant.ofEpochMilli(item.getLong("grantedAt")),
                    expiresAt = Instant.ofEpochMilli(item.getLong("expiresAt")),
                    reason = item.optString("reason", ""),
                    nightId = LocalDate.ofEpochDay(item.getLong("nightId")),
                    useOfNight = item.getInt("useOfNight"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun recordEmergencyUnlock(record: EmergencyUnlockRecord) {
        val entry = JSONObject().apply {
            put("grantedAt", record.grantedAt.toEpochMilli())
            put("expiresAt", record.expiresAt.toEpochMilli())
            put("reason", record.reason)
            put("nightId", record.nightId.toEpochDay())
            put("useOfNight", record.useOfNight)
        }
        val existing = emergencyHistory()
        val array = JSONArray()
        array.put(entry)
        existing.take(MAX_HISTORY - 1).forEach { older ->
            array.put(
                JSONObject().apply {
                    put("grantedAt", older.grantedAt.toEpochMilli())
                    put("expiresAt", older.expiresAt.toEpochMilli())
                    put("reason", older.reason)
                    put("nightId", older.nightId.toEpochDay())
                    put("useOfNight", older.useOfNight)
                },
            )
        }
        write { putString(KEY_HISTORY, array.toString()) }
    }

    private inline fun write(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
        _revision.value = _revision.value + 1
    }

    private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

    private fun minutesToTime(minutes: Int): LocalTime =
        LocalTime.of((minutes / 60) % 24, minutes % 60)

    companion object {
        private const val PREFS_NAME = "night_lock_settings"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_START_MINUTES = "start_minutes"
        private const val KEY_END_MINUTES = "end_minutes"
        private const val KEY_MAX_UNLOCKS = "max_unlocks_per_night"
        private const val KEY_GRANT_MINUTES = "grant_minutes"
        private const val KEY_HOLD_SECONDS = "hold_seconds"
        private const val KEY_BLOCK_SETTINGS = "block_settings"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_NIGHT_ID = "emergency_night_id"
        private const val KEY_USES_TONIGHT = "emergency_uses"
        private const val KEY_BYPASS_UNTIL = "emergency_bypass_until"
        private const val KEY_HISTORY = "emergency_history"

        private const val NO_VALUE = -1L
        private const val UNLIMITED = -1
        private const val MAX_HISTORY = 50

        /** 23:00 and 06:00, the defaults the app ships with. */
        private const val DEFAULT_START_MINUTES = 23 * 60
        private const val DEFAULT_END_MINUTES = 6 * 60
        private const val DEFAULT_MAX_UNLOCKS = 3
        private const val DEFAULT_GRANT_MINUTES = 15
        private const val DEFAULT_HOLD_SECONDS = 5

        @Volatile
        private var instance: LockSettings? = null

        fun getInstance(context: Context): LockSettings =
            instance ?: synchronized(this) {
                instance ?: LockSettings(context).also { instance = it }
            }
    }
}
