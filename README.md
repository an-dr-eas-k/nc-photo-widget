# Nextcloud Photo Widget

An Android home screen widget that shows a random picture from a folder on your Nextcloud server.

## Features

- Any number of widgets, each with its own folders, tags, interval, shape and filter (one shared Nextcloud account)
- Random photo from one or more Nextcloud folders (subfolders included)
- Tag filter: photos with any of the half-checked tags and all of the fully checked ones
- Time span filter: photos of the last k days, optionally also of the same days in every earlier year
- Widget shapes (rounded corners, square, circle, oval, pill, cut corners) and picture filters (grayscale, noir, sepia, vivid, faded, warm, cool, faded film); pictures with "snapseed" in the file name are shown unfiltered
- Widgets can be resized down to one tile
- Automatic change at a configurable interval (15 minutes to 24 hours, or only manually)
- The photo list is scanned once and then brought up to date twice a day (noon and midnight) by adding only the photos that are new on the server (every photo in the list is equally likely to be picked); the time span moves on by itself. The settings show the number of photos, last sync time and duration, and can rescan the whole list on demand
- Tap the left edge for the previous picture, the right edge for the next one, the center to open the picture in the Nextcloud app, the bottom middle (widgets at least two cells high) to show or hide the date of the picture; the recent history is kept on the device, also for offline use. A red mark shows when the last request failed
- Login via Nextcloud Login Flow v2: a revocable app password is stored, never your real password

## Requirements

- Android 8.0 (API 26) or newer
- A Nextcloud server reachable over HTTPS
- Optional: the Nextcloud Android app, to open pictures from the widget

## Usage

1. Add the **Nextcloud Photo Widget** from the home screen widget picker. The app has no launcher icon; its settings open when you add the widget.
2. Enter your server address and tap **Log in**; approve the login in the browser. The account is shared by all widgets.
3. Add one or more photo folders, optionally narrow them with tags and a time span, choose the change interval, shape and filter, then tap **Save and apply**.
4. Each widget has its own settings. To change them later, long-press the widget and tap its settings button (Android 12 or newer).

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
