# Battery Debugger

An Android app for diagnosing a phone whose battery percentage can't be trusted — for example one that
shows 40% and then drops to 0% within minutes. It records the battery's behaviour over days and compares
the percentage the phone displays with independent estimates:

- **From voltage** — cell voltage at light load mapped onto a typical lithium-ion curve (approximate, ±10 points).
- **Current-counted** — charge actually drawn since the last full charge, against the battery's design capacity.

It flags sudden drops, shows the history, and exports everything as CSV. On first launch you choose
**Recording Mode** (this is the phone with the problem) or **Investigation Mode** (open an export sent from
another phone; no permissions needed). The estimate is also shown in the
recording notification. Built for a Pixel 9 Pro; the design capacity is set in `domain/Analysis.kt`.
Domain vocabulary is in [CONTEXT.md](CONTEXT.md). UI in English and Hebrew.

## Install

Download the APK from the latest [release](../../releases) and sideload it, then grant the one permission
that needs a computer:

```
adb shell pm grant com.akiva.batterydebugger android.permission.BATTERY_STATS
```

The app's setup checklist walks through the rest.

## Build

```
./gradlew testDebugUnitTest assembleDebug
```

Every push to `master` builds a signed APK and publishes it as a GitHub release.
