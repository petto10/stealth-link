# Stealth Link

[Italiano](README.md)

An independent Android companion for the **Turtle Beach Stealth Ultra**. Version `0.3.0-alpha` uses extended text for new installations, with configurable Command Display settings and a connection recovery action. The app uses Kotlin, Jetpack Compose, and Material 3, with Italian and English interfaces and system, light, and dark themes.

## Alpha features

- Opt-in forwarding for any app whose notifications Android exposes to `NotificationListenerService`. Filters by channel, contact, and conversation, `MessagingStyle` extraction, deduplication, coalescing, and generic alerts while the phone is locked.
- Custom synthetic test messages, a simulator that reassembles protocol attributes, and a Lab for recording observations on the physical display.
- Protected Social transport. New installations default to extended text, a 110-byte UTF‑8 limit per page, 25 ms between fragments, and numbered pages on. Pagination can be turned off globally or per app. An upgrade preserves an explicit previous choice of compatibility or extended text.
- A Lab with known-length samples, separate encoding tests, configurable fragment pacing, and optional short numbered notifications. **Notifications-only connection** mode lets you test Social without opening the settings client.
- BLE session in a `connectedDevice` service, selection of previously paired devices, reconnection, manual recovery with the same pairing, and exportable diagnostics free of personal content.
- A read-only **Configuration** panel with the active slot, system properties, and device information when exposed. **Profiles** shows the ten slot names and lets you create, rename, select, import, and export local profiles.

The `0.2.1-alpha` hardware log records six completed GATT deliveries and 43 fragments, with full responses for the 128- and 255-byte ASCII samples. The photos and user report put the visible limit at around 120 characters on the tested controller. Multiple lines and scrolling were also observed in the detail view. [Physical test results and A/B procedure in Italian](docs/prove-telefono.md#confronto-ab-a-mtu-23)

## Install and try

Use Android 7.0 or later. Download the signed APK under **Assets** on the prerelease page, then open **Home → Simulator**. In **More → Lab → Test message**, enter a title and body and send the test. Synthetic simulation requires no permissions. To test Android notifications, grant notification access through Android Settings. For hardware testing, pair the controller through its **App Link** screen before following the handset guide. [GitHub prereleases](https://github.com/petto10/stealth-link/releases) · [Handset and controller testing guide in Italian](docs/prove-telefono.md)

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
- [Changelog](CHANGELOG.md)
- [Version 0.3.0-alpha release notes](docs/releases/v0.3.0-alpha.md)

Stealth Link is an independent project. Turtle Beach and Stealth Ultra identify the manufacturer and target device.
