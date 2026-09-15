package com.teamx.drift.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teamx.drift.R
import com.teamx.drift.core.AppAccessPolicy
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityAppPickerBinding
import com.teamx.drift.databinding.ItemAppChoiceBinding
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages
import com.teamx.drift.util.InstalledApp

/**
 * Picks the apps for one end of the ramp: the ones to put away when the wind-down
 * starts, or the ones to keep through the quiet stage.
 */
class AppPickerActivity : AppCompatActivity() {

    /** Which end of the ramp this screen is editing. */
    enum class List { PUT_AWAY, KEEP }

    private lateinit var binding: ActivityAppPickerBinding
    private val settings by lazy { DriftSettings.getInstance(this) }

    private lateinit var list: List
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = runCatching { List.valueOf(intent.getStringExtra(EXTRA_LIST).orEmpty()) }
            .getOrDefault(List.PUT_AWAY)

        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.setText(
            if (list == List.PUT_AWAY) R.string.picker_put_away_title else R.string.picker_keep_title,
        )
        binding.subtitle.setText(
            if (list == List.PUT_AWAY) R.string.picker_put_away_subtitle else R.string.picker_keep_subtitle,
        )

        selected += current()

        val apps = DevicePackages.launchable(this)
        binding.suggestButton.isVisible = list == List.PUT_AWAY
        binding.suggestButton.setOnClickListener { suggest(apps) }
        binding.doneButton.setOnClickListener { save() }

        binding.apps.layoutManager = LinearLayoutManager(this)
        binding.apps.adapter = Adapter(apps)
        binding.empty.isVisible = apps.isEmpty()
    }

    private fun current(): Set<String> = when (list) {
        List.PUT_AWAY -> settings.distractingPackages
        List.KEEP -> settings.essentialPackages
    }

    /** Ticks the usual suspects, but only the ones actually on this phone. */
    private fun suggest(apps: kotlin.collections.List<InstalledApp>) {
        val installed = apps.map { it.packageName }.toSet()
        selected += AppAccessPolicy.SUGGESTED_DISTRACTING.intersect(installed)
        binding.apps.adapter?.notifyDataSetChanged()
    }

    private fun save() {
        when (list) {
            List.PUT_AWAY -> settings.distractingPackages = selected.toSet()
            List.KEEP -> settings.essentialPackages = selected.toSet()
        }
        NightController.invalidateAccessPolicy()
        finish()
    }

    private inner class Adapter(
        private val apps: kotlin.collections.List<InstalledApp>,
    ) : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(val binding: ItemAppChoiceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(ItemAppChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = apps.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val app = apps[position]
            holder.binding.label.text = app.label
            holder.binding.packageName.text = app.packageName
            holder.binding.checkbox.setOnCheckedChangeListener(null)
            holder.binding.checkbox.isChecked = app.packageName in selected
            holder.binding.root.setOnClickListener { holder.binding.checkbox.toggle() }
            holder.binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected += app.packageName else selected -= app.packageName
            }
        }
    }

    companion object {
        private const val EXTRA_LIST = "com.teamx.drift.extra.LIST"

        fun open(context: Context, list: List) {
            context.startActivity(
                Intent(context, AppPickerActivity::class.java)
                    .putExtra(EXTRA_LIST, list.name),
            )
        }
    }
}
