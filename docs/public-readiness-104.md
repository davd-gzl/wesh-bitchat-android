# Android public-readiness 104

This candidate keeps version name `1.0.0` and advances the Play version code to
`104`. Its About screen links to the French or English privacy policy according
to the active app language.

## Native crash symbols: public-release blocker

The four committed Arti libraries are stripped ELF files. Their dynamic symbol
tables expose the six required JNI entry points, but they do not contain the
Rust symbol or debug information needed to symbolicate native crash frames.
The native wrapper also sets `strip = true`, and the pinned build applies
`llvm-strip --strip-debug` before publishing each library.

Consequently, enabling `ndk.debugSymbolLevel` in Gradle would not recover useful
symbols from the current inputs. It would only repackage already stripped
libraries. Candidate 104 must not claim native crash symbol support, and the
Play native-symbol warning remains unresolved.

A complete correction requires the pinned Arti build to retain an unstripped,
path-remapped library for each ABI as a release-only symbol artifact, while the
app continues to package stripped libraries. That change must include:

1. a clean four-ABI native rebuild in the pinned container;
2. provenance for both packaged libraries and symbol artifacts;
3. two clean builds proving identical hashes;
4. validation that the symbol archive contains no host paths and resolves Rust
   frames for every ABI; and
5. release tooling that ships the reviewed symbol archive beside the AAB.

The current repository does not retain those unstripped inputs, so this cannot
be corrected honestly by an application-only Gradle change.

## `RUSTSEC-2023-0071`: public-release blocker

The pinned native lock still contains `rsa 0.9.10`. It is pulled directly by
`tor-llcrypto` and `tor-key-forge`, and through `ssh-key` from `tor-keymgr`.
`arti-client 0.38.0` depends on both `tor-llcrypto` and `tor-keymgr`. The advisory
has no published compatible fix in this graph.

The reviewed runtime surface creates client channels with no key manager and
has no identified private-RSA operation reachable from JNI. That bounded
finding supported the documented Beta 103 conditional acceptance; it does not
remove the affected code and does not authorize a public release.

Removing `rsa` locally is not a safe application-layer update:
`tor-llcrypto` also uses RSA for Tor public-key verification, so dependency or
feature surgery can change interoperability and security semantics. A valid
resolution requires either an upstream-compatible fixed graph or a separately
reviewed Arti/crypto patch that preserves Tor public verification while making
private-key operations constant-time or absent. It then requires a new lock,
four-ABI rebuild, dependency audit, JNI reachability review, reproducibility
checks, and Tor interoperability validation.

Candidate 104 intentionally leaves the pinned native graph and binary hashes
unchanged. Public promotion remains blocked on an explicit resolution and
review; the Beta 103 waiver must not be reused.
