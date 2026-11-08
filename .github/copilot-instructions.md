# Repository guide

## Project shape

OwpenGram is a multi-server Android messenger based on Telegram for Android
13.1.1 and MTProto layer 232. `TMessagesProj` is the shared Android library:
most Java UI, messaging, and networking code lives here, alongside JNI/C++
code built by CMake. `TMessagesProj_App`, `TMessagesProj_AppHuawei`,
`TMessagesProj_AppHockeyApp`, and `TMessagesProj_AppStandalone` package that
shared code for different distribution channels. The standalone app is the
unrestricted, Firebase-free build used for Releases; its `afat` flavor packages
all four ABIs. `TMessagesProj_AppTests` contains Android instrumentation tests.

OwpenGram's server selection and multi-account integration lives mainly in
`org.telegram.owpengram` and the related screens in `org.telegram.ui`.
`OwpengramServers` persists server definitions and account-to-server bindings,
then passes connection settings to the native networking layer through
`ConnectionsManager`. Keep server-specific account data scoped to its server;
the shared Telegram code and protocol layer still serve all configured servers.
The `backend` directory is a separate Git submodule for the Go server, not an
Android Gradle module.

`buildSrc` supplies the Telegram build plugins and code-generation tasks,
including TL schema test generation. Treat generated TL model/test output as
generated; update its source schema or generator inputs instead.

## Build, test, and lint

Use JDK 17+, Android SDK platform/build-tools 36, NDK 27.2.12479018, and CMake
3.22.1. Initialize the Android submodules listed in `.gitmodules` before
building; CI intentionally does not initialize the separate `backend`
submodule.

From the repository root:

```sh
./gradlew :TMessagesProj_AppStandalone:assembleAfatDebug
./gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone
./gradlew :TMessagesProj_AppStandalone:lintAfatStandalone
```

The debug APK is the installable development build; `standalone` is the
minified release build. The Windows `build-android.bat` script wraps
`scripts/build.ps1` for the interactive standalone build.

Instrumentation tests require a connected Android device or emulator:

```sh
./gradlew :TMessagesProj_AppTests:connectedAfatDebugAndroidTest
./gradlew :TMessagesProj_AppTests:connectedAfatDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.telegram.tgnet.test.TlsHelloHandshakeTest
```

Replace the class value with the fully qualified test class to run another
single instrumentation class. Relevant tests are in
`TMessagesProj_AppTests/src/androidTest/kotlin`.

The Android UI automation MCP is configured in `.mcp.json` using `agent-device`;
it requires Node.js 22.12+ and ADB, with a trusted emulator or device connected.

## Repository-specific conventions

- Keep shared app behavior in `TMessagesProj`; put distribution-specific
  manifests, dependencies, and packaging changes in the corresponding app
  module. The standalone app has its own manifest and signing configuration.
- Android build variants are the combination of the `afat` ABI flavor and a
  build type (`debug` or `standalone` for the standalone app). CI builds those
  exact variants; preserve the universal APK and split-ABI version-code
  behavior when changing packaging.
- The standalone app deliberately does not apply Google Services: it must
  build without Firebase project configuration and uses the client's own
  keep-alive connection for push. Avoid adding cloud-service requirements to
  this flavor.
- Release number `OWPENGRAM_BUILD` is separate from the Telegram app version.
  `0` denotes a local build and disables the in-app release updater; published
  builds use the release number (`O<number>`). The build scripts and workflow
  pass it as `-POWPENGRAM_BUILD=<number>`.
- For server changes, follow the existing split: server records and
  persistence in `OwpengramServers`/`OwpengramServer`, selection/edit flows in
  the server UI fragments, and native connection application through
  `ConnectionsManager`. Account bindings use account slots, so removing or
  changing a server must account for every account bound to it.
