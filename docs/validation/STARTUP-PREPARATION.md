# Android startup verification — candidate 0.1.0 (42)

Runtime source `8a5516eb4286a6df97d8790e0cdac8cb2aedc13c` is frozen and locally
built/checked. Its follow-up documentation does not change the artifact's source.
Candidate 41 and its APK remain historical reference evidence; its initial ANR
and one cold launch observed at roughly 28 seconds are not retroactively
resolved by these source changes, unit tests or a different first screen.

## Behavior

The Wesh process prepares encrypted preferences, persisted identities and the
crypto service on an IO dispatcher. A process-owned coordinator publishes
Initializing, Ready or Failed; an Activity can be destroyed while preparation
continues without creating a second identity. Retrying an unavailable store is
explicit and does not delete data or generate a replacement for an unreadable
identity.

The first UI is a small loading or retry surface inside the existing polished
silver / pure-black Wesh theme. It constructs neither the chat ViewModel nor
its contact and message operations before secure startup completes. The native
Bluetooth / unified service constructors remain on Main and receive the
already prepared crypto service, preserving their Android lifecycle assumptions.
Encrypted preference handles are process-cached so the ViewModel's additional
identity manager does not reopen the master key on Main. The application does
not delete that master-key alias during panic; clearing data updates the same
preference handle.

Foreground-service promotion and its notification occur before waiting for
secure preparation. A recreated sticky service waits for the same coordinator,
checks stored background policy and current Bluetooth permissions before
starting transport, and cancels its waiter on stop or quit. A failed bootstrap
stops its foreground notification/service. Preparing the core objects alone
is not a radio start, successful discovery or proof of message delivery.

The Activity retains only the latest incoming Intent while startup/onboarding
are pending and consumes it once when both gates are open. Both the consumed flag and a minimal pending Intent are saved with Activity
state. Only a verification URI up to 4096 UTF-8 bytes and private-navigation
fields (peer up to 128 bytes, nickname up to 255) survive. Arbitrary extras and
geographic routes are excluded. Quit is handled immediately and never saved. The existing QR review, freshness, session-key,
enrollment and per-send authorization checks remain authoritative; a deep link
does not silently enroll or send anything.

## Identity failure handling

Both Noise and signing-key reads distinguish first use (both relevant entries
absent) from partial, corrupt or unreadable storage. Persistence must succeed
before a new key can be returned for use. A failed `SharedPreferences.commit`
can still change its in-memory map: a process-wide fence requires an explicit
successful rewrite before a later read can accept that identity. It retries the
same values rather than generating new ones.

A panic wipe supersedes pending failed writes and cannot replay them into the
cleared store. If the wipe fails, later identity reads remain fenced until a
successful wipe. The existing panic and database lifecycle still require their
own broader review and physical-device validation. Cached preferences are not
an assertion of secure erasure or resistance to external keystore changes.

No wire format, key algorithm, application ID, Arti source, native library or
native checksum manifest changes are part of this startup batch. The runtime
Ready state is deliberately scoped to this preparation; it does not certify
all database writes/restores or make a received/read receipt claim.

## Checks on the frozen source

The global offline Gradle invocation completed successfully with app / Wear
debug builds, both complete JVM collections and lint tasks. App: **684 passed,
3 inherited skips (687 total)**. Wear: **224 successful cases**, reused
UP-TO-DATE in the final run with unchanged shared inputs after their preceding
successful execution. No class or method filter was used for the full run.

The full app collection includes these new behavior cases:

- 4 coordinator/route tests: suspended startup, one process initialization,
  explicit failure/retry, cancelled UI waiter with no post-ready action and a
  bounded, once-only route.
- 8 identity-storage tests: absence versus partial/corrupt values, read failure,
  failed commits, stable signing identity across retry and legacy migration.
- 3 persistence-fence tests: memory modified despite commit failure, repair of
  the same identity, failed wipe/removal and no resurrection after panic.
- 6 Intent cases (3 tests on API 28 and 33): allowlisted bounded fields, no saved
  quit/geographic route, and a real Bundle/Parcel round trip before consumption.
- 7 image-export cases from the separate API-compatibility batch.

The earlier 12-test and 22-test targeted runs have separate local evidence;
their counts are subsets and must not be added to the full collection.
App lint remains **304 errors / 321 warnings / 19 hints**, Wear
**37 / 52 / 10**. The Downloads NewApi error is removed. Each module adds three
UseKtx diagnostics and removes two earlier ones; the explicit checked commit
result is required, and no suppression or baseline was added. Removing the
transient prepared-crypto singleton field avoids an added StaticFieldLeak
warning without claiming that its application context was a confirmed leak.

The final APK passed package/version/label/debug-signer/zipalign checks, all
20 native entries are stored, all 10 packaged ELF64 libraries have at least
16 KiB LOAD alignment, and all four Arti binaries match the unchanged vendored
inputs. Test fixture URL, filenames and test-class markers were absent; inherited
byte-pattern lookup-table collisions do not establish semantic seed presence.
The installed emulator APK hash matched the verified artifact exactly.

In an isolated headless run of the known API35 AVD, three `am start -W` calls
reported first-frame TotalTime **8.136 / 4.149 / 7.884 seconds**. After each, the
same process remained alive for 15 seconds and its bounded PID crash buffer
showed no fatal. A targeted exit-info comparison observed no new ANR exit
record; that cannot exclude a live hang or all ANRs. Secure-runtime readiness,
completed onboarding, live UI/conversation operation and TalkBack were not
independently observed. The AVD was stopped cleanly afterward.

A prior GUI QEMU process exited 139 before candidate42 installation. That is a
tool failure with an unestablished cause, not an app crash observation. No new system
image, data wipe, snapshot recovery, GPS, camera, microphone or physical phone
was used for the successful headless CLI observations.

These are JVM/API and bounded process tests using synthetic test identities.
They do not qualify a foreground-service deadline on a phone, actual GUI /
TalkBack, BLE, background/battery behavior, physical16KiB behavior or the cause
of candidate41's original ANR. Raw XML, logs, hashes, APKs and machine/device
metadata remain outside Git.

## Remaining qualification

1. Observe actual secure runtime / onboarding and the main product screens,
   including loading, retry, exact QR/session gates and pending-route recovery.
2. Complete GUI and TalkBack traversal on a usable authorized surface.
3. Run the physical iOS/Android Mesh Lab, including service deadlines, permission
   revocation, private/public delivery, background and battery policies.
4. Review inherited permission/API/localization debt in separate source batches.
   Do not add privileged LOCAL_MAC_ADDRESS permission or style suppressions.
5. Resolve native dependency/security, licensing and regulatory release gates
   before deciding pilot distribution.

No store upload, invitation, distribution, regulatory filing or merge occurred.
