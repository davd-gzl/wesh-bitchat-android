# Local encrypted drafts

Wesh drafts are local data, never queued sends. Public drafts and private drafts
use separate destinations. A private destination contains the complete Noise
public key from the QR contact record; short mesh IDs, nicknames and aliases are
not accepted. The local owner is also the complete Noise public key, obtained
from the prepared runtime, without creating or replacing an identity on read.

The bounded document lives in the app's no-backup directory. AES-GCM uses a
dedicated Android Keystore alias and versioned associated data bound to the
local owner. Text, destination keys and owner are inside the encrypted envelope;
no draft is written to plaintext preferences or SavedState. The shared cipher
now loads an existing key for decrypt, failing on absent, inaccessible or invalid
aliases. It creates a key only for encrypt; the draft backend first verifies
that existing storage is absent or readable for the same owner.

The schema accepts only its own canonical version-one JSON representation.
Unknown fields, duplicate keys/destinations, wrong types, invalid UTF-8,
authentication failure and oversized data fail restoration. Filesystem probes
conclude absence only on NoSuchFileException; access errors, directories,
symlinks and incomplete atomic files cannot become an empty default.

Limits are 64 destinations, 8,192 UTF-8 bytes per draft and 131,072 UTF-8 text
bytes in total. Known loaded drafts evict the oldest entries when count/total
limits are reached. While restore is unconfirmed, at most 64 destinations may
be edited; additional destinations are rejected so deletion tombstones are not
forgotten. Encoding/envelope reads are separately capped before parsing.

The process-owned store serializes bounded, conflated writes on IO. Startup
restoration happens after secure runtime readiness. Edits made while storage is
unavailable remain in RAM and merge only after successful restoration; they
never overwrite unknown or malformed disk contents. Confirmation requires atomic
write plus exact decrypted readback. A byte-exact confirmed ciphertext baseline
and one owned unconfirmed candidate protect replacements: even a valid but
divergent same-owner document is retained without overwrite. A recognized prior
candidate becomes the rollback baseline before another commit is attempted.
Failed commit/readback keeps the draft dirty for explicit retry. French UI distinguishes pending memory, confirmed storage
and errors; the last keystroke is not promised durable before confirmation.
A captured send includes destination, exact text, destination revision and store
generation. Accepted callbacks cannot clear later edits, including ABA edits
that return to the original text, or text belonging to another local identity.

Panic immediately fences RAM and pending writes. Before any draft deletion or
key destruction, an atomic, data-free marker records the interrupted operation.
Failure keeps the marker and the gate. During a normal completed panic, a changed
local Noise identity and confirmed draft erasure are required before the marker
is removed and private admission/transports resume. Existing global panic steps
outside this draft change retain their earlier verification limits; the marker
does not independently prove every global deletion completed.

If a marker remains on startup, runtime preparation stops before transport
construction. There is no automatic repair or selective draft-wipe bypass. The
same recovery screen appears after an incomplete panic in the current process.
The user can explicitly ask Android to erase all Wesh application data after a
confirmation covering identity, messages, drafts, settings and permissions to
reauthorize. Android accepting this request means only that erasure was requested;
the app keeps its gate and does not claim completion or remove the marker.
A refusal/exception remains visible and retryable. Tests inject this OS action;
no real app-data erasure is run by validation.

A crash or storage failure before the initial panic marker is committed cannot
guarantee erasure across process death. The running process remains fenced and offers the explicit
Android recovery. Other-identity or unreadable ciphertext is retained without
mixing; resolving it requires successful restoration for its owner or explicit
panic/recovery, never automatic replacement.

All tests use synthetic values, injected key providers/backends and temporary
files. They do not access a real Keystore, devices, location or production data.
Physical lifecycle, process termination, Android system erasure and accessibility
qualification still require the unavailable devices. Local raw validation and
distinct development APKs stay ignored under
`.validation/artifacts/android-drafts-lint/`; historical evidence is preserved.


## Historical draft-persistence validation

The final JDK 21 offline run assembled app and Wear debug APKs, forced both
complete JVM test tasks with --rerun and no filter, then ran lint. App: 834
tests, 831 passed and 3 inherited skips. Wear: 345 passed, no skips. No failure
or error occurred. The 33 app / 4 Wear targeted cases are included in these
full collections and are not additional historical candidate results.

Normalized XML comparison with the preceding permissions-final evidence adds
no diagnostic. App has 260 errors / 314 warnings / 19 hints, down from
268/318/19. Wear has zero errors / 47 warnings / 10 hints, down from 3/51/10.
No MissingPermission remains. The lint cleanup removes eight app errors and
three Wear errors plus four warnings per module; inherited abortOnError=false
still means task success alone does not establish a clean report.

All 662 frozen build-input hashes match before and after the full run. The
nine changed shared production/test files generated for Wear match their app
sources exactly. Historical42 and the preceding development app/Wear APK
hashes are unchanged. New APKs retain the package, versions and debug signer;
the app remains development version 42. Signer, zipalign16, ELF64 LOAD alignment
and native-byte equality checks passed; app contains the same 20 native
libraries as historical42, including the four verified vendored Arti inputs.
Wear's four native libraries match its preceding development APK. New test
class markers are absent from both APKs.

Distinct APKs, normalized deltas, raw reports, input manifests, source patch,
targeted evidence and verification scripts remain local and ignored in the
new artifact directory. No actual Keystore, Android data-erasure action,
device, ADB, emulator, GUI, live location, upload or distribution was used for
this validation. Physical process/power loss, panic recovery and accessibility
remain qualification gates.


## V1 send admission and global panic follow-up

Public Wesh submission now awaits the concrete BLE handoff result before adding
its local echo or clearing the captured draft. Availability and the service
request generation are rechecked across the suspension. A failed fragment stops
the remaining train and reports one failure. Cancellation retains coroutine
semantics. A rejected submission can follow a partial transmission; retry may
therefore duplicate content already sent. Acceptance is not a recipient receipt.

Global panic now also requires the synchronous `wesh_ui` preferences clear to
commit successfully and read back empty before its final recovery barrier can
remove the durable marker or restart transport. Failure of preferences, draft or
conversation erasure preserves the recovery gate. This closes an omission that
could retain the personal note after a nominally completed panic. Other global
steps still retain their earlier verification limits.

The ViewModel and UI share one preferences owner. Panic immediately fences edits
and clears the in-memory note. The editor no longer saves note text in
`rememberSaveable`. Edit/save callbacks carry the immutable generation captured
by their composition; callbacks from before panic remain rejected after a
successful resume, without overwriting a newly entered note. The draft help text
now accurately describes encrypted persistence after application closure.

Tests use injected preferences and isolated synthetic markers, including commit
false after an in-memory clear, exceptions, nonempty readback, other wipe failure
and stale callbacks after resume. They do not prove system IME/rotation behavior,
power-loss durability or physical erasure. Current local validation evidence is
ignored under `.validation/artifacts/android-v1-readiness/`; previous results
above belong to their historical source.


## Confirmed personal-note save

The note's Save action is separate from encrypted conversation drafts. It now
waits off Main for SharedPreferences commit success and an exact value readback
before showing confirmation inside the note sheet. Failure retains the edited
in-memory text and offers retry; the message says the save is unconfirmed,
without claiming that no write occurred. Every retry performs the actual commit
even when a failed earlier call already changed the preferences RAM cache.

A separate coroutine mutex serializes note writes and panic clearing. The RAM
fence takes no IO lock: it invalidates editors immediately, while panic waits
for any in-flight commit and clears afterward. Queued saves recheck their
captured generation and revision before writing. Confirmation is checked again
after returning to the caller dispatcher, so an edit or panic that supersedes
the snapshot cannot announce success. Cancellation of a save propagates;
panic clearing is non-cancellable. This is an OS commit/readback contract,
not proof of physical power-loss durability.


## Media erasure and the Wesh wipe control

The normal global wipe is now reachable from Wesh settings through an explicit
French confirmation. Cancelling or dismissing only closes that dialog. Its
confirmation calls the existing panic routine, whose progress/recovery screens
continue to hide normal actions; no alternate reset bypass is introduced.
The copy distinguishes local data from external copies and physical erasure.

Plaintext attachments require confirmed deletion as well as conversations,
drafts and preferences before the marker can be removed or transport resume.
Media cleanup runs on IO, captures storage roots once, and attempts every target
even when another fails. A refused delete, exception, unexpected entry type,
unknown stat result or remaining directory prevents success. Only an explicit
missing-path result counts as absence. Synthetic file tests cover retained
readable bytes after refusal, acknowledgement without removal, unknown probes,
exceptions, full cleanup and retry; they never erase actual QA application data.
