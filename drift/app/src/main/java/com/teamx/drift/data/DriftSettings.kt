package com.teamx.drift.data

import android.content.Context
import android.content.SharedPreferences
import com.teamx.drift.core.DriftConfig
import com.teamx.drift.core.EscapeHatchPolicy
import com.teamx.drift.core.EscapeHatchRecord
import com.teamx.drift.core.EscapeHatchState
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightSchedule
import com.teamx.drift.core.UnlockReason
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
 * Everything Drift remembers: tonight's shape, which apps belong to which stage, the
 * state of the escape hatch, and the record of times it was used.
 *
 * SharedPreferences rather than a database, because every reader (an alarm receiver, the
 * accessibility guard, the service) may be running long before any screen exists.
 */
class DriftSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0L)

    /** Bumped on every write so observers can recompute without polling. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // ---- tonight's shape ------------------------------------------------------

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = write { putBoolean(KEY_ENABLED, value) }

    /** The sleep target. Everything else on the ramp is measured back from here. */
    var sleepAt: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_SLEEP_AT, DEFAULT_SLEEP_AT))
        set(value) = write { putInt(KEY_SLEEP_AT, value.toMinuteOfDay()) }

    var wakeAt: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_WAKE_AT, DEFAULT_WAKE_AT))
        set(value) = write { putInt(KEY_WAKE_AT, value.toMinuteOfDay()) }

    var windDownLeadMinutes: Int
        get() = prefs.getInt(KEY_WIND_DOWN_LEAD, DEFAULT_WIND_DOWN_LEAD)
        set(value) = write {
            val lead = value.coerceIn(0, MAX_LEAD_MINUTES)
            putInt(KEY_WIND_DOWN_LEAD, lead)
            // The ramp only makes sense in order; pull quiet in with it if it would overtake.
            if (quietLeadMinutes > lead) putInt(KEY_QUIET_LEAD, lead)
        }

    var quietLeadMinutes: Int
        get() = prefs.getInt(KEY_QUIET_LEAD, DEFAULT_QUIET_LEAD)
        set(value) = write {
            putInt(KEY_QUIET_LEAD, value.coerceIn(0, windDownLeadMinutes))
        }

    val schedule: NightSchedule
        get() = NightSchedule(
            sleepAt = sleepAt,
            wakeAt = wakeAt,
            windDownLead = Duration.ofMinutes(windDownLeadMinutes.toLong()),
            quietLead = Duration.ofMinutes(quietLeadMinutes.toLong()),
        )

    // ---- the escape hatch -----------------------------------------------------

    /** Opens allowed per night; null means unlimited. */
    var maxUsesPerNight: Int?
        get() = prefs.getInt(KEY_MAX_USES, DEFAULT_MAX_USES).takeIf { it >= 0 }
        set(value) = write { putInt(KEY_MAX_USES, value ?: UNLIMITED) }

    var grantMinutes: Int
        get() = prefs.getInt(KEY_GRANT_MINUTES, DEFAULT_GRANT_MINUTES)
        set(value) = write { putInt(KEY_GRANT_MINUTES, value.coerceIn(1, 12 * 60)) }

    var holdSeconds: Int
        get() = prefs.getInt(KEY_HOLD_SECONDS, DEFAULT_HOLD_SECONDS)
        set(value) = write { putInt(KEY_HOLD_SECONDS, value.coerceIn(0, 60)) }

    val escapeHatchPolicy: EscapeHatchPolicy
        get() = EscapeHatchPolicy(
            maxUsesPerNight = maxUsesPerNight,
            grantDuration = Duration.ofMinutes(grantMinutes.toLong()),
            holdToConfirm = Duration.ofSeconds(holdSeconds.toLong()),
        )

    // ---- which apps belong to which stage -------------------------------------

    /** Put away from the wind-down onwards. */
    var distractingPackages: Set<String>
        get() = prefs.getStringSet(KEY_DISTRACTING, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_DISTRACTING, value) }

    /** Kept through the quiet stage. */
    var essentialPackages: Set<String>
        get() = prefs.getStringSet(KEY_ESSENTIAL, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_ESSENTIAL, value) }

    /** Null means Settings is never closed. */
    var blockSettingsFrom: NightPhase?
        get() = prefs.getString(KEY_BLOCK_SETTINGS_FROM, null)
            ?.let { name -> NightPhase.entries.firstOrNull { it.name == name } }
        set(value) = write { putString(KEY_BLOCK_SETTINGS_FROM, value?.name) }

    /** True once the user has been through first-run setup. */
    var hasBeenSetUp: Boolean
        get() = prefs.getBoolean(KEY_SET_UP, false)
        set(value) = write { putBoolean(KEY_SET_UP, value) }

    // ---- the composed configuration -------------------------------------------

    val config: DriftConfig
        get() = DriftConfig(
            enabled = enabled,
            schedule = schedule,
            escapeHatch = escapeHatchPolicy,
        )

    // ---- tonight's escape hatch state -----------------------------------------

    var escapeHatchState: EscapeHatchState
        get() {
            val storedNight = prefs.getLong(KEY_NIGHT_ID, NO_VALUE)
            val storedOpen = prefs.getLong(KEY_OPEN_UNTIL, NO_VALUE)
            return EscapeHatchState(
                nightId = storedNight.takeIf { it != NO_VALUE }?.let(LocalDate::ofEpochDay),
                usesThisNight = prefs.getInt(KEY_USES_TONIGHT, 0),
                openUntil = storedOpen.takeIf { it != NO_VALUE }?.let(Instant::ofEpochMilli),
            )
        }
        set(value) = write {
            putLong(KEY_NIGHT_ID, value.nightId?.toEpochDay() ?: NO_VALUE)
            putInt(KEY_USES_TONIGHT, value.usesThisNight)
            putLong(KEY_OPEN_UNTIL, value.openUntil?.toEpochMilli() ?: NO_VALUE)
        }

    // ---- the record -----------------------------------------------------------

    /** Most recent first, capped so it cannot grow without bound. */
    fun history(): List<EscapeHatchRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                EscapeHatchRecord(
                    openedAt = Instant.ofEpochMilli(item.getLong("openedAt")),
                    closesAt = Instant.ofEpochMilli(item.getLong("closesAt")),
                    reason = item.optString("reason")
                        .let { name -> UnlockReason.entries.firstOrNull { it.name == name } }
                        ?: UnlockReason.JUST_CHECKING,
                    note = item.optString("note", ""),
                    nightId = LocalDate.ofEpochDay(item.getLong("nightId")),
                    useOfNight = item.optInt("useOfNight", 1),
                    phase = item.optString("phase")
                        .let { name -> NightPhase.entries.firstOrNull { it.name == name } }
                        ?: NightPhase.SLEEP,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun record(entry: EscapeHatchRecord) {
        val array = JSONArray()
        (listOf(entry) + history().take(MAX_HISTORY - 1)).forEach { array.put(it.toJson()) }
        write { putString(KEY_HISTORY, array.toString()) }
    }

    /** Opens the user took during one night, newest first. */
    fun historyFor(nightId: LocalDate): List<EscapeHatchRecord> =
        history().filter { it.nightId == nightId }

    private fun EscapeHatchRecord.toJson(): JSONObject = JSONObject().apply {
        put("openedAt", openedAt.toEpochMilli())
        put("closesAt", closesAt.toEpochMilli())
        put("reason", reason.name)
        put("note", note)
        put("nightId", nightId.toEpochDay())
        put("useOfNight", useOfNight)
        put("phase", phase.name)
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
        private const val PREFS_NAME = "drift_settings"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_SLEEP_AT = "sleep_at"
        private const val KEY_WAKE_AT = "wake_at"
        private const val KEY_WIND_DOWN_LEAD = "wind_down_lead"
        private const val KEY_QUIET_LEAD = "quiet_lead"
        private const val KEY_MAX_USES = "max_uses_per_night"
        private const val KEY_GRANT_MINUTES = "grant_minutes"
        private const val KEY_HOLD_SECONDS = "hold_seconds"
        private const val KEY_DISTRACTING = "distracting_packages"
        private const val KEY_ESSENTIAL = "essential_packages"
        private const val KEY_BLOCK_SETTINGS_FROM = "block_settings_from"
        private const val KEY_SET_UP = "has_been_set_up"
        private const val KEY_NIGHT_ID = "hatch_night_id"
        private const val KEY_USES_TONIGHT = "hatch_uses"
        private const val KEY_OPEN_UNTIL = "hatch_open_until"
        private const val KEY_HISTORY = "hatch_history"

        private const val NO_VALUE = -1L
        private const val UNLIMITED = -1
        private const val MAX_HISTORY = 60
        private const val MAX_LEAD_MINUTES = 8 * 60

        private const val DEFAULT_SLEEP_AT = 23 * 60
        private const val DEFAULT_WAKE_AT = 6 * 60
        private const val DEFAULT_WIND_DOWN_LEAD = 60
        private const val DEFAULT_QUIET_LEAD = 30
        private const val DEFAULT_MAX_USES = 3
        private const val DEFAULT_GRANT_MINUTES = 15
        private const val DEFAULT_HOLD_SECONDS = 5

        @Volatile
        private var instance: DriftSettings? = null

        fun getInstance(context: Context): DriftSettings =
            instance ?: synchronized(this) {
                instance ?: DriftSettings(context).also { instance = it }
            }
    }
}
