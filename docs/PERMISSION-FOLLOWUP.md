# Local permission follow-up

This development change follows candidate 0.1.0 (42), runtime `8a5516eb`,
without replacing its APK or qualification evidence. The development build
retains version 42; verify its source and hash before installing it. Both
native PRs retain the validation-foundations base and remain draft.

Local Bluetooth adapter address reads and the corresponding debug label are
removed. Diagnostic enabled/state reads tolerate permission revocation. The
force-finish receiver uses ContextCompat with NOT_EXPORTED while retaining
its signature permission. No privileged permission, lint suppression or
baseline was added; crypto, identity and wire formats are unchanged.

## Validation

- App JVM tests executed: 687 total, 684 passed, 3 inherited skips, no failure
  or error. Wear: 224 passed, no skip, failure or error. These are separate
  module collections, not additions to historical candidate results.
- App and Wear debug builds and lint completed. After removing only the
  now-unused adapter-address string from 34 resource files, assemble/lint
  was rerun; the preceding successful JVM results are reused for that
  resource-only deletion.
- Lint app: 298 errors / 318 warnings / 19 hints, down from 304/321/19.
  Wear: 33/50/10, down from 37/52/10. No added diagnostic. No remaining
  LOCAL_MAC_ADDRESS call; 30 MissingPermission transport errors remain
  per module. The inherited abortOnError=false still permits a successful
  task exit with errors.
- Development APK package/version/label, historical debug signer and
  zipalign16 checked. All 20 native libraries are identical to the historical
  candidate; four Arti libraries match their vendored inputs, and ELF64 LOAD
  alignment is at least 16 KiB. Test fixture/class markers are absent.

Raw reports and distinct development APKs remain ignored under
`.validation/artifacts/android-permissions-review/`. The historical42 APK
hash was rechecked unchanged. No native rebuild, physical 16 KiB, GUI,
TalkBack, new emulator launch or Mesh Lab result is claimed for this patch.
Radio/permission lifecycle on physical devices remains a stable-release gate.
No upload, workflow dispatch, retarget, release or distribution was performed.

## Next development permission batch

The next local batch starts from `d8d47dbd` and retains development version 42.
Adapter enabled-state reads in the connection, client and server managers now
reject startup when permission is revoked between the permission check and the
framework call. No manager reports startup accepted after that denied read.

Terminal tracker shutdown attempts disconnect and close independently for every
captured GATT before its owner cancels the coroutine scope. Previously, a denied
disconnect could interrupt all cleanup; the delayed close also looked up an
already-cleared connection map. Server shutdown detaches its GATT, characteristic
and link references and attempts all cancellations and close despite permission
revocation. An individual tracker disconnect also attempts close when disconnect
fails, before dropping its reference; an accepted disconnect keeps the existing
callback path. The server disconnect error path no longer reads protected device
details.

The added synthetic JVM regressions exercise denied permission, revocation after
preflight, denied disconnect/close, cleanup continuation and repeated stop. The
shared app tests are generated into Wear using the existing Sync task.


### Next-batch validation

- JDK 21 offline app/Wear debug builds, full JVM tests and lint were executed
  together on the final Kotlin source. App: 696 total, 693 passed, 3 inherited
  skips, no failure or error. Wear: 233 passed, no skip, failure or error.
  These fresh module collections include the nine added cases on each target;
  earlier filtered test reports are intermediate evidence and are not reused.
- Normalized XML lint comparison against the archived `d8d47dbd` reports adds
  no diagnostic. App: 291 errors / 318 warnings / 19 hints (previously
  298/318/19). Wear: 26/50/10 (previously 33/50/10). Each module removes
  seven MissingPermission errors: three in the tracker and four in the server.
  The remaining 23 per module cover connection-limit disconnect, client
  scan/connect/GATT callbacks, server setup/responses and advertising stop.
  No suppression or baseline change was made; inherited abortOnError=false
  means successful lint tasks still report errors.
- Both development APKs were archived under distinct names. The app retains
  the Wesh package/label and version 42, the historical debug signer, zipalign16
  and all 20
  native libraries byte-identical to historical42. The four Arti inputs match
  their vendored files. Wear retains its existing package/version and signer;
  all four Wear native libraries match the preceding development APK.
  Packaged ELF64 LOAD segments remain at least 16 KiB on both targets.
  The two added test class markers are absent from both APKs.
- Local source hashes were captured before the full run and verified after it;
  generated Wear source/test hashes match the app source of truth. The
  historical42 and preceding app/Wear development APK hashes are unchanged.
  Raw reports, source proof, APK checks and new APKs remain ignored under
  `.validation/artifacts/android-permissions-next/`, separately from both
  historical42 and `android-permissions-review` evidence.

No device, emulator, ADB, live location, GUI, TalkBack or Mesh Lab validation
was attempted for this batch. Revocation timing, reconnect, background radio,
phone/watch interop and physical 16 KiB behavior remain qualification gates.
No native library rebuild, version bump, upload, deployment or distribution
was performed.

## GATT callback development batch

This batch follows `056cb6c1`, still with development version 42. The client
records a separate callback owner for every connection attempt, including the
interval before MTU negotiation. `connectToAddress` now returns the framework
attempt's actual acceptance, and rejects denied permission, duplicate attempts,
null GATT and failed startup. Each setup operation handles permission revocation
and refusal. Failed MTU negotiation retains the existing disconnect policy;
this batch does not introduce a fallback to the default MTU.

MTU, service discovery and CCCD writes are sequenced. The characteristic becomes
usable and the connected delegate fires only after a matching successful CCCD
acknowledgement. API 33 and newer use the descriptor write status API; older
versions check the legacy Boolean result. Setup expires after 30 seconds. Stop,
refusal, callback failure and timeout release the owned GATT and pending attempt;
disconnect and close are attempted independently. Old client callback owners
cannot mutate a replacement link, including a synthetic test that reuses the
same GATT object. Scan shutdown now completes synchronously and detaches the
old callback even if permission is denied. Results, batches and failures
delivered by an old callback after restart are rejected, and denied results are
rejected before reading device details. Delayed scan starts respect the desired state.

The server catches denied open, service publication, responses and advertising
stop. Advertising waits for successful service publication. A separate resource
admission event enforces connection budgets as soon as a central registers,
including centrals that never enable notifications; readiness still waits for
CCCD acceptance. Descriptor and
packet responses must be accepted before subscription, readiness or packet
dispatch advances; a false response leaves the request retryable. Invalid CCCD,
prepared/offset writes and malformed packets receive failure when a response
is requested. Server stop
captures its old resources before cleanup. Callbacks and delayed work from a
stopped server generation cannot be admitted into its replacement.

Server generation checks qualify admission to a callback; delegate callouts are
performed outside the server lock. An already-admitted event may still complete
while stop runs. Android server callbacks expose a device address rather than a
connection-generation token, so a late callback from a previous connection using
the same address within one still-running server generation remains ambiguous.
Physical reconnect and radio timing qualification remains required. No claim of
complete elimination of Bluetooth races is made.

### GATT callback validation

- Final JDK 21 offline debug assembly, lint and the full Wear JVM collection
  completed together. That session reused the already-current app test task,
  so a separate unfiltered `:app:testDebugUnitTest --rerun` executed the full
  app collection again on the same frozen Kotlin sources. Final XML: app
  792 total / 789 passed / 3 inherited skips; Wear 329 passed with no skip.
  Neither module has a failure or error. Earlier filtered and interrupted
  runs remain intermediate evidence and are not reused for these counts.
- The new callback regressions contribute 96 cases on each target: 31 client
  and 17 server methods each executed on synthetic SDK 32 and 35 runtimes.
  They cover API-specific descriptor acceptance, permission denial and races,
  failed and late callbacks, timeouts, cleanup, stop/restart, scan ownership,
  and resource admission before CCCD readiness. Robolectric is not a physical
  device or emulator qualification.
- Normalized XML comparison against the archived `056cb6c1` reports removes
  20 MissingPermission errors per module. App: 271 errors / 318 warnings /
  19 hints (previously 291/318/19), no added diagnostic. Wear: 6/51/10
  (previously 26/50/10). Its sole added diagnostic is ObsoleteSdkInt on the
  shared API 32/33 descriptor branch: Wear requires SDK 33, while the phone
  still needs the older API path. The shared branch is retained without a
  suppression, baseline change or duplicate Wear source implementation.
  Three MissingPermission errors remain per module: connection-limit
  disconnect and two permission-protected debug device-name reads. Lint is
  still not clean; inherited abortOnError=false permits successful task exit.
- Both distinct development APKs passed package/version, historical debug
  signer and zipalign16 checks. The app remains Wesh version 42, with all 20
  native libraries byte-identical to historical42; all four Arti libraries
  match their vendored inputs and the checksum verifier passed. Wear retains
  its existing package/version and all four native libraries match the preceding
  development APK. Packaged ELF64 LOAD alignment remains at least 16 KiB on
  both targets. New test class markers are absent from both APKs.
- Source hashes were captured before assembly and verified before the fresh
  app run and after validation. All six changed production/test sources match
  the generated Wear copies. Historical42 and both preceding development APK
  hashes remain unchanged. The distinct current APKs and raw reports are local
  and ignored under `.validation/artifacts/android-gatt-callbacks/`.

Review corrected deferred client shutdown affecting replacement connections,
late scan callback admission, and server budgets for centrals that never enable
notifications. No further actionable defect was established in the final local
review. Physical permission revocation, reconnect timing, background radio,
phone/watch interop, accessibility and 16 KiB runtime qualification remain open.
No device, emulator, ADB, live location, GUI, TalkBack, Mesh Lab, upload, native
library rebuild, version bump, deployment or distribution was used or claimed.
