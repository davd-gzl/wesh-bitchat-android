# Wesh Android validation fork

This repository validates Wesh’s native Android experience on the upstream
[BitChat Android](https://github.com/permissionlesstech/bitchat-android) codebase.
It is an experimental fork, with production architecture still subject to the
cross-platform physical-device validation sprint.

Wesh V1 combines a public local Bluetooth mesh conversation with private
contacts verified by scanning each other’s live QR code. Public messages can be
read, relayed and retained by mesh participants. Private identity verification
is local to this phone; a successful local handoff does not prove remote receipt
or reading. The Wesh shell disables Internet, geographic and Wi-Fi Aware routes.
It does not turn packaged upstream dependencies into absent code.

The app uses the system typeface, a polished silver / pure black interface and
an isolated application ID, `org.samouraiworld.wesh.android`. It does not replace
an upstream BitChat installation. MeshCore and Meshtastic adapters are long-term
roadmap exploration outside V1.

## Current development candidate

Candidate **0.1.0 (42)** was built from runtime source
`8a5516eb4286a6df97d8790e0cdac8cb2aedc13c`.

Wesh now prepares encrypted preferences and persisted crypto identity on IO,
with a process-owned initializing / ready / failed state and explicit retry.
Unreadable, partial or incorrectly sized stored keys do not become replacement
identities. Checked persistence fences prevent a failed commit from being
accepted just because its values entered memory. Bluetooth / unified service
construction stays on Main and receives prepared crypto directly. The latest
bounded pending route survives Activity state restoration and waits for secure
startup plus onboarding; QR review and per-send authorization remain in force.
Foreground-service promotion precedes suspension, and an ineligible sticky
restart stops without waiting for the keystore. See the
[startup verification and limits](docs/validation/STARTUP-PREPARATION.md).

Image export now uses an explicit document destination on API 26–28 and the
Downloads API on API 29+, performs storage work on IO, and reports actual copy /
publication failure. It adds no broad storage permission. This does not qualify
all inherited image formats or physical providers.

- App debug build and its full JVM collection passed: **684 passed, 3 inherited
  skips (687 total)**. The final global invocation reused the Wear build,
  **224 successful JVM cases** and lint results with unchanged shared inputs;
  their actual execution also passed in the preceding complete run. Wear
  remains an inherited module outside the Wesh V1 product scope.
- The four Arti libraries and both checksum manifests remain unchanged and
  byte-identical between the earlier two clean builds in the same pinned
  environment. Independent review checked ELF/JNI/source correspondence.
- The final universal APK passed package, label, version, existing debug-signer
  and ZIP alignment checks. All 20 native ZIP entries are stored uncompressed;
  all LOAD segments in its 10 packaged 64-bit libraries meet 16 KiB alignment.
  This does not qualify a physical 16 KiB device.
- App lint reports **304 errors, 321 warnings and 19 hints**; Wear reports
  **37 errors, 52 warnings and 10 hints**. Compared with candidate 41, the
  unsupported Downloads API error is removed. Each module has three new
  UseKtx style warnings and two old ones removed (net +1); explicit editor
  commits preserve their checked Boolean result. No new error, suppression,
  baseline or privileged permission was added. The inherited
  `abortOnError = false` remains; task success is not lint clean.

The known isolated API 35 emulator accepted the upgrade and its installed APK
hash matched the verified candidate exactly. Three CLI cold launches reported
Activity first-frame TotalTime values of **8.136 / 4.149 / 7.884 seconds**. The
same process stayed alive for 15 seconds after each, with no fatal observed in
its bounded crash buffer and no new ANR exit record in the targeted before /
after comparison. These are Activity/loading observations: secure-runtime
readiness, completed onboarding, actual conversations, GUI and TalkBack were
not independently observed. They are not phone performance or full ANR tests.
The headless emulator was then shut down cleanly.

An earlier GUI emulator process exited with code 139 before candidate 42 was
installed; its cause is not established and cannot be attributed to the app.
Candidate 41's initial app ANR and one launch observed at roughly 28 seconds
remain historical limitations, with no causal resolution inferred from this
candidate's different first screen. The disposable earlier instrumented probe
was incomplete and is not a performance baseline. Candidate 40 / 41 artifacts
and provisional candidate-42 source checks remain preserved separately locally.

This is a local development candidate. Store publication, pilot distribution,
regulatory classification and tester invitations require their own decisions.
Physical BLE interoperability, multi-hop delivery, background / battery behavior,
full GUI accessibility, TalkBack audio and service deadlines remain unqualified.
Raw APKs, exact artifact hashes, command logs and detailed provenance stay local.

## Build and test

Use JDK 21, the SDK / Build Tools pinned in `gradle/libs.versions.toml` and the
checked-in Gradle wrapper. See [CONTRIBUTING.md](CONTRIBUTING.md) for the evidence
and privacy rules.

```sh
python3 tools/arti-build/test-provenance.py
tools/arti-build/verify-checksums.sh
./gradlew :app:assembleDebug testDebugUnitTest
./gradlew lintDebug
```

The four Arti libraries are tracked vendored build inputs. The phone’s
`preBuild` gate checks both their hashes and the exact wrapper, lock and pinned
build-source hashes. A source-only dependency edit with stale libraries is
rejected. Follow the [native rebuild guide](tools/arti-build/README.md), including
two clean native builds and comparison of all four outputs.

The native lock patches rustls to 0.23.46 and rustls-webpki to 0.103.15. It still
contains RSA RUSTSEC-2023-0071 and unmaintained bincode / paste warnings. Keep
this gate explicit: disabled Internet routes and a fixed TLS library do not
establish private-RSA reachability or an advisory-free native graph.

## Identity parser and interoperability evidence

The QR verifier rejects malformed binary verification controls with unsigned,
bounded lengths, exact field sizes and no trailing fields. Ten parser
regressions include the formerly throwing high-bit length inputs. Existing
challenge/response frames and the signing preimage are unchanged: 85 / 151
bytes including the message type, with a 103-byte response signing preimage
excluding that type.

Four shared signed-QR tests use the same immutable synthetic fixture as iOS,
the real signature verifier, tampered fields and clock boundaries at ±300 /
301 seconds. These are API and source-level wire checks; live scanning, mixed
client sessions and physical transport interoperability remain open. Test
fixture URL, filename and test-class markers were absent from the APK. Pattern
collisions in native lookup tables are not evidence of semantic key leakage
or a proof that every matching byte pattern is absent.

## Validation and contribution

The [original baseline](docs/validation/BASELINE.md) describes the upstream
starting point, including its old package/version and toolchain results; those
facts are historical. Current candidate facts belong above and in the local
provenance record. Source-only unit tests and build checks do not substitute for
physical Mesh Lab evidence.

Use feature branches and pull requests. Keep raw APKs, build logs, signing
material, contact data and device identifiers local. Synthetic fixtures are
required. Read [AGENTS.md](AGENTS.md) before working on the fork.

## Upstream references and license

The fork retains the existing upstream mesh / Noise wire formats while adding the
Wesh UI and local identity gates. Unchanged wire formats still need physical
cross-client validation. Upstream Internet, location, store and release
procedures describe upstream functionality, not enabled Wesh routes or an
authorization to publish this candidate.

- [Upstream Android repository](https://github.com/permissionlesstech/bitchat-android)
- [Upstream iOS repository](https://github.com/permissionlesstech/bitchat)
- [Wesh Android release guide](docs/maintainer-release-guide.md)
- [Wesh reproducible-build guide](docs/reproducible-builds.md)

The checked-in [LICENSE.md](LICENSE.md) is GPLv3. The upstream public-domain
claim conflicts with that file, as recorded in the historical baseline.
Preserve third-party notices and licensing information; distribution requires
license and corresponding-source review. Do not infer distribution terms from
old upstream marketing text.
