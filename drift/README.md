# Drift

**Leave the scroll. Enter the night.**

An Android app that makes the phone gradually less interesting as bedtime comes round,
and hands it back in the morning. Not a screen-time dashboard — a phone that knows when
it is time to stop being interesting.

## The ramp

The night is a ramp, not a switch. With a 23:00 bedtime and the default leads:

```
  22:00          22:30          23:00                      06:00
    |              |              |                          |
    +-- WIND_DOWN -+--- QUIET ----+--------- SLEEP ----------+--> OPEN
```

| Stage | What is left |
|---|---|
| **Wind-down** (60 min before bed) | Everything except the apps you asked to be protected from. The phone is still a phone; the scroll is gone. |
| **Quiet** (30 min before bed) | Your essentials, the home screen, and calling. |
| **Sleep** (bedtime → wake) | Calling, alarms and the clock. The night screen stays up. |

Moving bedtime moves the whole ramp — the two earlier stages are leads measured back from
the sleep target, not separate times to keep in sync.

**Calling is never closed, at any stage.** A phone that cannot call for help at 03:00 is a
worse problem than any amount of scrolling.

## If you really need it

An app that cannot be bypassed gets uninstalled, which protects nobody. So Drift adds
friction instead of refusing:

1. **Name it.** Work / Something important / Just want to check.
2. **One beat**, for the honest answer only — *"You have already opened the phone twice
   tonight."* — with *Continue anyway* right there next to *Put it down*.
3. **Hold to open**, five seconds.

That spends one of the night's openings (three by default) and opens the whole phone for
fifteen minutes, after which the night picks up exactly where it left off. Each opening is
written down, and Tonight shows the count back to you the next time you reach for it.

The app never shames anyone. It makes the intentional choice easier than the impulsive one.

## The screens

**Tonight** is the whole app: where the night is right now, the ramp as one line, which
apps go away and which stay, and the setup checklist. A single line of history — what you
did last night, or how many times you have reached past tonight — and no charts.

**The night screen** is what appears when something is closed. It says the same three
things at every stage — where the night is, what it closed, how to get past it anyway —
but gets quieter and harder to leave as the night goes on. During the wind-down, *Put it
down* takes you home. Asleep, back does nothing and the clock is the biggest thing on the
screen.

## How it works

```
PhaseAlarmReceiver ───┐                     ┌── NightScreenActivity
BootReceiver ─────────┼──► DriftService ────┤
AppGuardAccessibility ┘         │           └── status notification
                                ▼
                         NightController ──► NightEvaluator (core, pure)
                                │
                          DriftSettings (SharedPreferences)
```

- **`core/`** holds every rule with no Android in it: the ramp and its midnight wrap, the
  nightly allowance, the verdict, and which packages survive which stage. It is a
  standalone Gradle build, so `gradle -p core test` runs on any JVM with no Android SDK.
  53 tests cover it.
- **`NightEvaluator`** is the only thing that answers "what is the phone doing right now?",
  so the alarm, the service, the guard and every screen cannot disagree. It reports the
  phase the schedule says *and* the phase actually being enforced, which differ only while
  an opening is running — so Tonight can say "you are inside quiet hours with 7 minutes of
  borrowed time" rather than pretending the night paused.
- **`PhaseScheduler`** keeps exactly one alarm outstanding: the next stage boundary, or the
  end of an opening, whichever comes first.
- **`AppGuardAccessibilityService`** notices which app came to the foreground. Without root
  or a device-owner setup, the accessibility API is the only way to see that. It reads no
  screen content — `canRetrieveWindowContent="false"` — so all it ever learns is a package
  name.
- **`DriftService`** puts the night screen up when a *stage begins*, and re-checks the clock
  every 30 seconds as a safety net for a doze-delayed alarm. It deliberately does not
  re-raise the screen on a plain tick, so a call at 02:00 is never interrupted.

## Building

```bash
./gradlew :app:assembleDebug   # needs an Android SDK (ANDROID_HOME / local.properties)
./gradlew -p core test         # the night rules, no SDK needed
```

JDK 17+ and Android SDK 35. Pushing to this branch also builds a debug APK in CI; the
artifact is on the run page under **Actions**.

## Setting it up on a phone

Install, open Drift, and clear the checklist. Two items are genuinely required:

1. **App guard** (accessibility) — without it nothing is ever put away.
2. **Show over other apps** — lets the night screen come to the front.

The other three (exact alarms, notifications, battery optimisation) affect how punctual
the stages are, not whether they happen.

Then pick your apps. **Tick the usual suspects** fills the put-away list with the obvious
ones that are actually installed on the phone. **Preview tonight** shows the night screen
at any hour without waiting for one.

## What this is not

- **A commitment device, not a security control.** Anyone who can reach Settings can turn
  the accessibility service off, and safe mode disables it outright. *Close Settings during
  quiet hours* raises that cost, and makes switching Drift off harder for you too. If you
  need something that genuinely cannot be removed, you want a device-owner or MDM
  deployment.
- **Android only.** iOS gives no app the ability to close other apps; the equivalent there
  is Screen Time or a supervised-device profile.
- **Not a usage tracker yet.** Per-app time limits, real phone-free totals and the morning
  screen all need `UsageStatsManager`, which is a separate permission and a separate
  subsystem. Nothing here reads how long you spent in any app.
- **Grayscale is not possible** for a normally installed app. Toggling it needs
  `WRITE_SECURE_SETTINGS`, which is only grantable over adb from a computer.
- **`FOREGROUND_SERVICE_SPECIAL_USE` and the accessibility permission both need a written
  justification** to publish on Google Play. Sideloading is unaffected.
