# Hakshev

פנקס מפקד דיגיטלי לעבודה בשטח: אפליקציית אנדרואיד (Java, XML, Room) שעובדת במצב לא מקוון.

Digital field notebook for squad / platoon commanders. Android app (Java + XML layouts, Room), offline-first.

## Package
`com.example.hakshev` · minSdk 26 · compileSdk 36

## Screens
| Screen | What it does |
|---|---|
| Dashboard | Headcount, counters overview, exceptions, quick actions, WhatsApp report |
| Counters | The commander defines what to count; one screen per counter with its soldiers |
| Roster | Filter by squad / status, status per soldier, bulk presence |
| Soldier profile | Personal, medical and exemptions (with expiry), equipment, and the soldier's counters |
| Duties | Guard shifts with a 6h rest warning and fairness count |
| Inspections | Checklist templates, exception-based inspection, history |
| Settings | Squads, CSV import, day / tactical-night theme, PIN lock, auto-wipe |

## Counters
Nothing is counted by default. The commander creates counters (with suggestions such as water,
exit hours, passed tests) and chooses for each one:
- **Period:** day, week (from Sunday), two weeks, month, or cumulative (never resets).
  Entries are kept, so the previous period is shown next to the current one.
- **Optional target** per soldier and an optional unit label.
- **Who takes part**, in two ways: open a soldier and add counters to him, or open a counter and add
  soldiers (everyone, a whole squad, or a manual pick).

Each +1 / -1 (or custom amount with a note) is stored with the commander's name and time.

## Field design
- Day theme is high contrast; night theme is pure black with dim red text.
- All action buttons are at least 56dp.
- No GPS, no background sync, no animations (battery).

## Security
- The database is encrypted at rest with SQLCipher (AES-256). The random passphrase is wrapped by an
  Android Keystore key, so the file is unreadable if copied off the device.
- Data is stored locally only (Room). Backups are disabled (`allowBackup=false`, no cloud transfer).
- PIN lock (salted SHA-256), re-lock after 30s in background, `FLAG_SECURE` blocks screenshots and recents preview.
- Optional auto-wipe after N days of inactivity, and a manual wipe button.
- Do not enter classified information (exact locations, training plans, classified equipment numbers).
  Store short descriptions only (e.g. "פטור עמידה"), never diagnoses or ID numbers.

## Sync between commanders (Firebase)
Optional. Without it the app is fully local. With it, every commander of a unit sees the same
soldiers, counters, entries and duties; changes made offline are merged when the phone is online.

**One-time setup (by one person):**
1. Create a Firebase project. Enable **Firestore** and, under Authentication, the **Anonymous** provider.
2. Add an Android app with package `com.example.hakshev` and download `google-services.json`.
3. Publish [`firestore.rules`](firestore.rules) in Firestore > Rules.
4. In the app: Settings > Sync > *Create shared unit* and pick `google-services.json`.
   The app shows a **join code**. Send it only to the other commanders.

**Other commanders:** Settings > Sync > *Join with code*, paste the code.

How it works:
- Every record is encrypted on the phone (AES-256-GCM) with a secret that exists only inside the
  join code, so Google stores unreadable data. Whoever holds the code can read and change the data.
- Changed records are pushed, then everything new on the server is pulled (on opening the app
  once a minute, and with *Sync now*).
- Conflicts: the newest change to a record wins (phone clocks should be correct). Counter entries
  are append-only, so counts from two commanders simply add up. Deletions are propagated.
- Two commanders who each created a counter with the same name get two counters; delete one.
- Not tested against a live Firebase project yet.

## Main screen, done-lists and reasons
- The app opens on the **soldier list**. Tap a soldier to add counts / punishments or mark things as done.
  Counters, duties, inspections, overview and settings are the buttons above the list.
  Soldier data (add / edit / delete) is in Settings > *Edit soldier data*.
- A personal number must be exactly 7 digits (leave it empty if unknown).
- A counter can be a **count** (a number per soldier) or a **done-list** (done / not done, e.g. a test), with optional details such as a grade.
- Each counter can ask for a **reason**: none, optional or required. The reason is saved with the entry and shown in the history.

## Not implemented yet
- Multi-commander roles (squad / sergeant / platoon commander): every commander in a unit can currently read and change everything.
- Biometric unlock.
- Excel (.xlsx) import: export to CSV UTF-8 first.

## Build
Open the folder in Android Studio, or:

```
./gradlew assembleDebug
```

## Conversion rule
A counter can convert into another one, e.g. 3 of "סש"ט" become 1 of "another punishment":
set "המרה" on the first counter. When a soldier reaches the number, that many are taken off and he
gets 1 in the target counter (created automatically if it does not exist). Deleting an entry later
does not undo a conversion.

## Web version (iPhone + Android) — `web/`
A static PWA with the same screens, counters, conversions, duties, inspections, reports and sync as the
Android app. No build step. Same Firebase unit: join with the same unit name + password (or join code)
and the Android phones and the web app see the same data.

- Deploy on Netlify: connect the GitHub repo, no build command, publish directory `web` (see `netlify.toml`).
- Run locally: `cd web && python -m http.server 8765`, then open http://localhost:8765
- Install: Android Chrome menu > "Install app"; iPhone Safari > Share > "Add to Home Screen".
- Data is kept in the browser's IndexedDB on each device; sync goes through Firestore (records are encrypted
  on the device before upload). Firebase Authentication > Anonymous must stay enabled.
