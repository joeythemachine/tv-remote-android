# TV Remote for Android

A Wi-Fi remote for a Samsung UN43KU6300 (2016 Tizen) TV, made for a Galaxy Tab S7.
The remote, its press animation and the app icon are all drawn in code.

## Install on the tablet

1. On the tablet, open **https://github.com/joeythemachine/tv-remote-android/releases/latest**
2. Tap **TV-Remote.apk** to download it, then open the download.
3. If Android asks, allow your browser (or My Files) to **install unknown apps**, then tap **Install**.
4. Open **TV Remote**. The first time, accept the **Allow** prompt that appears on the TV.

Every push to `main` builds a new APK (see the Actions tab). New builds install over the old one.

## Notes

- The tablet and TV must be on the same Wi-Fi.
- If the TV's IP address changes, the app finds it again automatically.
- Power-on uses Wake-on-LAN and needs **Network → Expert Settings → Power On with Mobile** turned on on the TV.
- `keystore/tvremote.jks` is a signing key used only for this personal app, so updates install cleanly.
