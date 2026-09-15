package com.teamx.drift.ui

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
import com.teamx.drift.R
import com.teamx.drift.core.EscapeHatchDecision
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightStatus
import com.teamx.drift.core.UnlockReason
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityNightScreenBinding
import com.teamx.drift.databinding.DialogHoldBinding
import com.teamx.drift.databinding.DialogReasonBinding
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * What the phone shows when tonight has closed something.
 *
 * It says the same three things at every stage — where the night is, what it closed, and
 * how to get past it anyway — but it gets quieter and harder to leave as the night goes
 * on. During the wind-down it is a note you can step around. Asleep, it is the screen.
 */
class NightScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNightScreenBinding
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { DriftSettings.getInstance(this) }

    private var isPreview = false
    private var blockedPackage: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            render(NightController.refresh(this@NightScreenActivity))
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)

        binding = ActivityNightScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showOverKeyguard()
        hideSystemBars()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isPreview) {
                        finish()
                        return
                    }
                    // Asleep, back is not an exit. Earlier in the night it means "fine,
                    // I'll put it down" and should land somewhere sensible.
                    if (NightController.enforcedPhase() != NightPhase.SLEEP) goHome()
                }
            },
        )

        binding.previewBadge.isVisible = isPreview
        binding.closePreview.isVisible = isPreview
        binding.closePreview.setOnClickListener { finish() }
        binding.putItDown.setOnClickListener { if (isPreview) finish() else goHome() }
        binding.openPhone.setOnClickListener { openDialer() }
        binding.needIt.setOnClickListener { askWhy() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NightController.status.collectLatest { status ->
                    // Morning, or borrowed time, dismisses this screen by itself.
                    if (!isPreview && !status.restricts) finish() else render(status)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        render(NightController.refresh(this))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun readIntent(intent: Intent?) {
        isPreview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true
        blockedPackage = intent?.getStringExtra(EXTRA_BLOCKED_PACKAGE)
    }

    private fun render(status: NightStatus) {
        val phase = if (isPreview) previewPhase(status) else status.enforced

        binding.clock.text = LocalDateTime.now().format(CLOCK_FORMAT)
        binding.clock.isVisible = phase == NightPhase.SLEEP

        binding.headline.setText(
            when (phase) {
                NightPhase.SLEEP -> R.string.night_headline_sleep
                NightPhase.QUIET -> R.string.night_headline_quiet
                else -> R.string.night_headline_wind_down
            },
        )

        binding.detail.text = blockedAppLine(phase)
        binding.nextStep.text = nextStepLine(status, phase)
        binding.nextStep.isVisible = binding.nextStep.text.isNotBlank()

        // Only the phone is offered once the phone is asleep; earlier there is still a
        // whole phone behind this screen, so pointing at the dialer would be odd.
        binding.openPhone.isVisible = phase.isAtLeast(NightPhase.QUIET)
        binding.putItDown.isVisible = phase != NightPhase.SLEEP || isPreview

        binding.usesLeft.text = when (val left = NightController.usesLeftTonight(this)) {
            null -> getString(R.string.night_uses_unlimited)
            0 -> getString(R.string.night_uses_none)
            1 -> getString(R.string.night_uses_one)
            else -> getString(R.string.night_uses_many, left)
        }
    }

    /** In preview, walk the copy through the stage the schedule is nearest to. */
    private fun previewPhase(status: NightStatus): NightPhase =
        if (status.phase.restricts) status.phase else NightPhase.SLEEP

    private fun blockedAppLine(phase: NightPhase): String {
        val app = blockedPackage?.let { DevicePackages.label(this, it) }
        return when {
            app != null && phase == NightPhase.WIND_DOWN ->
                getString(R.string.night_detail_app_wind_down, app)

            app != null -> getString(R.string.night_detail_app_quiet, app)
            phase == NightPhase.SLEEP -> getString(R.string.night_detail_sleep)
            phase == NightPhase.QUIET -> getString(R.string.night_detail_quiet)
            else -> getString(R.string.night_detail_wind_down)
        }
    }

    private fun nextStepLine(status: NightStatus, phase: NightPhase): String {
        val changesAt = status.changesAt ?: return ""
        val until = formatRemaining(Duration.between(LocalDateTime.now(), changesAt))
        val at = changesAt.format(CLOCK_FORMAT)
        return when (phase) {
            NightPhase.WIND_DOWN -> getString(R.string.night_next_quiet, at, until)
            NightPhase.QUIET -> getString(R.string.night_next_sleep, at, until)
            NightPhase.SLEEP -> getString(R.string.night_next_wake, at, until)
            NightPhase.OPEN -> ""
        }
    }

    /** Step one: name it. Answering at all is most of the point. */
    private fun askWhy() {
        val reasonBinding = DialogReasonBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reason_title)
            .setView(reasonBinding.root)
            .setNegativeButton(R.string.reason_cancel, null)
            .create()

        reasonBinding.reasonWork.setOnClickListener {
            dialog.dismiss()
            proceed(UnlockReason.WORK)
        }
        reasonBinding.reasonImportant.setOnClickListener {
            dialog.dismiss()
            proceed(UnlockReason.IMPORTANT)
        }
        reasonBinding.reasonChecking.setOnClickListener {
            dialog.dismiss()
            proceed(UnlockReason.JUST_CHECKING)
        }

        dialog.show()
    }

    private fun proceed(reason: UnlockReason) {
        if (reason.deservesSecondThought) secondThought(reason) else holdToOpen(reason)
    }

    /**
     * Step two, for the honest answer only: one beat to notice the reflex. Continuing is
     * right there — the app asks, it does not refuse.
     */
    private fun secondThought(reason: UnlockReason) {
        val spent = NightController.usesSpentTonight(this)
        val message = if (spent > 0) {
            resources.getQuantityString(R.plurals.second_thought_with_count, spent, spent)
        } else {
            getString(R.string.second_thought_first)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.second_thought_title)
            .setMessage(message)
            .setPositiveButton(R.string.second_thought_continue) { _, _ -> holdToOpen(reason) }
            .setNegativeButton(R.string.second_thought_put_down) { _, _ ->
                if (!isPreview) goHome()
            }
            .show()
    }

    /** Step three: hold it. Long enough to be a decision, short enough to be nothing. */
    private fun holdToOpen(reason: UnlockReason) {
        val policy = settings.escapeHatchPolicy
        val holdBinding = DialogHoldBinding.inflate(layoutInflater)
        holdBinding.body.text =
            getString(R.string.hold_body, policy.grantDuration.toMinutes())

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.hold_title)
            .setView(holdBinding.root)
            .setNegativeButton(R.string.reason_cancel, null)
            .create()

        val holdMillis = policy.holdToConfirm.toMillis()
        holdBinding.holdProgress.isVisible = holdMillis > 0
        bindHold(holdBinding, holdMillis) {
            dialog.dismiss()
            open(reason, holdBinding.note.text?.toString().orEmpty())
        }

        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.show()
    }

    private fun bindHold(
        holdBinding: DialogHoldBinding,
        holdMillis: Long,
        onConfirmed: () -> Unit,
    ) {
        if (holdMillis <= 0L) {
            holdBinding.holdButton.setOnClickListener { onConfirmed() }
            return
        }

        var startedAt = 0L
        val progress = object : Runnable {
            override fun run() {
                val held = SystemClock.elapsedRealtime() - startedAt
                if (held >= holdMillis) {
                    holdBinding.holdProgress.progress = PROGRESS_MAX
                    onConfirmed()
                    return
                }
                holdBinding.holdProgress.progress =
                    ((held.toFloat() / holdMillis) * PROGRESS_MAX).toInt()
                handler.postDelayed(this, 40L)
            }
        }

        holdBinding.holdButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startedAt = SystemClock.elapsedRealtime()
                    holdBinding.holdButton.setText(R.string.hold_holding)
                    handler.post(progress)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(progress)
                    holdBinding.holdProgress.progress = 0
                    holdBinding.holdButton.setText(R.string.hold_button)
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun open(reason: UnlockReason, note: String) {
        if (isPreview) {
            Toast.makeText(this, R.string.preview_no_unlock, Toast.LENGTH_LONG).show()
            return
        }
        when (val decision = NightController.openEscapeHatch(this, reason, note)) {
            is EscapeHatchDecision.Allowed -> {
                Toast.makeText(
                    this,
                    getString(R.string.hatch_opened, settings.grantMinutes),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }

            is EscapeHatchDecision.Exhausted -> Toast.makeText(
                this,
                getString(R.string.hatch_exhausted, decision.maxPerNight),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** The one app the night always leaves open. */
    private fun openDialer() {
        val candidates = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
        )
        val usable = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (usable == null) {
            Toast.makeText(this, R.string.night_no_dialer, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(usable.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
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
        return if (hours > 0) {
            getString(R.string.duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.duration_minutes, minutes)
        }
    }

    companion object {
        private const val EXTRA_PREVIEW = "com.teamx.drift.extra.PREVIEW"
        private const val EXTRA_BLOCKED_PACKAGE = "com.teamx.drift.extra.BLOCKED_PACKAGE"
        private const val PROGRESS_MAX = 1000
        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Brings the night screen forward, creating it if it is not already up. */
        fun show(context: Context, blockedPackage: String?) {
            val intent = Intent(context.applicationContext, NightScreenActivity::class.java)
                .putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            runCatching { context.applicationContext.startActivity(intent) }
        }

        /** Opens it from Tonight, closable, without waiting for 23:00. */
        fun preview(context: Context) {
            context.startActivity(
                Intent(context, NightScreenActivity::class.java)
                    .putExtra(EXTRA_PREVIEW, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
