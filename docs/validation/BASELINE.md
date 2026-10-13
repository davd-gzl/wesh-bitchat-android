# Android fork validation baseline

Validation started **13 October 2026**. This is a transport-validation experiment for Wesh; Weshalor remains a temporary project name. It does not decide the production architecture or change upstream radio, cryptography, application identity, or branding.

## Source and fork

- Fork: [samouraiworld/wesh-bitchat-android](https://github.com/samouraiworld/wesh-bitchat-android).
- Upstream: [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android).
- Baseline commit: [`6ade68256cf32ca327047ba7f618ac6514f205ff`](https://github.com/permissionlesstech/bitchat-android/tree/6ade68256cf32ca327047ba7f618ac6514f205ff), dated 11 October 2026.
- Validation branch: `chore/validation-foundations`.
- `upstream` tracks the original source; `origin` points to the Wesh fork. Preserve upstream history; propose all changes through feature branches and PRs.
- Baseline application ID is still `com.bitchat.droid`, version `2.0.2` / code `39`. Do not distribute it as a Wesh release. A separate package identity, signing policy, update-source policy, and branding need a later implementation decision.

## Build requirements and reproducible command

The current source is newer than the proposal's research snapshot. The version catalog and wrapper require JDK 21, Gradle 9.6.1, AGP 9.3.1, Kotlin 2.4.10, compile/target SDK 37, Build Tools 37.0.0, and minimum API 26. Use the wrapper and retain upstream dependency locks and verification metadata.

The SDK repository lists the platform package as **`platforms;android-37.0`**, not `platforms;android-37`. The initial SDK-manager request with the latter name failed. The corrected package and Build Tools installed into the ignored task-local SDK using an existing accepted license; no new license was accepted.

From the Android checkout, set `JAVA_HOME` to a JDK 21 installation and `ANDROID_HOME` to an SDK with those packages. Then run:

```sh
tools/validation/run-baseline.sh
# Equivalent underlying tasks:
./gradlew --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  :app:assembleDebug :app:testDebugUnitTest
```

The helper stores Gradle caches, Android user settings/debug signing material, and raw output under ignored `.validation/`. It disables automatic SDK installation into an existing SDK. It neither installs an APK nor runs radio tests. Package installation is a separate explicit setup step:

```sh
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platforms;android-37.0' 'build-tools;37.0.0'
```

Use only licenses already accepted by the account owner, or have the owner review newly required licenses. `platform-tools` supplies `adb` for later device work; it is not required for JVM unit tests.

## Observed result

**Phone debug APK build and existing phone unit tests passed.** The command completed with exit code 0 and `BUILD SUCCESSFUL in 9m 16s` (44 executed Gradle tasks). Parsed JUnit XML: **93 suites, 612 tests total; 609 passed, 0 failures, 0 errors, 3 skipped**.

All three skips are inherited `@Ignore` cases in `CommandProcessorTest` (lower/upper-case join and unknown-command handling); source comments attribute them to Mockito final-class issues. No tests were added, removed, retried, or newly disabled. This is a passing upstream baseline with existing coverage gaps, not a completely unskipped suite.

Five local APKs were produced: `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-x86-debug.apk`, `app-x86_64-debug.apk`, and `app-universal-debug.apk`. Nothing was installed on a device. Unit results are at `app/build/test-results/testDebugUnitTest/`; the local HTML report is `app/build/reports/tests/testDebugUnitTest/index.html`.

Existing compilation warnings include deprecated APIs, unchecked casts, and unnecessary null assertions; they did not fail the build. The task-local SDK lacks `platform-tools`, generating a warning; read-only inventory used a separate available ADB binary. Phone lint, Wear OS builds/tests, instrumented tests, release builds, and physical radio tests were not run. No production signing credentials were used.

The first sandboxed attempt failed before configuration because network access was unavailable. With approved network access, the wrapper download and project configuration succeeded. SDK package naming was a setup issue. These are environment/setup findings, not failed protocol tests.

Raw logs remain local at `.validation/logs/baseline-build-tests.log`; SDK setup output remains under `.validation/logs/`. Do not publish these logs: they can contain local paths and other identifying metadata. Publish only reviewed, sanitized result summaries. APKs, if produced, remain under `app/build/outputs/apk/debug/`; ABI splits are enabled for assemble tasks, so select the appropriate split or universal APK rather than assuming the README's single APK filename.

The read-only ADB inventory found **zero connected Android devices**. No app was installed, no app data was cleared, and no physical transport result has been measured.

## Device-validation plan

Recruit a pool of 8–12 consenting test devices across iOS and Android. Minimum Android coverage: two manufacturers; one older supported Android/API class and one current release; BLE central/peripheral capability; battery-saving modes; optionally one Wi-Fi Aware device to isolate that transport separately. Include both direct Android pairs and mixed Android/iOS pairs built from recorded commits. Use synthetic identities/messages and neutral device labels such as A/B/C. Keep serials, peer IDs, Bluetooth addresses, live location, screenshots with identifying information, and raw logcat local.

Before installation, the test owner checks whether upstream BitChat is already installed. The baseline package ID conflicts with upstream, and a different debug signer can prevent updates. Do not uninstall upstream or erase existing user data to work around this. Prefer dedicated test phones; decide a separate test package ID in a reviewed implementation if coexistence is required.

| Scenario | Procedure and evidence |
|---|---|
| Offline direct DMs | Airplane mode on, Bluetooth explicitly re-enabled, Wi-Fi/mobile data off, internet fallback disabled. Verify network isolation on every phone; send 20 short synthetic messages per direction and record acknowledgement latency, duplicates, loss, and actual recipient arrival. |
| Mixed platforms | Repeat Android→iOS and iOS→Android with identities verified by QR where supported. Record both exact client commits; README compatibility claims are not evidence. |
| ACK accuracy | Compare sender delivery state to receiver observation and a valid recipient ACK. Exercise recipient unavailable, delayed ACK, reconnect, duplicates, and timeout. No false delivered state is acceptable. Delivery does not mean read. |
| Reconnection | Toggle Bluetooth, move a peer out of range and back, restart app/device, deny/grant Bluetooth permission. Distinguish recovery from retaining an existing link. |
| Background lifecycle | Repeat existing-link and fresh-discovery cases with sender/receiver foreground, background, screen locked, process killed, and user force-stopped. Record each state separately; foreground-service presence does not prove reliability. |
| Battery policy | Repeat normal mode and vendor/OS battery saver; document permission and battery-optimization settings. Measure a 30-minute locked/background run against a matched idle baseline. Set supported conditions from evidence. |
| Multi-hop | A→C through B with no direct A–C path and no internet route. Demonstrate B's relay contribution using controlled placement and an A/C control run without B. Report direct and relay rates separately. |
| Optional transport | Isolate BLE-only first. Test Wi-Fi Aware separately on supported Android hardware; do not credit BLE with a Wi-Fi path or assume mixed iOS support. |
| Permissions | Bluetooth denial, older BLE-related location permission requirements, notification denial, and optional permissions. Confirm understandable unavailable states and recovery. Do not collect live location. |

The proposal's foreground targets remain **unmeasured**: at least 95% of short DMs acknowledged within 30 seconds in explicitly supported scenarios and zero false delivered indicators. Record denominators, device/OS classes, app commits, radio/lifecycle settings, and defects; do not extrapolate a small lab to crowd-scale reliability.

Retain upstream `docs/device-transport-test-matrix.md`, `docs/testing-conventions.md`, and `docs/release-gate-runbook.md` as engineering references. Upstream Mesh Lab hooks target Android devices/watch; they are not a substitute for the mixed-platform manual matrix. No Android instrumentation source set is present in this snapshot, so declaring `connectedAndroidTest` does not establish existing device tests.

## Distribution and next gate

Debug APK signing is suitable only for local validation. No production signing credentials were used. Establish Wesh's own application ID, signing/update certificate policy, Play upload configuration, and privacy-preserving debug-key storage before any beta distribution. The upstream release guide signs outside CI; keep signing keys and passwords out of Git.

The upstream README still says public domain, but `LICENSE.md` contains GPLv3. Preserve license/notices and treat Android as GPLv3 for planning until clarified; do not copy Android code into the iOS client as a shortcut. Distribution requires an explicit licensing review and corresponding-source plan.

A build pass will unlock physical testing; it will not demonstrate offline reliability, security, UX success, licensing clearance, or readiness for store submission. The next decision is a measured go/no-go after the device matrix and user-demand sprint, followed by a revised implementation plan.
