package com.teamx.nightlock.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.teamx.nightlock.R
import com.teamx.nightlock.core.EmergencyDecision
import com.teamx.nightlock.core.LockStatus
import com.teamx.nightlock.data.LockSettings
import com.teamx.nightlock.databinding.ActivityLockBinding
import com.teamx.nightlock.databinding.DialogEmergencyUnlockBinding
import com.teamx.nightlock.lock.LockController
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * What the phone shows between 23:00 and 06:00.
 *
 * Two ways out, both deliberate: the Phone button, which is the only app the curfew lets
 * through, and the emergency unlock, which opens everything for a few minutes and writes
 * itself into the log. Back and Home do not close it; the guard brings it straight back.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isPreview = false

    private val ticker = object : Runnable {
        override fun run() {
            render(LockController.refresh(this@LockActivity))
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isPreview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showOverKeyguard()
        hideSystemBars()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back is not an exit during a curfew. In preview it is.
                    if (isPreview) finish()
                }
            },
        )

        binding.previewBadge.isVisible = isPreview
        binding.buttonClosePreview.isVisible = isPreview
        binding.buttonClosePreview.setOnClickListener { finish() }
        binding.buttonPhone.setOnClickListener { openDialer() }
        binding.buttonEmergency.setOnClickListener { showEmergencyDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockController.status.collectLatest { status ->
                    // 06:00, or an emergency unlock, dismisses the lock screen by itself.
                    if (!isPreview && !status.shouldEnforceLockScreen) {
                        finish()
                    } else {
                        render(status)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render(status: LockStatus) {
        binding.clock.text = LocalDateTime.now().format(CLOCK_FORMAT)

        val unlockAt = when (status) {
            is LockStatus.Locked -> status.lockedUntil
            is LockStatus.Bypassed -> status.curfewEndsAt
            is LockStatus.Unlocked -> status.nextLockAt
            LockStatus.Off -> null
        }
        binding.unlockAt.isVisible = unlockAt != null
        binding.countdown.isVisible = unlockAt != null
        if (unlockAt != null) {
            binding.unlockAt.text = getString(R.string.lock_until, unlockAt.format(CLOCK_FORMAT))
            binding.countdown.text = getString(
                R.string.lock_countdown,
                formatRemaining(Duration.between(LocalDateTime.now(), unlockAt)),
            )
        }

        binding.usesLeft.text = when (val left = LockController.emergencyUsesLeft(this)) {
            null -> getString(R.string.lock_uses_unlimited)
            0 -> getString(R.string.lock_uses_left_none)
            1 -> getString(R.string.lock_uses_left_one)
            else -> getString(R.string.lock_uses_left, left)
        }
    }

    /** The one app the curfew lets through. */
    private fun openDialer() {
        val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(packageManager) == null) {
            // Fall back to the tel: form before telling the user there is no dialer.
            val fallback = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fallback.resolveActivity(packageManager) == null) {
                Toast.makeText(this, R.string.lock_no_dialer, Toast.LENGTH_LONG).show()
                return
            }
            startActivity(fallback)
            return
        }
        startActivity(intent)
    }

    private fun showEmergencyDialog() {
        val settings = LockSettings.getInstance(this)
        val policy = settings.emergencyPolicy
        val dialogBinding = DialogEmergencyUnlockBinding.inflate(layoutInflater)

        dialogBinding.body.text =
            getString(R.string.emergency_body, policy.grantDuration.toMinutes())
        dialogBinding.usesLeft.text = when (val left = LockController.emergencyUsesLeft(this)) {
            null -> getString(R.string.lock_uses_unlimited)
            0 -> getString(R.string.lock_uses_left_none)
            1 -> getString(R.string.lock_uses_left_one)
            else -> getString(R.string.lock_uses_left, left)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.emergency_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.emergency_cancel, null)
            .create()

        val holdMillis = policy.holdToConfirm.toMillis()
        dialogBinding.holdProgress.isVisible = holdMillis > 0
        bindHoldToConfirm(dialogBinding, holdMillis) {
            dialog.dismiss()
            grantEmergencyUnlock(dialogBinding.reason.text?.toString().orEmpty())
        }

        dialog.show()
    }

    /**
     * A press and hold rather than a tap: long enough that nobody unlocks the phone out
     * of habit at 01:00, short enough to be nothing in a real emergency.
     */
    private fun bindHoldToConfirm(
        dialogBinding: DialogEmergencyUnlockBinding,
        holdMillis: Long,
        onConfirmed: () -> Unit,
    ) {
        if (holdMillis <= 0L) {
            dialogBinding.holdButton.setOnClickListener { onConfirmed() }
            return
        }

        var startedAt = 0L
        val progress = object : Runnable {
            override fun run() {
                val held = SystemClock.elapsedRealtime() - startedAt
                if (held >= holdMillis) {
                    dialogBinding.holdProgress.progress = PROGRESS_MAX
                    onConfirmed()
                    return
                }
                dialogBinding.holdProgress.progress =
                    ((held.toFloat() / holdMillis) * PROGRESS_MAX).toInt()
                handler.postDelayed(this, 40L)
            }
        }

        dialogBinding.holdButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startedAt = SystemClock.elapsedRealtime()
                    dialogBinding.holdButton.setText(R.string.emergency_holding)
                    handler.post(progress)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(progress)
                    dialogBinding.holdProgress.progress = 0
                    dialogBinding.holdButton.setText(R.string.emergency_hold)
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun grantEmergencyUnlock(reason: String) {
        when (val decision = LockController.requestEmergencyUnlock(this, reason)) {
            is EmergencyDecision.Allowed -> {
                val minutes = LockSettings.getInstance(this).grantMinutes
                Toast.makeText(
                    this,
                    getString(R.string.emergency_granted, minutes),
                    Toast.LENGTH_LONG,
                ).show()
                if (!isPreview) finish()
            }

            is EmergencyDecision.Exhausted -> Toast.makeText(
                this,
                getString(R.string.emergency_exhausted, decision.maxPerNight),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun formatRemaining(duration: Duration): String {
        val total = duration.coerceAtLeast(Duration.ZERO)
        val hours = total.toHours()
        val minutes = total.toMinutes() % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    companion object {
        private const val EXTRA_PREVIEW = "com.teamx.nightlock.extra.PREVIEW"
        private const val PROGRESS_MAX = 1000
        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Brings the lock screen to the front, creating it if it is not already up. */
        fun launch(context: Context) {
            val intent = Intent(context.applicationContext, LockActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            runCatching { context.applicationContext.startActivity(intent) }
        }

        /** Opens the lock screen from the setup screen, closable, without a curfew. */
        fun preview(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_PREVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
