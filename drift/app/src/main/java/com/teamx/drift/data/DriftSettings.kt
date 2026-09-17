package com.teamx.drift.data

import android.content.Context
import android.content.SharedPreferences
import com.teamx.drift.core.AppLimits
import com.teamx.drift.core.Commitments
import com.teamx.drift.core.DriftConfig
import com.teamx.drift.core.EditWindow
import com.teamx.drift.core.EssentialRole
import com.teamx.drift.core.EscapeHatchPolicy
import com.teamx.drift.core.LimitExtensions
import com.teamx.drift.core.LimitPolicy
import com.teamx.drift.core.EscapeHatchRecord
import com.teamx.drift.core.EscapeHatchState
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightSchedule
import com.teamx.drift.core.Outage
import com.teamx.drift.core.UnlockReason
import java.time.DayOfWeek
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

    /**
     * The roles kept reachable all night: the essentials kit.
     *
     * Defaults to the super saver set, so a quiet phone is usable out of the box rather
     * than only after somebody hand-picks a list.
     */
    var essentialRoles: Set<EssentialRole>
        get() = prefs.getStringSet(KEY_ESSENTIAL_ROLES, null)
            ?.mapNotNull { name -> EssentialRole.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: EssentialRole.SUPER_SAVER
        set(value) = write { putStringSet(KEY_ESSENTIAL_ROLES, value.map { it.name }.toSet()) }

    /** Null means Settings is never closed. */
    var blockSettingsFrom: NightPhase?
        get() = prefs.getString(KEY_BLOCK_SETTINGS_FROM, null)
            ?.let { name -> NightPhase.entries.firstOrNull { it.name == name } }
        set(value) = write { putString(KEY_BLOCK_SETTINGS_FROM, value?.name) }

    /** True once the user has been through first-run setup. */
    var hasBeenSetUp: Boolean
        get() = prefs.getBoolean(KEY_SET_UP, false)
        set(value) = write { putBoolean(KEY_SET_UP, value) }

    // ---- daily app limits ----------------------------------------------------

    var appLimits: AppLimits
        get() = AppLimits(readMinutes(KEY_APP_LIMITS))
        set(value) = write { putString(KEY_APP_LIMITS, writeMinutes(value.perApp)) }

    var maxExtensionsPerDay: Int
        get() = prefs.getInt(KEY_MAX_EXTENSIONS, DEFAULT_MAX_EXTENSIONS)
        set(value) = write { putInt(KEY_MAX_EXTENSIONS, value.coerceIn(0, 10)) }

    var extensionMinutes: Int
        get() = prefs.getInt(KEY_EXTENSION_MINUTES, DEFAULT_EXTENSION_MINUTES)
        set(value) = write { putInt(KEY_EXTENSION_MINUTES, value.coerceIn(1, 60)) }

    val limitPolicy: LimitPolicy
        get() = LimitPolicy(
            maxExtensionsPerDay = maxExtensionsPerDay,
            extensionLength = Duration.ofMinutes(extensionMinutes.toLong()),
        )

    /** Minutes borrowed against today's limits. Resets with the usage day. */
    var limitExtensions: LimitExtensions
        get() {
            val day = prefs.getLong(KEY_EXT_DAY, NO_VALUE)
            return LimitExtensions(
                dayId = day.takeIf { it != NO_VALUE }?.let(LocalDate::ofEpochDay),
                timesUsed = readCounts(KEY_EXT_TIMES),
                extraTime = readMinutes(KEY_EXT_EXTRA),
            )
        }
        set(value) = write {
            putLong(KEY_EXT_DAY, value.dayId?.toEpochDay() ?: NO_VALUE)
            putString(KEY_EXT_TIMES, writeCounts(value.timesUsed))
            putString(KEY_EXT_EXTRA, writeMinutes(value.extraTime))
        }

    // ---- when the rules may be relaxed ----------------------------------------

    var editWindowEnabled: Boolean
        get() = prefs.getBoolean(KEY_WINDOW_ENABLED, false)
        set(value) = write { putBoolean(KEY_WINDOW_ENABLED, value) }

    var editWindowFrom: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_WINDOW_FROM, DEFAULT_WINDOW_FROM))
        set(value) = write { putInt(KEY_WINDOW_FROM, value.toMinuteOfDay()) }

    var editWindowTo: LocalTime
        get() = minutesToTime(prefs.getInt(KEY_WINDOW_TO, DEFAULT_WINDOW_TO))
        set(value) = write { putInt(KEY_WINDOW_TO, value.toMinuteOfDay()) }

    var editWindowDays: Set<DayOfWeek>
        get() = prefs.getStringSet(KEY_WINDOW_DAYS, null)
            ?.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: EditWindow.WEEKDAYS
        set(value) = write { putStringSet(KEY_WINDOW_DAYS, value.map { it.name }.toSet()) }

    val editWindow: EditWindow
        get() = EditWindow(
            from = editWindowFrom,
            to = editWindowTo,
            days = editWindowDays,
            enabled = editWindowEnabled,
        )

    // ---- the promise, as one comparable value ---------------------------------

    /**
     * Everything the user has committed to. Read before a change and compared with what
     * the change would produce, so [com.teamx.drift.core.ChangeGuard] can judge the whole
     * edit rather than the screen it came from.
     */
    var commitments: Commitments
        get() = Commitments(
            enabled = enabled,
            schedule = schedule,
            limits = appLimits,
            limitPolicy = limitPolicy,
            distracting = distractingPackages,
            essential = essentialPackages,
            essentialRoles = essentialRoles,
            escapeHatch = escapeHatchPolicy,
            blockSettingsFrom = blockSettingsFrom,
        )
        set(value) {
            enabled = value.enabled
            sleepAt = value.schedule.sleepAt
            wakeAt = value.schedule.wakeAt
            windDownLeadMinutes = value.schedule.windDownLead.toMinutes().toInt()
            quietLeadMinutes = value.schedule.quietLead.toMinutes().toInt()
            appLimits = value.limits
            maxExtensionsPerDay = value.limitPolicy.maxExtensionsPerDay
            extensionMinutes = value.limitPolicy.extensionLength.toMinutes().toInt()
            distractingPackages = value.distracting
            essentialPackages = value.essential
            essentialRoles = value.essentialRoles
            maxUsesPerNight = value.escapeHatch.maxUsesPerNight
            grantMinutes = value.escapeHatch.grantDuration.toMinutes().toInt()
            holdSeconds = value.escapeHatch.holdToConfirm.seconds.toInt()
            blockSettingsFrom = value.blockSettingsFrom
        }

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

    // ---- proof of life --------------------------------------------------------

    /**
     * When the service last ticked.
     *
     * Written quietly: it changes every half minute and nothing observes it reactively,
     * so bumping the revision would throw away the cached app allowlist each time.
     */
    var lastHeartbeat: Instant?
        get() = prefs.getLong(KEY_HEARTBEAT, NO_VALUE).takeIf { it != NO_VALUE }
            ?.let(Instant::ofEpochMilli)
        set(value) = writeQuietly {
            putLong(KEY_HEARTBEAT, value?.toEpochMilli() ?: NO_VALUE)
        }

    /** The last stretch during which Drift was not running, if one has been noticed. */
    var lastOutage: Outage?
        get() {
            val from = prefs.getLong(KEY_OUTAGE_FROM, NO_VALUE)
            val to = prefs.getLong(KEY_OUTAGE_TO, NO_VALUE)
            if (from == NO_VALUE || to == NO_VALUE || to < from) return null
            return Outage(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
        }
        set(value) = writeQuietly {
            putLong(KEY_OUTAGE_FROM, value?.from?.toEpochMilli() ?: NO_VALUE)
            putLong(KEY_OUTAGE_TO, value?.to?.toEpochMilli() ?: NO_VALUE)
        }

    /** True once the user has seen and dismissed the outage above. */
    var lastOutageSeen: Boolean
        get() = prefs.getBoolean(KEY_OUTAGE_SEEN, true)
        set(value) = writeQuietly { putBoolean(KEY_OUTAGE_SEEN, value) }

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

    private fun readMinutes(key: String): Map<String, Duration> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { Duration.ofMinutes(json.getLong(it)) }
    }.getOrDefault(emptyMap())

    private fun writeMinutes(values: Map<String, Duration>): String {
        val json = JSONObject()
        values.forEach { (key, duration) -> json.put(key, duration.toMinutes()) }
        return json.toString()
    }

    private fun readCounts(key: String): Map<String, Int> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { json.getInt(it) }
    }.getOrDefault(emptyMap())

    private fun writeCounts(values: Map<String, Int>): String {
        val json = JSONObject()
        values.forEach { (key, count) -> json.put(key, count) }
        return json.toString()
    }

    /** A write nothing observes: does not bump the revision or invalidate caches. */
    private inline fun writeQuietly(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
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
        private const val KEY_ESSENTIAL_ROLES = "essential_roles"
        private const val KEY_BLOCK_SETTINGS_FROM = "block_settings_from"
        private const val KEY_SET_UP = "has_been_set_up"
        private const val KEY_NIGHT_ID = "hatch_night_id"
        private const val KEY_USES_TONIGHT = "hatch_uses"
        private const val KEY_OPEN_UNTIL = "hatch_open_until"
        private const val KEY_HISTORY = "hatch_history"
        private const val KEY_HEARTBEAT = "last_heartbeat"
        private const val KEY_OUTAGE_FROM = "outage_from"
        private const val KEY_OUTAGE_TO = "outage_to"
        private const val KEY_OUTAGE_SEEN = "outage_seen"
        private const val KEY_APP_LIMITS = "app_limits"
        private const val KEY_MAX_EXTENSIONS = "max_extensions"
        private const val KEY_EXTENSION_MINUTES = "extension_minutes"
        private const val KEY_EXT_DAY = "ext_day"
        private const val KEY_EXT_TIMES = "ext_times"
        private const val KEY_EXT_EXTRA = "ext_extra"
        private const val KEY_WINDOW_ENABLED = "window_enabled"
        private const val KEY_WINDOW_FROM = "window_from"
        private const val KEY_WINDOW_TO = "window_to"
        private const val KEY_WINDOW_DAYS = "window_days"

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
        private const val DEFAULT_MAX_EXTENSIONS = 2
        private const val DEFAULT_EXTENSION_MINUTES = 5
        private const val DEFAULT_WINDOW_FROM = 9 * 60
        private const val DEFAULT_WINDOW_TO = 18 * 60

        @Volatile
        private var instance: DriftSettings? = null

        fun getInstance(context: Context): DriftSettings =
            instance ?: synchronized(this) {
                instance ?: DriftSettings(context).also { instance = it }
            }
    }
}
