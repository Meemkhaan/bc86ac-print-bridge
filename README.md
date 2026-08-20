# BC-86AC Print Bridge (Android app)

Replaces the Termux/Node bridge with a real always-on Android app. Install
it once, keep it running (it auto-starts and survives reboots), and it
handles **both** USB and network printing to the Black Copper BC-86AC —
no terminal, no manual `node print-bridge.js` ever again.

It exposes the exact same HTTP API on port 9876 that the Termux bridge did
(`GET /health`, `POST /print`), so **your Chrome extension and
`ticket-print-client.js` don't need any changes** — just point them at
this app's address instead of Termux's (same tablet, likely same address
you already have configured).

## Get the APK (no Android Studio needed)

This uses GitHub Actions to build the APK in the cloud — Google's Android
build tools aren't reachable from where I generated this project, but
GitHub's build servers have full access to them.

1. Go to [github.com/new](https://github.com/new), create a new **public**
   repo (e.g. `bc86ac-print-bridge`). Public is required for unlimited free
   Actions minutes; a private repo also works but has limited free minutes.
2. On the new repo's page, click **uploading an existing file** (or drag
   the whole project folder onto the page).
3. Upload every file/folder from this project, preserving the folder
   structure (`app/`, `.github/`, `build.gradle.kts`, etc.) — GitHub's web
   upload supports dragging a whole folder in Chrome.
4. Commit directly to `main`.
5. Click the **Actions** tab at the top of the repo. You should see a
   "Build APK" workflow running (starts automatically on push). Wait ~2-3
   minutes for it to finish (green checkmark).
6. Click into the finished run, scroll to **Artifacts**, download
   `bc86ac-print-bridge-debug-apk` — it's a zip containing `app-debug.apk`.

## Install on the Lenovo 10e

1. Transfer the `.apk` to the tablet (Google Drive, USB drive, email to
   yourself, whatever's easiest).
2. Open it from the Files app. ChromeOS will prompt to allow installing
   from this source the first time — allow it.
3. Install. You may see a Play Protect warning since it's not from the
   Play Store — this is expected for a sideloaded app; tap install anyway.
4. Open **BC-86AC Print Bridge**.

## First-time setup in the app

1. **Grant notification permission** if prompted (needed for the "service
   running" persistent notification).
2. Tap **Disable battery optimization for this app** — this is the
   equivalent of what `termux-wake-lock` was doing, but built in and
   permanent. Confirm in the system dialog that follows.
3. Plug in the BC-86AC via USB, tap **Pair USB printer**, accept the
   permission dialog. Status should show "Paired: ... (permission OK)".
4. Confirm **Printer IP** / **Printer port** match your setup
   (`192.168.18.100` / `9100` are pre-filled as defaults) and tap **Save**.
5. Tap **Test print (USB)** and **Test print (Network)** to confirm both
   paths work.

That's it. Leave the app installed — you don't need to keep it open in the
foreground; the persistent notification means it's running as a background
service. It restarts automatically if the tablet reboots.

## Updating your extension / web app

Wherever you had the Bridge URL pointing at the Termux address, it's the
same address now (same tablet, same port 9876) — nothing to change unless
your setup used a different port. The API contract is identical:

- `GET /health` → `{"ok":true,"name":"bc86ac-bridge-app","usbPaired":true}`
- `POST /print` with `X-Printer-Host` / `X-Printer-Port` headers → relays to
  the network printer (unchanged behavior)
- `POST /print` with `X-Transport: usb` header (or no `X-Printer-Host` at
  all) → prints directly over USB using this app's own USB Host API
  connection, no browser/WebUSB involved

## Notes

- The debug APK is signed with Android's default debug key (not a
  Play-Store release key) — completely fine for sideloading on your own
  device, just not something you'd publish to the Play Store as-is.
- If you ever want to rebuild after making a change, just push the change
  to the GitHub repo (or re-upload the changed file) — Actions rebuilds
  automatically.
