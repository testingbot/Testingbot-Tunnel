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

## Not done: a single native binary

GraalVM `native-image` would produce one file with faster startup, closer to what
Sauce Connect ships. It is a larger piece of work than it looks: Jetty and JSch
lean on reflection and `ServiceLoader`, so it needs reachability metadata and a
tracing-agent run, and the failure mode is a path that only breaks in production.
The jlink bundle removes the JRE dependency today without that risk.
