# shutDownPi

A public Android app for managing several Raspberry Pi nodes over SSH.

## What it does

- Save, edit, expand, and remove multiple nodes.
- Show nodes as **Not checked** until you tap **Check SSH**; the status changes only after an authenticated SSH connection succeeds.
- Store node passwords in Android encrypted preferences, with key material protected by Android Keystore.
- Verify an unknown SSH server by showing its SHA-256 host-key fingerprint before trust; save approved keys in the app's private files. A changed key blocks access.
- Send shutdown immediately, then show an adjustable 10–300 second unplug wait (120 seconds by default). The wait says “likely safe” and never guarantees power is off.
- Reboot over SSH and poll for the node to reconnect for up to ten minutes; show **Node back online** when SSH returns.
- Use ordinary SSH and `sudo`; no agent or service is installed on a Pi.

## Pi permissions

The SSH account must be able to run `shutdown -h now` and `reboot` through `sudo` without an interactive prompt. The app deliberately uses `sudo -n`, so it will report a permissions issue instead of trying to collect a second password. Configure the exact allowed command paths for the Pi's OS using `visudo` if needed. For Raspberry Pi OS, check the paths with `command -v shutdown` and `command -v reboot` before writing a narrow sudoers rule. Do not grant broad passwordless root access.

## Build a debug APK

GitHub Actions builds the debug APK on app-source pushes to `main`. To download it, open [Actions](https://github.com/operatorDoge42/shutDownPi/actions/workflows/android-debug.yml), choose the latest successful **Build Android debug APK** run, and download the `shutDownPi-debug-apk` artifact. GitHub requires a signed-in account with repository access to download workflow artifacts; they are retained for 90 days.

For a direct public download, use the [latest beta release](https://github.com/operatorDoge42/shutDownPi/releases/latest). On Android, download the APK, open it, and confirm Android's installer prompt. Android may ask you to allow installs from the browser or file manager.

You can also build locally:

Install Android command-line tools and Java 17, then accept Android SDK licenses on the build machine. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK directory and `JAVA_HOME` to a JDK 17 installation. From this folder run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy it to a test phone. Debug builds are signed with Gradle's standard debug key; do not use this key for a release.

## GitHub setup

The local Git repository is named `shutDownPi` and uses the `main` branch. The source repository is public. GitHub Actions creates a temporary build artifact; the beta release provides the direct APK download. The APK is a debug build for testing, not a Play Store release.

## Safety and acknowledgement behavior

The shutdown command is sent immediately. The 120-second unplug wait starts afterward and is guidance only, not proof that power is off. A successful SSH exit status is reported as sent. If SSH drops before an exit status, the app says the action may have started and starts the same cautious wait. Network reachability is not proof that the machine is safe to power off. Reboot monitoring is best effort and has a ten-minute limit.
