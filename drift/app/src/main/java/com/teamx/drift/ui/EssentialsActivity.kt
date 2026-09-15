package com.teamx.drift.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teamx.drift.R
import com.teamx.drift.core.ChangeDecision
import com.teamx.drift.core.EssentialRole
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityEssentialsBinding
import com.teamx.drift.databinding.ItemEssentialRoleBinding
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages

/**
 * The essentials kit: what the phone keeps once it has stopped being entertaining.
 *
 * Shown as jobs rather than apps — "Messages", "Clock" — with whatever fills each one on
 * this device named underneath, so it is obvious what the choice actually buys.
 */
class EssentialsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEssentialsBinding
    private val settings by lazy { DriftSettings.getInstance(this) }

    private val roles = EssentialRole.entries.toList()
    private val selected = linkedSetOf<EssentialRole>()

    /** Resolved once: each lookup asks the package manager several questions. */
    private val filledBy = mutableMapOf<EssentialRole, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEssentialsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selected += settings.essentialRoles
        roles.forEach { role -> filledBy[role] = DevicePackages.labelForRole(this, role) }

        binding.apps.layoutManager = LinearLayoutManager(this)
        binding.apps.adapter = Adapter()
        binding.resetButton.setOnClickListener { resetToSuperSaver() }
        binding.doneButton.setOnClickListener { save() }
    }

    private fun resetToSuperSaver() {
        selected.clear()
        selected += EssentialRole.SUPER_SAVER
        binding.apps.adapter?.notifyDataSetChanged()
    }

    private fun save() {
        val decision = NightController.proposeChange(this) { commitments ->
            commitments.copy(essentialRoles = selected.toSet())
        }
        when (decision) {
            is ChangeDecision.Allowed -> finish()
            // Keeping more open at night relaxes the night, so it waits like any other
            // loosening. Taking something out of the kit always goes through.
            is ChangeDecision.Blocked -> LockedChangeDialog.show(this, decision)
        }
    }

    private fun labelFor(role: EssentialRole): Int = when (role) {
        EssentialRole.PHONE -> R.string.role_phone
        EssentialRole.MESSAGES -> R.string.role_messages
        EssentialRole.CONTACTS -> R.string.role_contacts
        EssentialRole.CLOCK -> R.string.role_clock
        EssentialRole.CALCULATOR -> R.string.role_calculator
        EssentialRole.CAMERA -> R.string.role_camera
        EssentialRole.EMAIL -> R.string.role_email
        EssentialRole.MAPS -> R.string.role_maps
        EssentialRole.CALENDAR -> R.string.role_calendar
        EssentialRole.MUSIC -> R.string.role_music
        EssentialRole.FILES -> R.string.role_files
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Row>() {

        inner class Row(val binding: ItemEssentialRoleBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(ItemEssentialRoleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = roles.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val role = roles[position]
            val filled = filledBy[role]

            holder.binding.label.setText(labelFor(role))
            holder.binding.filledBy.text = filled
                ?: getString(R.string.role_nothing_installed)

            // Calling is not a choice. It stays whatever else is ticked.
            val isPhone = role == EssentialRole.PHONE
            holder.binding.checkbox.setOnCheckedChangeListener(null)
            holder.binding.checkbox.isChecked = isPhone || role in selected
            holder.binding.checkbox.isEnabled = !isPhone && filled != null
            holder.binding.root.isEnabled = !isPhone && filled != null
            holder.binding.root.setOnClickListener {
                if (!isPhone && filled != null) holder.binding.checkbox.toggle()
            }
            holder.binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected += role else selected -= role
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, EssentialsActivity::class.java))
        }
    }
}
