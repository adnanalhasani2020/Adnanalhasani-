# Hospital Android app — local SQLite mode

## What changed

The Android app bundles the hospital Python API and Arabic web interface. On launch it starts the API on the device loopback address and stores the SQLite database at the app-private path `files/hospital.sqlite3`. No separate computer, hosting account, or network server is required for the bundled Android app.

The app creates a random API token and keeps it in private app preferences; the dashboard uses it to authenticate requests to the local API. The API listens only on `127.0.0.1`, not on the Wi-Fi network.

## Build

The GitHub Actions workflow `.github/workflows/hospital-android-apk.yml` builds a debug APK and uploads it as the `hospital-android-debug-apk` artifact when changes are pushed to the configured development branch. Debug artifacts are retained for 14 days.

## Data and limitations

- Data is stored locally on this Android device. It is not automatically synced to another phone or computer.
- Uninstalling the app or clearing its storage deletes its local database. Implement and test an encrypted export/backup-and-restore process before storing important records.
- This is a development foundation, not a certified clinical system. Do not enter real patient data. Production use requires security and clinical review, proper user roles, backups, recovery testing, and regulatory assessment.
- The dashboard loads icon/font assets from public CDNs when internet access is available; the local API and SQLite database themselves do not require internet access.
