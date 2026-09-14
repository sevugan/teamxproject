# Night Lock

An Android app that locks the phone at **23:00** and hands it back at **06:00**. While
the curfew is running, calling is the only thing available. There is an emergency
unlock for the nights when that is not good enough.

## What it does

| | |
|---|---|
| **23:00** | Whatever is on screen is replaced by the lock screen. |
| **Through the night** | Any app you open is closed again, except the phone. The lock screen shows the time, the countdown to 06:00, a **Phone** button and an **Emergency unlock** button. |
| **06:00** | The lock screen dismisses itself and the phone is normal again. |
| **Emergency** | Hold the emergency button for 5 seconds and the whole phone opens for 15 minutes, then relocks by itself. Three per night by default, logged with a timestamp and an optional reason. |

Emergency *calls* are never gated behind any of this. The dialer stays reachable from
the lock screen even when the night's emergency unlocks are gone, and the system
emergency dialer is reachable from the hardware lock screen as always.

Start, end, unlocks per night, how long an unlock lasts, and whether Settings is blocked
too are all configurable on the setup screen. The defaults are 23:00 to 06:00.

## How it works

```
CurfewAlarmReceiver ──┐                      ┌── LockActivity (the lock screen)
BootReceiver ─────────┼─► LockForegroundService ─┤
AppGuardAccessibility ┘         │             └── status notification
                                ▼
                          LockController ──► LockEvaluator (core, pure)
                                │
                          LockSettings (SharedPreferences)
```

- **`core/`** holds every rule with no Android in it: the curfew window and its
  midnight wrap, the nightly emergency allowance, the lock verdict, and which packages
  stay reachable. It is a standalone Gradle build, so `gradle -p core test` runs on any
  JVM without an Android SDK. Every branch above is covered by its tests.
- **`LockEvaluator`** is the only thing that answers "is the phone locked right now?",
  so the alarm, the service, the guard and both screens can never disagree.
- **`CurfewScheduler`** keeps exactly one alarm outstanding: 23:00, 06:00, or the
  expiry of an emergency grant, whichever comes first. Each firing schedules the next.
- **`AppGuardAccessibilityService`** is what notices that you just opened something.
  Without root or a device-owner setup, the accessibility API is the only way to see
  which app came to the foreground. It reads no screen content —
  `canRetrieveWindowContent="false"` — so all it ever learns is a package name.
- **`LockForegroundService`** raises the lock screen when the curfew *begins*, and
  re-checks the clock every 30 seconds as a safety net for an alarm that doze delayed.
  It deliberately does not re-raise the lock screen on a plain tick, so a call at 02:00
  is never interrupted by it.

## Building

```bash
./gradlew :app:assembleDebug      # needs an Android SDK (ANDROID_HOME / local.properties)
./gradlew -p core test            # the curfew and emergency rules, no SDK needed
```

Requires JDK 17+ and Android SDK 35. Open the `nightlock/` folder in Android Studio and
it will configure itself.

## Setting it up on a phone

Install, open the app, and clear the checklist on the setup screen:

1. **App guard** (accessibility) — required. Without it nothing gets blocked.
2. **Show over other apps** — required. Lets the lock screen come to the front at 23:00.
3. **Exact alarms** — keeps the lock landing on the minute.
4. **Notifications** — the countdown, and the button that ends an emergency unlock early.
5. **Ignore battery optimisation** — stops the system delaying the 23:00 alarm.

**Preview lock screen** on the setup screen shows exactly what 23:00 looks like, at any
hour, without waiting for one.

## What this is not

- **It is a commitment device, not a security control.** Anyone who can reach Settings
  can turn the accessibility service off, and booting into safe mode disables it
  outright. Turning on *Also block Settings while locked* raises the cost of that, and
  makes switching the app off harder for you too. If you need something that genuinely
  cannot be removed, you want a device-owner or MDM deployment, not this.
- **Android only.** iOS gives no app the ability to close other apps; the equivalent
  there is Screen Time or a supervised-device profile, set up by the phone's owner.
- **`FOREGROUND_SERVICE_SPECIAL_USE` and the accessibility permission both need a
  written justification** if you publish this on Google Play. Sideloading is unaffected.
