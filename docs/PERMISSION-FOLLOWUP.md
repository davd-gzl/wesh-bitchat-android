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
