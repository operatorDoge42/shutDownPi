# shutDownPi

A private, local-first Android app for managing several Raspberry Pi nodes over SSH.

## What it does

- Save, edit, expand, and remove multiple nodes.
- Show nodes as **Not checked** until you tap **Check SSH**; the status changes only after an authenticated SSH connection succeeds.
- Store node passwords in Android encrypted preferences, with key material protected by Android Keystore.
- Verify an unknown SSH server by showing its SHA-256 host-key fingerprint before trust; save approved keys in the app's private files. A changed key blocks access.
- Run shutdown after an adjustable 10–300 second in-app countdown (120 seconds by default), with a cancellation button and explicit acknowledgement caveats.
- Reboot over SSH and poll for the node to reconnect for up to ten minutes; show **Node back online** when SSH returns.
- Use ordinary SSH and `sudo`; no agent or service is installed on a Pi.

## Pi permissions

The SSH account must be able to run `shutdown -h now` and `reboot` through `sudo` without an interactive prompt. The app deliberately uses `sudo -n`, so it will report a permissions issue instead of trying to collect a second password. Configure the exact allowed command paths for the Pi's OS using `visudo` if needed. For Raspberry Pi OS, check the paths with `command -v shutdown` and `command -v reboot` before writing a narrow sudoers rule. Do not grant broad passwordless root access.

## Build a debug APK

Install Android command-line tools and Java 17, then accept Android SDK licenses on the build machine. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK directory and `JAVA_HOME` to a JDK 17 installation. From this folder run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy it to a test phone. Debug builds are signed with Gradle's standard debug key; do not use this key for a release.

## GitHub setup

The local Git repository is named `shutDownPi` and uses the `main` branch. The source repository is public so beta testers can open it from a link. No release workflow or public APK publishing automation is configured.

## Safety and acknowledgement behavior

The shutdown command is sent immediately. The 120-second unplug wait starts afterward and is guidance only, not proof that power is off. A successful SSH exit status is reported as sent. If SSH drops before an exit status, the app says the action may have started and starts the same cautious wait. Network reachability is not proof that the machine is safe to power off. Reboot monitoring is best effort and has a ten-minute limit.
