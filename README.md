# WitnessCam

An Android app (Android 10+) for recording police stops. It records video and audio, uses the front
and rear cameras together when the phone supports it, burns the date, time and GPS position into
every frame, and uploads the footage to **your own Dropbox** in short chunks while it records.
If the phone is taken or broken, everything up to the last few seconds is already in the cloud.

## Features

- **Dual camera**: on phones that support running two cameras at once (many Android 11+ devices), the
  rear camera (with audio) and the front camera each record their own full-frame video. The files are
  `seg_0001_rear.mp4` and `seg_0001_front.mp4`. Each is stamped REAR or FRONT. Other phones, or the
  Settings switch, use the rear camera only.
- **Recordings screen**: lists every session with its length, cameras and upload status. Play a session
  back clip after clip, switch between the rear and front views, open it in the cloud, or delete it
  from the phone. PIN-protected when a PIN is set.
- **Burned-in stamp**: date, time, time zone, GPS coordinates and accuracy drawn onto the video
  itself, so the stamp can't be stripped from the file.
- **Upload while recording**: video is cut into chunks (10 s by default, adjustable) and each chunk
  is uploaded as soon as it is finished, to `Dropbox/Apps/WitnessCam/<date_time>/`.
- **Tamper evidence**: each chunk's SHA-256 is computed on the phone and stored in a `.json` file next to
  it, and every upload is checked against Dropbox's own checksum before the copy on the phone can be deleted.
- **Background recording**: keeps recording with the screen off or another app open (a
  notification with a Stop button stays visible while recording).
- **Survives no signal**: chunks queue on the phone and upload automatically when a connection
  returns. WorkManager retries with backoff.
- **Start/stop widget**: a home-screen button that shows **● RECORD** or **■ STOP**. To prevent
  accidental presses it needs two taps: the first turns it amber ("TAP AGAIN") for 3 seconds and the
  second acts. This can be turned off in Settings. There's also a
  Quick Settings tile (works from the lock screen) and the big red button in the app.
- **PIN-protected stop**: with a PIN set, every way of stopping a recording (the app, the widget, the
  tile, the notification) asks for it, including from the lock screen. Settings are PIN-protected too,
  so the PIN can't simply be removed. Wrong guesses lock the screen for increasing periods (30 s, 60 s, ...).
  Optionally, fingerprint or face can be used instead of typing the PIN (off by default: a PIN can't
  be taken from you the way a finger or face can).
- **Text alerts** to emergency contacts chosen from your phone's contacts (or typed in):
  - when recording starts: your message, the time and a Google Maps link to your location;
  - optional **live footage link**: the recording's Dropbox folder is shared by link and texted, so
    contacts can watch clips as they upload;
  - optional location updates every N minutes while recording;
  - when recording stops (on by default), including how long it ran;
  - a **Send test text** button in Settings.
- **Least-privilege Dropbox access**: the app can only see its own folder (`Apps/WitnessCam`),
  not the rest of your Dropbox.

## Install

1. On your Android phone, open **<https://github.com/oooshk/witness-cam/releases/latest>** and tap
   **WitnessCam.apk** under *Assets*.
2. Open the downloaded file and tap **Install**. The first time, Android asks you to allow installs
   from your browser. If Play Protect warns that the app is unknown, tap **More details → Install
   anyway**. That warning appears for any app that isn't from the Play Store.
3. Open WitnessCam and allow camera, microphone, location and notifications.

**Only download WitnessCam from the GitHub releases page above.** Each release lists the APK's
SHA-256 and the signing certificate's SHA-256, so you can check that a copy is genuine. Updates
install over the old version only if they're signed with the same key.

## Set up

1. Open WitnessCam and tap **Sign in with Dropbox**. Approve access in the Dropbox app or browser,
   and you're taken straight back. Recordings go to `Dropbox/Apps/WitnessCam/`.
2. Settings → **Text alerts**: add emergency contacts and tap **Allow SMS**. On Android 13+ Android
   blocks SMS for apps installed from a file until you allow it. The app walks you through it.
3. Settings → **Stop protection**: set a PIN.
4. Settings → **Allow background uploads**, so Android's battery saver doesn't pause uploads.
5. Add the home-screen widget or the Quick Settings tile, then make a test recording and check that it
   appears in Dropbox.

## Tips for using it

- Test it before you need it: record for a minute and check the files appear in Dropbox.
- Add the Quick Settings tile (swipe down → edit → drag in **WitnessCam**) and/or the home-screen widget.
- A dashboard phone mount lets you keep your hands visible and still capture the interaction.
- Keep the chunk length short (5–10 s) if you have good coverage. Use longer chunks on weak connections.
- Uploading uses about 25 MB per minute of recording (3 Mbps video plus audio). Turn on **Upload on Wi-Fi only** if
  mobile data is limited. Footage then stays on the phone until you're on Wi-Fi, which defeats the
  "phone gets taken" protection.

## Known limitations

- **The PIN can't stop everything.** Android always lets the phone's owner (or anyone holding an
  unlocked phone) force-stop an app from system Settings, the Android 13+ "active apps" panel, or by
  powering off the phone. The PIN stops casual shutdowns. The real protection is that footage already
  uploaded to Dropbox is out of reach. Keep your phone's screen lock enabled, and keep the chunk length short.
- The live footage link makes that recording's folder viewable by **anyone who has the link**. It's off
  by default. You can unshare a folder at any time in Dropbox (Share → Settings → Delete link).
- Text alerts need a SIM with SMS service and the SMS permission. Standard message rates apply.
  On Android 13 and newer, apps installed from a downloaded file have SMS permission blocked as a
  "restricted setting". Settings → Text alerts → **Allow SMS** walks you through unblocking it (App
  info → ⋮ → Allow restricted settings → Permissions → SMS → Allow).

- Android only lets a camera/microphone recording start while the app is on screen, so the widget
  and tile open the app to start recording. After that you can turn the screen off.
- There is a brief gap (typically well under a second) between chunks while one file is finalized
  and the next one starts.
- Running front and rear cameras together depends on the phone's hardware. Phones that can't do it
  record from the rear camera only. The status bar in the app shows which mode is active. Some phones
  lower the resolution when both cameras run at once.
- Clips stay on the phone after upload so they can be reviewed (about 35 MB per minute with both
  cameras). Delete old sessions from the Recordings screen, or turn on "Delete clips from the phone
  after a verified upload" in Settings.
- If the phone is powered off or force-stopped mid-chunk, that last chunk may be lost.

## Disclaimer

WitnessCam is provided as is, with no warranty (see LICENSE). It can fail: phones run out of battery
or storage, networks drop, phones get seized or destroyed, and uploads can be delayed. Don't rely on
it as your only protection. Test it on your own phone before you need it.

## Legal note

This is not legal advice. In the US, several federal appeals courts have recognized a First Amendment
right to record police performing their duties in public, but laws differ by state (for example audio
consent laws and "buffer zone" laws), and recording is never a reason to interfere with an officer's
lawful orders. Check the laws where you live and drive.

## For the maintainer: releases

- Releases are signed with a private key stored encrypted at `app/signing/release.jks.enc`. CI
  decrypts it using the **`RELEASE_KEY_PASSPHRASE`** repository secret (Settings → Secrets and
  variables → Actions → Secrets). Without that secret, CI builds but publishes nothing. **Keep the
  passphrase safe**: if it's lost, no further updates can be installed over existing copies.
- **Dropbox app**: the key in `gradle.properties` (`dropboxAppKey`, or a `DROPBOX_APP_KEY` repository
  variable) is built into the app. It's a public identifier: sign-in uses PKCE, and there's no secret.
  In the [Dropbox App Console](https://www.dropbox.com/developers/apps) the app must use **Scoped
  access**, **App folder**, and have **Allow public clients (PKCE)** enabled. On its Permissions tab
  tick `account_info.read`, `files.metadata.read`, `files.content.write`, `sharing.read` and
  `sharing.write`. In development status up to 500 Dropbox accounts can connect. Apply for production
  status in the App Console before that.
- `app/debug.keystore` is only for local development builds. Anyone can see it, so never publish
  builds signed with it.

## Project layout

| File | Purpose |
|---|---|
| `RecordingService.kt` | Foreground service: camera binding (dual/single), chunked recording |
| `ReviewActivity.kt` / `PlayerActivity.kt` | Recordings list and clip playback (Media3) |
| `TimestampOverlay.kt` | CameraX `OverlayEffect` that draws the time/GPS stamp onto frames |
| `ChunkStore.kt` | On-device queue of finished chunks waiting to upload |
| `UploadWorker.kt` | WorkManager job that uploads and verifies chunks |
| `Cloud.kt` | Upload interface |
| `DropboxUploader.kt` / `DropboxAuth.kt` | Dropbox API v2 uploads/sharing; sign-in via the Dropbox SDK (PKCE) |
| `SmsAlerter.kt` | Emergency-contact text message |
| `RecordTileService.kt` / `RecordWidgetProvider.kt` | Quick Settings tile and home-screen widget |
