package com.teamx.drift.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.teamx.drift.R
import com.teamx.drift.core.LimitVerdict
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityTimeUpBinding
import com.teamx.drift.night.AppGuardAccessibilityService
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages
import java.time.Duration

/**
 * Shown when an app has used up its day.
 *
 * Says how long it got, what is left to borrow, and nothing else. No chart, no streak, no
 * disappointed tone — the app is closed, which is the whole message.
 */
class TimeUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeUpBinding
    private var packageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent?.getStringExtra(EXTRA_PACKAGE)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome()
            },
        )

        binding.done.setOnClickListener { goHome() }
        binding.extend.setOnClickListener { extend() }

        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        packageName = intent.getStringExtra(EXTRA_PACKAGE)
        render()
    }

    private fun render() {
        val target = packageName
        if (target == null) {
            finish()
            return
        }

        val label = DevicePackages.label(this, target)
        binding.headline.text = getString(R.string.time_up_headline, label)

        val settings = DriftSettings.getInstance(this)
        val limit = settings.appLimits.limitFor(target)
        binding.detail.text = if (limit != null) {
            getString(R.string.time_up_detail, label, formatDuration(limit))
        } else {
            getString(R.string.time_up_detail_plain, label)
        }

        val left = NightController.extensionsLeft(this, target)
        val extensionMinutes = settings.extensionMinutes
        binding.extend.isVisible = left > 0
        binding.extend.text = getString(R.string.time_up_extend, extensionMinutes)
        binding.extensionsLeft.text = when (left) {
            0 -> getString(R.string.time_up_no_extensions)
            1 -> getString(R.string.time_up_one_extension)
            else -> getString(R.string.time_up_extensions_left, left)
        }
    }

    private fun extend() {
        val target = packageName ?: return
        if (!NightController.extendLimit(this, target)) {
            Toast.makeText(this, R.string.time_up_no_extensions, Toast.LENGTH_LONG).show()
            render()
            return
        }
        val minutes = DriftSettings.getInstance(this).extensionMinutes
        Toast.makeText(
            this,
            getString(R.string.time_up_extended, minutes),
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun goHome() {
        // The guard can do this cleanly; without it, ask the system for the home screen.
        if (!AppGuardAccessibilityService.sendHome()) {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        return if (hours > 0) {
            getString(R.string.duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.duration_minutes, minutes)
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "com.teamx.drift.extra.TIMED_OUT_PACKAGE"

        fun show(context: Context, packageName: String) {
            val intent = Intent(context.applicationContext, TimeUpActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            runCatching { context.applicationContext.startActivity(intent) }
        }
    }
}
