# Native camera tunnel runtime

The Android library is built from this pinned Go module with Go 1.26.10 and the
NDK r30 beta2 toolchain (API 26). `build.ps1` verifies module checksums, checks
the upstream Tailscale `tsnet.go` digest before applying the no-file-logger
patch, and defaults to emitting only `arm64-v8a` under
`app/build/generated/tunnelJniLibs` for Gradle to package. Generated binaries
are not source-controlled. The application's ABI filter also restricts native
dependencies and CMake outputs to ARM64. The build script pins the source digests for both
Tailscale patches: disabling its file logger and routing TLS/ACME certificate
state through the supplied StateStore for non-file stores. This keeps Serve and
Funnel private keys in the Android Keystore-encrypted store rather than under
the runtime directory.

Tailscale state and Zrok credentials/identity material are stored through the
JNI bridge into the Android Keystore-backed encrypted store. Zrok's SDK reads
its identity from a process-local anonymous memory file; it does not use the
SDK's ordinary on-disk environment root. Provider logging is directed to a
discard sink.

After changing the pinned dependency graph, regenerate the packaged license
inventory with `generate-notices.ps1`. The resulting Android asset contains
the license and notice files for the native runtime's Android dependency
closure. Source, pinned module versions/checksums, and the reproducible build
scripts remain in this repository; the packaged license inventory is at
`app/src/main/assets/third-party/tunnel/LICENSES.txt`.

The Gradle task supports Windows and Linux hosts. Windows uses `powershell.exe`;
Linux requires PowerShell 7 (`pwsh`) and Go 1.26.10 on `PATH`, plus the Linux NDK
r30 beta2 toolchain. Gradle keeps the native build cache under
`app/build/tunnel-native` on both hosts. On Linux, build the distribution APK with
`sh ./gradlew :app:assemblePerformance` using JDK 17 and the configured Android SDK.

For an arm64-only APK, append `-PtargetAbis=arm64-v8a`. This selects the ABI for
both native builds and APK packaging; the result is copied to
`build_output/byd-extend-v<version>-arm64-v8a.apk`. Without this property, the
build uses the upstream arm64-only default and APK filename.
