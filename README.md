# Clip Sync

# ❌ ❌ ❌ WARNING ❌ ❌ ❌ - I created this app for my own usage. Security, proper build etc was not a consideration for this application. Use with CAUTION ⚠️ ⚠️.

I've had many instances when I wanted to copy a bunch of text from my laptop to my phone and vice versa. I always used WhatsApp, Teams, Discord to do this. This application will listen on a multicast address and send the data from clipboard to the same multicast address. The listeners will get the data and paste it to their clipboard.

## Prerequisites

- Mac Os
- Rust >= 1.78.0 (Lower versions should work as well)
- Android Studio Fully setup
- Android Phone with API Level >= 31 (Mine is an S10+ I stole from my work 😁)

## Usage

- Build the `clipsyncmac` project by running `cargo build --release` inside `./clipsyncmac` directory. You can add it to path like so:

```
export CLIPSYNC="/Users/<youruser>/.../clipsync/clipsyncmac/target/release"
export PATH="$CLIPSYNC:$PATH"
```

If you have added it to the path, you can just run the cli using `clipsyncmac` anywhere from the terminal.

- Open `./ClipSyncAndroid` project in android studio, connect your device and click on run (the play button on top). This will install the app on your phone. Click `Start Service`. If you experience any issues, just kill the app, clear storage, open the app again and click `Start Service`.

- Run `clipsyncmac` binary

- Copy text from your Mac OS Laptop, It should be ready to paste in the android.
- Copy text from your Android and click on the clipsync notification in the notification drawer, the text copied should be available to paste in your Mac OS Laptop. (Android cannot listen to clipboard if the app is not in foreground).
