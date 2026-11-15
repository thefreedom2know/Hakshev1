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

## Not implemented yet
- Cloud sync and multi-commander roles (squad / sergeant / platoon commander) with merge-based conflict resolution.
- Biometric unlock.
- Excel (.xlsx) import: export to CSV UTF-8 first.

## Build
Open the folder in Android Studio, or:

```
./gradlew assembleDebug
```
