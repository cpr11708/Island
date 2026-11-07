# Island

A Dynamic Island for the cover screen of a Samsung Galaxy Z Fold, made to look and feel like the iPhone's.
The island sits over the camera hole and turns into whatever is happening on the phone.

## What it does

- **Music:** now playing with a live waveform*, controls, and seeking. Paused music stays a moment, then goes away.
- **Phone calls:** the caller, the call time, a waveform, and Answer, Decline, Hang up and Mute buttons.
- **Navigation:** Google Maps directions, distance and next turn.
- **Timers and stopwatch:** a live countdown or count-up, with Pause and Stop.
- **Two at once:** two activities share the island, one on each side of the camera, in the order Maps, Call, Music, Timer.
- **Short pop-ups:** charging, low battery, fully charged, silent and vibrate, Do Not Disturb, airplane mode,
  flashlight, and Bluetooth devices (with their battery level when the phone reports it).
- **Status dots:** camera, microphone, screen recording and hotspot.
- **Lock screen music player:** a glass player near the bottom of the lock screen while music plays.

## Install

1. Open the **[Releases](../../releases)** page on your phone and download `Island.apk`.
2. Open the file. If Android asks, allow your browser (or Files) to install unknown apps, then install.
3. Open **Island**. The setup screen shows what is left to do:
    - **Accessibility:** Settings, Accessibility, Installed apps, Island, turn it on. This is what lets you touch the island in every app.
    - **Notification access:** lets the island see music, calls, timers and navigation.
    - **Run in the background:** allow it, and on Samsung add Island to *Never auto sleeping apps* (Settings, Battery, Background usage limits).
    - **Optional:** call control (answer and hang up) and Bluetooth names.

### If a switch is greyed out ("Restricted setting")

Android blocks these settings for apps installed from a file. Open **Settings, Apps, Island**, tap the three dots at the
top right, choose **Allow restricted settings**, and try again. (The three dots only appear after you have tried to turn
the setting on once.)

## Privacy

Island has no internet permission, so nothing it sees (notifications, calls, music) can leave your phone.
It reads notifications only to show them in the island.

## Build it yourself

Open the project in Android Studio and run it, or use `./gradlew assembleDebug`.
The APK ends up in `app/build/outputs/apk/debug/`.

## Making a release (for the maintainer)

Push a tag and GitHub builds the APK and attaches it to a release:

```
git tag v1.0
git push origin v1.0
```

By default the APK uses the standard debug signature, which differs from build to build, so a new version means
uninstalling the old one first. To make updates install over each other, sign every release with your own key:

1. Create a key once: `keytool -genkeypair -v -keystore release.keystore -alias island -keyalg RSA -keysize 2048 -validity 10000`
2. Turn it into text: `base64 -i release.keystore` (on Linux: `base64 -w0 release.keystore`)
3. In the repository, go to Settings, Secrets and variables, Actions, and add three secrets:
   `KEYSTORE_BASE64` (the text from step 2), `KEYSTORE_PASSWORD` (the password you chose) and `KEY_ALIAS` (`island`).
4. Keep `release.keystore` somewhere safe and never commit it.




*The live waveform is not actually synchronized with audio, due to android not alloiwng any external apps to access call audio.