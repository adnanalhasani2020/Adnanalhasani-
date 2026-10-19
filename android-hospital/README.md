# Hospital Android — native XML starter

Native Android starter for the hospital system. It uses XML layouts, Kotlin, Material Components and Room over SQLite.

## Implemented
- Arabic RTL dashboard and patient registration/list screen.
- XML layouts for dashboard, patient form and patient list rows.
- UI → repository → DAO → Room/SQLite data flow.
- Persistent local database file named `hospital-local.db`.
- AndroidX, Material Components, Kotlin coroutines and Room dependencies.

## Build
Open this directory (`android-hospital`) in Android Studio with JDK 17 and Android SDK 35 installed. Allow Gradle sync, then run on an emulator/device. An APK has **not yet been built or tested in this session**.

## Data boundary
This starter stores demo records locally on each device. It is **not yet connected** to the existing Python hospital API or server-side SQLite database. Do not use it with real patient data. Shared multi-device records require authenticated API integration; do not expose a SQLite file directly over the network.

## Next
1. Connect a secure API client to the existing hospital backend.
2. Add doctors, appointments, encounters, laboratory, pharmacy, admissions, billing and settings screens.
3. Add Room migrations, automated tests and verify persistence across process restart.
4. Configure production authentication, permissions, TLS, backup policy and clinical safeguards before real use.
