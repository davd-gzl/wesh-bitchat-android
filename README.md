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

Candidate **0.1.0 (41)** was built from runtime source
`74f2a79490129ee91abacfd5f1835b45dae69d79`.

- App and Wear debug builds passed. App JVM tests: **656 passed, 3 inherited
  skips (659 total)**. Wear JVM tests: **224 passed, no skips**. Wear remains
  an inherited module outside the Wesh V1 product scope.
- The four Arti libraries and both checksum manifests are byte-identical
  between two clean builds in the same pinned environment. Independent review
  checked their ELF/JNI properties and source correspondence.
- The local universal APK passed package, label, version, existing debug-signer
  and ZIP alignment checks. All 20 native ZIP entries are stored uncompressed;
  all LOAD segments in its 10 packaged 64-bit libraries meet 16 KiB alignment.
  This does not qualify a physical 16 KiB device.
- App lint reported **305 errors, 320 warnings and 19 hints**. No diagnostic was
  added or located on a changed line relative to candidate 40. Its 20 fewer
  warnings are newer-version suggestions absent in the offline run, not source
  fixes. Wear lint reported **37 errors, 51 warnings and 10 hints** after its
  missing locked dependencies were downloaded. Both module tasks completed,
  while the first global offline invocation failed at Wear dependency resolution.
  The inherited `abortOnError = false` remains; this is not lint clean.

The known isolated emulator accepted installation. The first startup recorded
an ANR while completing startup, and a retry exceeded the launch wait timeout;
a later process remained alive. A subsequent controlled cold launch completed
in about 28 seconds and stayed alive for the bounded health check. Retain the
initial ANR and slow startup as an unresolved QA limitation; no cause or GUI /
TalkBack success is established by these CLI checks. The existing ANR trace
was not readable through ordinary ADB access; no elevated diagnostic access
was used.

The earlier candidate 40 remains historical. Raw APKs, exact artifact hashes,
command logs and detailed provenance are retained locally, outside Git.

This is a local development candidate. Store publication, pilot distribution,
regulatory classification and tester invitations require their own decisions.
Physical BLE interoperability, multi-hop delivery, background and battery
behavior, full GUI accessibility and TalkBack audio remain unqualified. An
emulator startup or static 16 KiB check cannot establish these properties.

## Build and test

Use JDK 21, the SDK / Build Tools pinned in `gradle/libs.versions.toml` and the
checked-in Gradle wrapper. See [CONTRIBUTING.md](CONTRIBUTING.md) for the evidence
and privacy rules.

```sh
python3 tools/arti-build/test-provenance.py
tools/arti-build/verify-checksums.sh
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest
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
- [Upstream release guide](docs/maintainer-release-guide.md)
- [Upstream reproducible-build guide](docs/reproducible-builds.md)

The checked-in [LICENSE.md](LICENSE.md) is GPLv3. The upstream public-domain
claim conflicts with that file, as recorded in the historical baseline.
Preserve third-party notices and licensing information; distribution requires
license and corresponding-source review. Do not infer distribution terms from
old upstream marketing text.
