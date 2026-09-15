package com.teamx.drift.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teamx.drift.R
import com.teamx.drift.core.ChangeDecision
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityAppLimitsBinding
import com.teamx.drift.databinding.ItemAppLimitBinding
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages
import com.teamx.drift.util.InstalledApp
import java.time.Duration

/**
 * Sets how long each app gets per day.
 *
 * Tightening a limit goes through at any hour. Raising or removing one is a change to a
 * promise, so it goes through [NightController.proposeChange] like everything else.
 */
class AppLimitsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppLimitsBinding
    private val settings by lazy { DriftSettings.getInstance(this) }
    private var apps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppLimitsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apps = DevicePackages.launchable(this)
        binding.apps.layoutManager = LinearLayoutManager(this)
        binding.apps.adapter = Adapter()
        binding.empty.isVisible = apps.isEmpty()
        binding.doneButton.setOnClickListener { finish() }

        renderWindowNote()
    }

    private fun renderWindowNote() {
        val window = settings.editWindow
        binding.windowNote.isVisible = window.enabled && !window.isUnusable
        binding.windowNote.text = getString(
            R.string.limits_window_note,
            window.from.toString(),
            window.to.toString(),
        )
    }

    private fun pickLimit(app: InstalledApp) {
        val current = settings.appLimits.limitFor(app.packageName)
        val labels = CHOICES.map { choice ->
            when {
                choice == null -> getString(R.string.limits_none)
                choice.toHours() > 0 -> getString(
                    R.string.duration_hours_minutes,
                    choice.toHours(),
                    choice.toMinutes() % 60,
                )

                else -> getString(R.string.duration_minutes, choice.toMinutes())
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setSingleChoiceItems(labels, CHOICES.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                apply(app, CHOICES[which])
            }
            .setNegativeButton(R.string.reason_cancel, null)
            .show()
    }

    private fun apply(app: InstalledApp, limit: Duration?) {
        val decision = NightController.proposeChange(this) { commitments ->
            val limits = if (limit == null) {
                commitments.limits.without(app.packageName)
            } else {
                commitments.limits.with(app.packageName, limit)
            }
            commitments.copy(limits = limits)
        }

        when (decision) {
            is ChangeDecision.Allowed -> binding.apps.adapter?.notifyDataSetChanged()
            is ChangeDecision.Blocked -> LockedChangeDialog.show(this, decision) {
                binding.apps.adapter?.notifyDataSetChanged()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(val binding: ItemAppLimitBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(ItemAppLimitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = apps.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val app = apps[position]
            val limit = settings.appLimits.limitFor(app.packageName)

            holder.binding.label.text = app.label
            holder.binding.limit.text = when {
                limit == null -> getString(R.string.limits_none)
                limit.toHours() > 0 -> getString(
                    R.string.duration_hours_minutes,
                    limit.toHours(),
                    limit.toMinutes() % 60,
                )

                else -> getString(R.string.duration_minutes, limit.toMinutes())
            }

            holder.binding.used.isVisible = limit != null
            if (limit != null) {
                val used = NightController.usedToday(this@AppLimitsActivity, app.packageName)
                holder.binding.used.text = getString(
                    R.string.limits_used_today,
                    used.toMinutes(),
                    limit.toMinutes(),
                )
            }

            holder.binding.root.setOnClickListener { pickLimit(app) }
        }
    }

    companion object {
        /** null is "no limit", and is deliberately first so it is easy to find. */
        private val CHOICES: List<Duration?> = listOf(
            null,
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            Duration.ofMinutes(15),
            Duration.ofMinutes(20),
            Duration.ofMinutes(30),
            Duration.ofMinutes(45),
            Duration.ofHours(1),
            Duration.ofMinutes(90),
            Duration.ofHours(2),
        )

        fun open(context: Context) {
            context.startActivity(Intent(context, AppLimitsActivity::class.java))
        }
    }
}
