# WitnessCam

An Android app (Android 10+) for recording police stops. It records video and audio, uses the front
and rear cameras together when the phone supports it, burns the date, time and GPS position into
every frame, and uploads the footage to **your own Google Drive or Dropbox** in short chunks while it records.
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
  is uploaded as soon as it is finished, to `My Drive/WitnessCam/<date_time>/` or
  `Dropbox/Apps/<your app name>/<date_time>/`.
- **Tamper evidence**: each chunk's SHA-256 is computed on the phone, stored in the Drive file's
  description and properties, and checked against Drive's own checksum before the local copy is deleted.
- **Background recording**: keeps recording with the screen off or another app open (a
  notification with a Stop button stays visible while recording).
- **Survives no signal**: chunks queue on the phone and upload automatically when a connection
  returns. WorkManager retries with backoff.
- **Start/stop widget**: a home-screen button that shows **● RECORD** or **■ STOP**. There's also a
  Quick Settings tile (works from the lock screen) and the big red button in the app.
- **PIN-protected stop**: with a PIN set, every way of stopping a recording (the app, the widget, the
  tile, the notification) asks for it, including from the lock screen. Settings are PIN-protected too,
  so the PIN can't simply be removed. Wrong guesses lock the screen for increasing periods (30 s, 60 s, ...).
- **Text alerts** to your emergency contacts:
  - when recording starts: your message, the time and a Google Maps link to your location;
  - optional **live footage link**: the recording's Drive folder is shared by link and texted, so
    contacts can watch clips as they upload;
  - optional location updates every N minutes while recording;
  - when recording stops (on by default), including how long it ran;
  - a **Send test text** button in Settings.
- **Least-privilege Drive access**: the app uses the `drive.file` scope, so it can only see files it
  created, not the rest of your Drive.

## Install

On your phone, open **<https://github.com/oooshk/witness-cam/releases/latest>** (sign in to GitHub,
since the repo is private) and tap **WitnessCam.apk** under *Assets*. Open the downloaded file to
install it. Android will ask you to allow installing from your browser the first time. Every change
pushed to `main` is built automatically and published as a new release.

To build it yourself: `./gradlew assembleDebug` (needs the Android SDK, JDK 17+).

## Choosing a cloud

Pick **Google Drive** or **Dropbox** in Settings → Upload to. Dropbox is quicker to set up
(about 2 minutes, no fingerprints or test users). Both verify every upload against the service's own
checksum before deleting the copy on the phone.

## One-time Dropbox setup (about 2 minutes)

1. Go to <https://www.dropbox.com/developers/apps> and tap **Create app**.
   - API: **Scoped access**
   - Access: **App folder**. The app can only touch its own folder, `Dropbox/Apps/<name>/`.
   - Name: anything unique, e.g. `WitnessCam-yourname`
2. On the app's **Permissions** tab, tick **files.content.write**, **sharing.write** and
   **sharing.read**, then press **Submit** at the bottom. Do this *before* connecting, because
   permissions added later need a reconnect.
3. On the **Settings** tab, copy the **App key**. You don't need the App secret.
4. In WitnessCam: Settings → Upload to: **Dropbox** → **Set up Dropbox**. Paste the key, tap
   **Open Dropbox**, sign in and tap **Allow**, then copy the code Dropbox shows. Go back to the app,
   tap **Paste** and then **Connect**.

Each video chunk gets a small `.json` file next to it holding its SHA-256, start time and GPS position.
The app stays in Dropbox's "development" status, which is fine for personal use.

## One-time Google Drive setup (about 10 minutes)

Google requires every app that uses Drive to be registered. Because this is your personal app, you
register it under your own Google account:

1. Go to <https://console.cloud.google.com/> and create a project (e.g. "WitnessCam").
2. **APIs & Services → Library**: search for **Google Drive API** and click **Enable**.
3. **APIs & Services → OAuth consent screen** (or **Google Auth Platform**): choose **External**, fill
   in the app name and your email. Under **Audience / Test users**, add your own Gmail address.
   You can leave the app in "Testing" mode for personal use.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.oooshk.witnesscam`
   - SHA-1 certificate fingerprint: `4B:B7:10:AC:BB:C9:B5:6D:9D:0F:F1:D0:B9:8A:21:47:E5:CC:AF:1B`
5. Install the app, tap **Connect Google Drive** and approve access.

The SHA-1 belongs to `app/debug.keystore`, which is committed so local and CI builds are signed the
same way. If you fork this repo or publish the app, generate your own keystore and register its SHA-1 instead.

> In "Testing" mode Google may ask you to re-approve access every 7 days. If uploads stop, open the
> app: it will show **Connect Google Drive** again, and you'll get a notification. Publishing the
> consent screen (no verification needed for personal use with the `drive.file` scope) removes this limit.

## Tips for using it

- Test it before you need it: record for a minute and check the files appear in Drive.
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
  uploaded to Drive is out of reach. Keep your phone's screen lock enabled, and keep the chunk length short.
- The live footage link makes that recording's folder viewable by **anyone who has the link**. It's off
  by default. You can unshare a folder at any time (Drive: Share → General access → Restricted;
  Dropbox: Share → Settings → Delete link).
- Text alerts need a SIM with SMS service and the SMS permission. Standard message rates apply.

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

## Legal note

This is not legal advice. In the US, several federal appeals courts have recognized a First Amendment
right to record police performing their duties in public, but laws differ by state (for example audio
consent laws and "buffer zone" laws), and recording is never a reason to interfere with an officer's
lawful orders. Check the laws where you live and drive.

## Project layout

| File | Purpose |
|---|---|
| `RecordingService.kt` | Foreground service: camera binding (dual/single), chunked recording |
| `ReviewActivity.kt` / `PlayerActivity.kt` | Recordings list and clip playback (Media3) |
| `TimestampOverlay.kt` | CameraX `OverlayEffect` that draws the time/GPS stamp onto frames |
| `ChunkStore.kt` | On-device queue of finished chunks waiting to upload |
| `UploadWorker.kt` | WorkManager job that uploads and verifies chunks |
| `Cloud.kt` | Cloud provider interface, provider selection, Google Drive uploader |
| `DriveClient.kt` / `DriveAuth.kt` | Google Drive REST v3 client and Google Identity authorization |
| `DropboxUploader.kt` / `DropboxAuth.kt` | Dropbox API v2 uploads/sharing and PKCE sign-in |
| `SmsAlerter.kt` | Emergency-contact text message |
| `RecordTileService.kt` / `RecordWidgetProvider.kt` | Quick Settings tile and home-screen widget |
