package com.teamx.drift.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.teamx.drift.R
import com.teamx.drift.core.ChangeDecision
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the app says when a change is refused.
 *
 * It explains rather than scolds: what would have been relaxed, when it can be done
 * freely, and the one way to do it now. The way through is never hidden — it just costs
 * an opening, which is the whole point.
 */
object LockedChangeDialog {

    private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm")

    fun show(
        activity: Activity,
        decision: ChangeDecision.Blocked,
        onDismiss: () -> Unit = {},
    ) {
        val body = buildString {
            append(activity.getString(R.string.locked_body))
            decision.loosenings.take(MAX_REASONS).forEach { reason ->
                append("\n  · ").append(reason)
            }
            val opensAt = decision.opensAt
            if (opensAt != null) {
                append("\n\n").append(
                    activity.getString(R.string.locked_opens_at, formatWhen(opensAt)),
                )
            }
            if (decision.canUseEscapeHatch) {
                append("\n").append(activity.getString(R.string.locked_or_opening))
            } else {
                append("\n").append(activity.getString(R.string.locked_no_openings))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.locked_title)
            .setMessage(body)
            .setPositiveButton(R.string.locked_ok, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    private fun formatWhen(at: LocalDateTime): String = at.format(WHEN_FORMAT)

    private const val MAX_REASONS = 4
}
