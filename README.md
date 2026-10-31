# Polar Paper

Native Android app for a **Polar H10**: scrolling ECG paper, live HRV, and local
session recordings with consolidated reports. **Wellness/research only, not a
diagnostic device.** A chest strap does not replace a clinical multi-lead ECG.

## Install and use

1. Download `polar-paper.apk` from [Releases](https://github.com/mayberryjp/polar_ekg/releases).
   Android **8.0+** is required. Allow installation from your browser/file manager.
   Optional: check the release's SHA256SUMS and signing-certificate.txt.
2. Wear an H10 with wet strap electrodes. Enable Bluetooth. Close other sensor
   apps; enable the H10's two-Bluetooth-connections setting in Polar Flow if needed.
3. Grant Nearby devices permissions (Location permission/services on Android 11
   and earlier). Notification permission is recommended for background control.
4. Tap **Find H10**, then your sensor, or enter its printed hexadecimal device ID.
5. Wait for live ECG and contact; tap **Start session**. **Finish & save report**
   opens the consolidated report. A foreground notification keeps recording with
   the screen off and offers **Stop & save**.
6. Saved sessions are under **Sessions**. Export a ZIP to a chosen folder or share
   it. ZIP includes `report.txt`, `metrics.json`, `ecg.csv`, `rr.csv`, and `hr.csv`.
   Export files are not encrypted. Share only with trusted recipients.

Data stays in the app's private local SQLite database. No accounts, analytics,
cloud sync, or network permission. Android backup is disabled. Delete individual
sessions in their reports; uninstalling removes all local sessions. Exports saved
outside the app must be deleted separately. Export important sessions before
uninstalling. Keep the signing key to install future updates without data loss.

## Metrics and paper

* Instant HR comes from the H10, not a home-grown ECG peak detector.
* Live cards show a **trailing 60-second** window. Reports/session-so-far use
  **the entire recorded session**. Min/max/mean HR use contact-valid received
  readings (mean is not time-weighted).
* **HRV = RMSSD**: square root of mean squared successive RR differences.
* **SDNN** (the requested standard-deviation metric): sample standard deviation
  of accepted RR intervals, denominator `n - 1`.
* **pNN50**: percentage of adjacent accepted pairs with absolute difference
  **strictly greater than 50 ms**, denominator the available pair count.
* RR intervals use the SDK's `rrsMs`. Accept 250-2000 ms with skin contact. HR
  accepts 1-240 bpm with contact. Rejects and gaps >5 seconds break adjacency.
  Missing RR also breaks adjacency. No ectopic detection or interpolation:
  these are bounds-screened RR estimates, not clinically validated NN metrics.
* Insufficient data shows `--`, not zero. Short-term HRV is best compared over at
  least five minutes at rest, with similar posture/breathing. Motion/arrhythmia
  can make these estimates unsuitable.
* H10 ECG normally streams at **130 Hz**, voltage in **microvolts**. The app queries
  supported settings, uses actual sample timestamps, and does not connect gaps
  larger than 30 ms on paper. A schematic grid uses 4 dp/mm, **25 mm/s and
  10 mm/mV**, plus a 1 mV calibration pulse. This is not a physical screen-size
  calibration. The visible time span depends on screen width.
* Raw ECG CSV retains SDK nanoseconds since **2000-01-01**, plus time relative to
  the first saved ECG sample. RR times are reconstructed from packet arrival;
  they are not ECG-synchronized R-peak locations.

Loss of connection or >10 seconds without ECG/HR ends the session as interrupted.
No silent auto-reconnect or joining data across separate captures. On process
termination, committed samples remain and the next app launch marks the session
interrupted. OS force-stop/power loss may lose the last in-flight transaction.
Normal finish waits for writes before reporting. Storage failures are surfaced.

## Build

Use Android Studio, **JDK 21**, Android SDK platform **35** and build-tools **35.0.0**.
The checked-in Gradle 8.11.1 wrapper checks its distribution SHA-256.

Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest # running emulator/device required
```

Linux/macOS: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Local release builds are
unsigned unless the `SIGNING_*` environment variables below are configured.

## CI and signed release delivery

[GitHub Actions](https://github.com/mayberryjp/polar_ekg/actions) runs on main,
pull requests, version tags, and manual dispatch:

* Unit tests, Android lint, debug APK artifact, and test reports.
* Android 35 emulator tests: launch UI, SQLite round-trip, metrics consistency,
  ZIP/JSON/CSV shape, interrupted-session recovery, and deletion.
* Trusted pushes/manual runs build a **signed release APK**, verify its signature,
  and upload `polar-paper-signed` including a checksum/certificate.
* A `v*` tag publishes those files to GitHub Releases **only after both jobs pass**.
  Installation is sideloading; this does not publish to Google Play or remotely
  install on a phone. Fork PRs never receive signing secrets.

Configure repository Actions secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of the release JKS |
| `ANDROID_KEYSTORE_PASSWORD` | Store password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Key password |

Never commit a key/password. Keep an offline encrypted backup; losing the key
prevents updating the installed app. The workflow fails explicitly without
signing secrets, rather than passing off an unsigned release as installable.
Local signing env: `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Version code is the workflow run
number; release version name is the tag without `v`.

To release from Windows:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

## Verification limits

Automated tests do not simulate a real Polar H10. Before relying on a recording,
check a physical H10 on your phone: permissions denied/granted, sensor discovery,
130 Hz ECG/calibration, contact loss, background/screen-off recording, Bluetooth
loss, session report vs exported RR, and an APK update retaining saved sessions.
Battery optimizers and vendor Bluetooth stacks can still interrupt recording.

## Dependency notice

Uses the [official Polar BLE SDK 8.5.0](https://github.com/polarofficial/polar-ble-sdk/tree/8.5.0)
and its [license](https://github.com/polarofficial/polar-ble-sdk/blob/8.5.0/LICENSE).
Polar/H10 names belong to Polar Electro Oy; this is an independent app.
Other dependencies include AndroidX, Kotlin, and kotlinx.coroutines; their
licenses are available from the respective upstream distributions.
