# Self-contained distributions

Builds a bundle containing a trimmed JDK runtime and the tunnel, so users need no
Java installed.

```bash
mvn package
dist/build-runtime.sh                                  # -> dist/testingbot-tunnel-<os>-<arch>/
dist/verify-runtime.sh dist/testingbot-tunnel-macos-arm64
```

Result: ~24 MB compressed, ~38 MB unpacked, launched with
`bin/testingbot-tunnel` (`bin\testingbot-tunnel.cmd` on Windows).

Each archive gets a `.sha256` beside it, in the `HASH  FILE` form `sha256sum -c`
reads back:

```bash
cd dist && sha256sum -c testingbot-tunnel-linux-x64.tar.gz.sha256
```

Written here rather than in the release workflow so a local build and a released
artifact are checksummed by the same code. The release job uploads these
alongside the archives, and does the same for the runnable jar. A Homebrew
formula or a winget manifest needs exactly this value, and one produced by a
separate path is one more thing that can disagree.

`jlink` emits a runtime for the platform and architecture it runs on, so each
target is built on its own runner. The `native` job in
`.github/workflows/release.yml` covers linux x64/arm64, macOS x64/arm64 and
windows x64, and attaches the archives to the GitHub release.

## Why the module list is curated

`jdeps --print-module-deps` is not trustworthy here. The shaded jar is minimized
and the application loads plenty by reflection, so jdeps under-reports — it omits
`java.logging` even though the code uses `java.util.logging` throughout, and it
cannot see the JCE providers TLS pulls in at runtime.

Getting the set wrong fails at runtime rather than at build time, which is why
`verify-runtime.sh` exists and why the release job runs it before uploading. Each
check maps to a module that is easy to lose:

| Check | Module it proves |
|---|---|
| `--doctor` reaches HTTPS endpoints | `jdk.crypto.ec` (EC ciphers), `java.naming` |
| `/metrics` contains `jvm_`/`process_` series | `java.management` |
| logback parses `logback.xml` without errors | `java.xml` |
| HTTPS CONNECT through the running tunnel | the TLS stack end to end |

Dropping `jdk.crypto.ec` and re-running the verifier is a quick way to confirm
the checks still bite: the tunnel then fails to start at all.

## macOS signing and notarization

`dist/sign-macos.sh` signs the bundle, notarizes it, and repacks. The release job
runs it automatically when `MACOS_CERTIFICATE_P12_BASE64` and
`APPLE_APP_SPECIFIC_PASSWORD` are set; without them the bundle is still built and
released, just unsigned, with a workflow warning. A release should not fail
because a certificate expired.

```bash
SKIP_NOTARIZE=1 dist/sign-macos.sh testingbot-tunnel-macos-arm64   # sign only, locally
dist/verify-runtime.sh dist/testingbot-tunnel-macos-arm64          # signed bundle still runs
```

Secrets, matching the names used elsewhere in the organisation:
`MACOS_CERTIFICATE_P12_BASE64`, `MACOS_CERTIFICATE_PASSWORD`,
`MACOS_KEYCHAIN_PASSWORD`, `APPLE_ID`, `APPLE_APP_SPECIFIC_PASSWORD`, and the
`MACOS_TEAM_ID` variable.

Three things about this bundle make it unlike signing a single binary:

**Every Mach-O is signed, not just the launcher.** A jlink runtime is a directory
of about twenty Mach-O files -- `bin/java`, `jspawnhelper`, and the dylibs. There
is no enclosing `.app` whose signature would cover them, so each is signed on its
own, deepest first, because signing a binary invalidates the signature of anything
containing it. The launcher itself is a shell script and is not signed.

**The JVM needs entitlements** (`dist/macos-entitlements.plist`). The hardened
runtime, which notarization requires, forbids what HotSpot does: JIT compilation,
writing unsigned executable memory, and loading the runtime's own dylibs. Without
`disable-library-validation` in particular the signed bundle refuses to start.

**Packing happens after signing.** codesign rewrites every binary, so an archive
built beforehand -- and its checksum -- describes files that no longer exist.
`build-runtime.sh` takes `SKIP_ARCHIVE=1` and `archive.sh` runs at the end
instead.

There is no `stapler staple`. A ticket can only be written into a container that
has somewhere to put one -- `.app`, `.dmg`, `.pkg`, `.kext` -- and this ships as a
tarball of plain binaries. Gatekeeper fetches the ticket from Apple on first run
instead, which works but needs the machine online once. Shipping a `.pkg`
alongside would make it offline-proof, and is the natural next step if that
matters.

## Not done: a single native binary

GraalVM `native-image` would produce one file with faster startup, closer to what
Sauce Connect ships. It is a larger piece of work than it looks: Jetty and JSch
lean on reflection and `ServiceLoader`, so it needs reachability metadata and a
tracing-agent run, and the failure mode is a path that only breaks in production.
The jlink bundle removes the JRE dependency today without that risk.
