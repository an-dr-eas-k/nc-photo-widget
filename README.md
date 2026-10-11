# Nextcloud Photo Widget

An Android home screen widget that shows a random picture from a folder on your Nextcloud server.

## Features

- Random photo from one or more Nextcloud folders (subfolders included)
- Automatic change at a configurable interval (2 minutes to 24 hours)
- Configurable refresh of the photo list (hourly to weekly, or manually), with the number of photos, last sync time and sync duration shown in the settings
- Tap the left edge for the previous picture, the right edge for the next one, the center to open the picture in the Nextcloud app
- Login via Nextcloud Login Flow v2: a revocable app password is stored, never your real password

## Requirements

- Android 8.0 (API 26) or newer
- A Nextcloud server reachable over HTTPS
- Optional: the Nextcloud Android app, to open pictures from the widget

## Usage

1. Add the **Nextcloud Photo Widget** from the home screen widget picker. The app has no launcher icon; its settings open when you add the widget.
2. Enter your server address and tap **Log in**; approve the login in the browser.
3. Add one or more photo folders and choose the change interval, then tap **Save and apply**.
4. To change the settings later, long-press the widget and tap its settings button (Android 12 or newer).

## Building

Requirements: JDK 17+ and the Android SDK (platform 35).

```sh
# Point Gradle at your Android SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Privacy

The app talks only to the Nextcloud server you configure. Credentials (server, user name, app password) are kept in the app's private storage on the device. To revoke access, delete the app password under *Settings > Security* in Nextcloud.

## Contributing

Issues and pull requests are welcome.

## License

[MIT](LICENSE)
