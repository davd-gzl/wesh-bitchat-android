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
