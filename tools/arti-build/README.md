# Rebuilding Arti for Android

The four committed `libarti_android.so` files are built from the official Arti
source plus the JNI wrapper in this directory. The supported build path is the
pinned Linux container:

```bash
tools/arti-build/rebuild-in-container.sh
tools/arti-build/verify-checksums.sh
```

The container builds all four Android ABIs, verifies the exported JNI symbols
and rejects native binaries containing host-specific paths. It then rewrites
`tools/arti-build/SHA256SUMS` and `tools/arti-build/SOURCE_SHA256SUMS`.
These manifests bind the four vendored libraries to the reviewed lock, wrapper,
and pinned build inputs. The phone `preBuild` task rejects missing manifests or
changed input hashes; update them only through a successful native rebuild.
The builder captures read-only copies of the wrapper, dependency lock, and
pins before cloning or compiling; those copies supply the compiled wrapper.
It compares the active inputs with that snapshot before publishing its hashes.
An edit followed by a restore cannot change the captured wrapper being built.
This guards ordinary concurrent edits; it is not a claim of protection against
a hostile process that can alter the build environment. Nonempty `patches/`
directories are rejected until their inputs have explicit provenance support.
The container mounts and executes these repository scripts; its image copies
only `TOOLCHAIN.env`, and runtime checks verify the pinned tool versions.
Checksums identify inputs and outputs; the two clean rebuilds below establish
whether those outputs can be reproduced.

## Pinned inputs

- `TOOLCHAIN.env`: Arti tag and commit, stable build epoch, Rust, `cargo-ndk`,
  Android NDK, and immutable Debian snapshot
- `rust-toolchain.toml`: Rust release and Android targets
- `Cargo.lock`: complete Rust dependency graph
- `Dockerfile`: digest-pinned Rust base image and checksum-verified NDK archive
- `Cargo.toml` and `src/lib.rs`: JNI wrapper package and source

Rust source paths are remapped to stable virtual paths, incremental compilation
is disabled, and `SOURCE_DATE_EPOCH` is pinned to the value used for the
committed libraries.

## Updating Arti or the native toolchain

1. Update the tag, full source commit, stable build epoch, tool versions,
   archive name, and archive checksum in `TOOLCHAIN.env`.
2. Update `Cargo.toml` if Arti changed its crate features or wrapper version.
3. Regenerate `Cargo.lock` against the exact Arti checkout and review every
   dependency change:

   ```bash
   tools/arti-build/rebuild-in-container.sh --update-lockfile
   ```

4. Update the digest-pinned base image and `rust-toolchain.toml` when changing
   Rust.
5. Run `tools/arti-build/rebuild-in-container.sh` twice from clean generated
   state and confirm `tools/arti-build/SHA256SUMS` is unchanged.
6. Run the Android tests and the two-build release comparison described in
   [`docs/reproducible-builds.md`](../../docs/reproducible-builds.md).

Do not build release native libraries with an unpinned local NDK or Rust
toolchain. `build-arti.sh` deliberately rejects mismatched tool versions.

## Dependency security and local validation

The reviewed Android lock patches `rustls` to `0.23.46` and `rustls-webpki`
to `0.103.15`, while retaining the Arti tag, source commit, JNI API, and all
other dependency versions. A source-lock audit does not prove which code paths
an APK reaches or that a previously built library includes the patch.

The locked graph still includes `rsa 0.9.10`, affected by
[RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071.html),
which has no published compatible fix. Its private-key operation reachability
through this wrapper has not been established. `bincode 2.0.1` and
`paste 1.0.15` also carry unmaintained warnings. Do not describe this native
graph as free of advisories or waive the remaining RSA finding. Wesh disables
its Internet routes, but that does not remove the packaged native dependency.
Distribution remains gated on a documented resolution or risk decision; a
local development candidate does not authorize a store or pilot upload.

Audit the exact lock before rebuilding, using a current advisory database:

```bash
cargo audit --file tools/arti-build/Cargo.lock
python3 tools/arti-build/test-provenance.py
```

The provenance regression checks use synthetic temporary inputs and verify
that changing the lock, wrapper, or library, removing or truncating a manifest,
adding an unsupported patch, failing the strings scan, or embedding a host path
causes verification to fail. Large early matches and single-backslash Windows
paths are included. Snapshot tests also cover concurrent source edits and an
edit followed by a restore. Raw audit/build logs and
APK candidates stay local.

## JNI surface

The wrapper exports the native methods used by `ArtiNative`:

- `getVersion`
- `setLogCallback`
- `initialize`
- `startSocksProxy`
- `stop`

The build fails if any expected symbol is absent. The pinned NDK `readelf`
check requires every LOAD segment in each 64-bit library to have at least
16 KiB alignment. No 16 KiB compatibility claim is made for the 32-bit ABIs.

## References

- [Arti source](https://gitlab.torproject.org/tpo/core/arti)
- [Cargo lockfiles](https://doc.rust-lang.org/cargo/faq.html#why-have-cargolock-in-version-control)
- [Rust source-path remapping](https://doc.rust-lang.org/rustc/remap-source-paths.html)
- [Install a specific Android NDK](https://developer.android.com/studio/projects/install-ndk)
