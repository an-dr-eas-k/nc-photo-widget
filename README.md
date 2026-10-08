# Nextcloud Photo Widget

An Android home screen widget that shows a random picture from a folder on your Nextcloud server.

## Features

- Random photo from a chosen Nextcloud folder (subfolders included)
- Automatic change at a configurable interval (15 minutes to 24 hours)
- Tap the left edge for the previous picture, the right edge for the next one, the center to open the picture in the Nextcloud app
- Login via Nextcloud Login Flow v2: a revocable app password is stored, never your real password

## Requirements

- Android 8.0 (API 26) or newer
- A Nextcloud server reachable over HTTPS
- Optional: the Nextcloud Android app, to open pictures from the widget

## Usage

1. Install the app and open it.
2. Enter your server address and tap **Log in**; approve the login in the browser.
3. Choose the photo folder and the change interval, then tap **Save and apply**.
4. Add the **Nextcloud Photo Widget** from the home screen widget picker.

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
