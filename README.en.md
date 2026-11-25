# Stealth Link

[Italiano](README.md)

An independent Android companion for the **Turtle Beach Stealth Ultra**. Version `0.1.0-alpha` focuses on Social notifications, a controller simulator, experiments for the Command Display, and read-only controller settings. The app uses Kotlin, Jetpack Compose, and Material 3, with Italian and English interfaces and system, light, and dark themes.

## Alpha features

- Opt-in forwarding for any app whose notifications Android exposes to `NotificationListenerService`. Filters by channel, contact, and conversation, `MessagingStyle` extraction, deduplication, coalescing, and generic alerts while the phone is locked.
- Custom synthetic test messages, a simulator that reassembles protocol attributes, and a Lab for recording observations on the physical display.
- BLE session in a `connectedDevice` service, selection of previously paired devices, reconnection attempts, and exportable diagnostics free of personal content.
- Read-only access to the active slot, ten slot names, and system properties. Local profiles with versioned JSON backup, import, and export.

Automated tests cover the simulator, codec, rules, storage, and read-only query components. Physical testing is still required for App Link, Android background behavior, and display rendering. Personal forwarding requires protected GATT subscriptions. The legacy Lab link accepts synthetic content only. The current controller queries leave firmware and settings intact. Full configuration is planned for a later phase.

## Install and try

Use Android 7.0 or later. Download the signed APK under **Assets** on the prerelease page, then open **Device → Simulator**. In **Lab → Test message**, enter a title and body and send the test. Synthetic simulation requires no permissions. To test Android notifications, grant notification access through Android Settings. For hardware testing, pair the controller through its **App Link** screen before following the handset guide. [GitHub prereleases](https://github.com/petto10/stealth-link/releases) · [Handset and controller testing guide in Italian](docs/prove-telefono.md)

Notifications are processed on the phone. The APK manifest omits the Internet permission, and the project uses no telemetry SDK. Exported profiles and diagnostics go to the location selected by the user.

## Build and test

Use JDK 17, Android SDK Platform 36, and Android Build Tools 35.0.0. Set `ANDROID_HOME` or configure the SDK location in Android Studio. The first build downloads Gradle and Maven dependencies.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`, uses application ID `dev.stealthcompanion`, and is signed with the build machine’s debug key. It can coexist with the official app. Run a single companion session during hardware tests.

## Documentation

- [Handset and controller testing](docs/prove-telefono.md)
- [Bluetooth protocol](docs/protocollo-bluetooth.md)
- [Architecture and settings roadmap](docs/architettura.md)
- [Test results](docs/risultati-test.md)
- [Signing and releases in Italian](docs/firma-release.md)

Stealth Link is an independent project. Turtle Beach and Stealth Ultra identify the manufacturer and target device.
