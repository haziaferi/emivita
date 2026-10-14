# Purpose map

Emivita's screens by purpose: what each one is for, where SPEC specifies it, its status, and what it overlaps with. A reference for SPEC, in the format of Factotum's purpose map (screens by what they do, not by where they sit in the navigation). A DRAFT of 2026-10-13: the screens, their jobs and their SPEC references describe the shipped app; the grouping into purposes is a proposal the owner settles. Rendered by tools/purpose_map.py, which also checks that every screen the code can show has a place here.

Generated from `purpose-map.json` by `tools/purpose_map.py`; edit the JSON, not this page.

## Purposes

| | Purpose | Its job | Screens |
|---|---|---|---|
| P1 | **Taking today's doses** | see what is due, be told when, and answer it: taken, skipped, snoozed, or an extra dose | 6 |
| P2 | **The medicine cabinet** | what I take, in what form, on which schedule, and how much is left before it runs out or expires | 6 |
| P3 | **Looking back** | the record of what was taken and missed, corrected when it is wrong, and handed to a doctor | 3 |
| P4 | **Making sure it rings** | the grants, channels and settings without which a reminder is late or silent, and the signs when one was held back | 5 |
| P5 | **In an emergency** | what a stranger or a doctor needs to know about me, reachable at once | 1 |
| P6 | **Keeping the data safe** | the record survives a lost phone, a broken database, or curious hands: backups, recovery, the lock | 5 |
| P7 | **Making it mine** | how the app looks and reads: palette, Today's layout, language, time format | 2 |

## P1 Taking today's doses

_see what is due, be told when, and answer it: taken, skipped, snoozed, or an extra dose_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Today | `route:Today` | §9 #5, §9 #37, R77 | shipped | Next reminders widget |
| The alarm ringing (full screen) | `activity:AlarmActivity` | R80 | shipped | Reminder notifications and their actions |
| Snooze for… (from a notification) | `activity:SnoozePickerActivity` | §9 #25, R64 | shipped | Alarms & Snooze (sheet) |
| Reminder notifications and their actions | `channel:reminders_alarm_v2`, `channel:reminders_high`, `channel:reminders_standard` | §9 #25, R64 | shipped | The alarm ringing (full screen), Notifications & alerts |
| Log a dose (as needed, or an extra one) | `dialog:LogDoseDialog` | §9 #2 | shipped | Edit a dose (status, time, note) |
| Next reminders widget | `widget:NextRemindersWidgetReceiver` | §9 #7 | shipped | Today |

## P2 The medicine cabinet

_what I take, in what form, on which schedule, and how much is left before it runs out or expires_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Medicines list | `route:Medicines` | §9 #1, §9 #27 | shipped |  |
| A medicine's page (reminders, stock, chart) | `route:MedicineDetail` | §9 #24, §9 #27 | shipped | History (calendar and list) |
| Add or edit a medicine | `route:AddMedicine`, `route:EditMedicine`, `dialog:ColorPickerDialog` | §9 #1, §9 #27 | shipped |  |
| Add or edit a reminder (fixed times, interval, window, cycle, follow-up, taper) | `route:AddReminder`, `route:EditReminder` | §9 #1, §9 #14, §9 #24, §9 #31 | shipped | Weekend mode (sheet) |
| Stock and expiration | `route:StockExpiration` | §9 #8, §9 #27, R85 | shipped | Stock levels widget |
| Stock levels widget | `widget:StockLevelsWidgetReceiver` | §9 #7 | shipped | Stock and expiration |

## P3 Looking back

_the record of what was taken and missed, corrected when it is wrong, and handed to a doctor_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Edit a dose (status, time, note) | `dialog:DoseEditDialog` | §9 #3, §9 #26 | shipped | Log a dose (as needed, or an extra one), History (calendar and list) |
| History (calendar and list) | `route:History` | §9 #6, §9 #26 | shipped | A medicine's page (reminders, stock, chart), Edit a dose (status, time, note) |
| Export history (CSV, PDF): range and medicines | `dialog:ExportScopeDialog` | §9 #10, §9 #26 | shipped | Data & Backup (backup, restore, auto-sync, purge) |

## P4 Making sure it rings

_the grants, channels and settings without which a reminder is late or silent, and the signs when one was held back_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Notifications & alerts | `route:NotificationsAlertsSettings`, `setting:NotificationsAlerts`, `card:NotificationsBlockedCard`, `card:BatteryOptimizationCard` | §9 #25, R83, R88 | shipped | Permission setup (first launch) and the missing-permissions card, Reminder notifications and their actions |
| Alarms & Snooze (sheet) | `setting:AlarmsSnooze` | §9 #12 | shipped | Snooze for… (from a notification) |
| Weekend mode (sheet) | `setting:WeekendMode` | §9 #12, §9 #14, R88 | shipped | Add or edit a reminder (fixed times, interval, window, cycle, follow-up, taper) |
| Permission setup (first launch) and the missing-permissions card | `sheet:PermissionSetupSheet`, `card:MissingPermissionsCard`, `card:FullScreenAlarmAccessCard` | §9 #38, R83, R88 | shipped | Notifications & alerts |
| 'A reminder did not fire' card | `card:MissedAlarmCard` | §9 #29 | shipped | Notifications & alerts |

## P5 In an emergency

_what a stranger or a doctor needs to know about me, reachable at once_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Medical ID | `route:MedicalIdSettings`, `setting:MedicalId` | §9 #18, R85 | shipped |  |

## P6 Keeping the data safe

_the record survives a lost phone, a broken database, or curious hands: backups, recovery, the lock_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Data & Backup (backup, restore, auto-sync, purge) | `route:DataBackupSettings`, `setting:DataBackup`, `dialog:PurgeConfirmDialog` | §9 #9, §9 #29, R83 | shipped | Export history (CSV, PDF): range and medicines, 'No backup yet' card |
| 'No backup yet' card | `card:NoBackupCard` | §9 #38, R83 | shipped | Data & Backup (backup, restore, auto-sync, purge) |
| Database set aside (unreadable, or its key lost) | `dialog:DatabaseRecoveredDialog` | §9 #32, R83 | shipped | Data & Backup (backup, restore, auto-sync, purge) |
| Privacy & security (app lock) | `setting:PrivacySecurity` | §9 #11 | shipped |  |
| Medical disclaimer (once, first launch) | `dialog:DisclaimerDialog` | §9 #29 | shipped | Permission setup (first launch) and the missing-permissions card |

## P7 Making it mine

_how the app looks and reads: palette, Today's layout, language, time format_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Settings | `route:Settings` | §9 #12 | shipped |  |
| Appearance (palette, Today layout, language, time format) | `route:AppearanceSettings`, `setting:Appearance` | §9 #28, §9 #36, §9 #37 | shipped | Today |

Parts of a screen, not screens: `HeroCard`, `OptionCard`, `LayoutCard`, `PaletteCard`, `MedicineCard`, `AttentionCard`.

## Screens whose purposes overlap

| | Strength | Screens | Why |
|---|---|---|---|
| O1 | strong | Notifications & alerts, Permission setup (first launch) and the missing-permissions card, 'A reminder did not fire' card | three ways to the same grants: the Settings screen, the first-launch sheet with its Today card, and the card after a reminder was held back |
| O2 | medium | Log a dose (as needed, or an extra one), Edit a dose (status, time, note) | both write a dose event; one creates it, the other corrects it |
| O3 | medium | Data & Backup (backup, restore, auto-sync, purge), Export history (CSV, PDF): range and medicines | both take data off the phone: the backup to restore it, the export to read it |

## The owner's answers

- 2026-10-13: add the purpose map as a reference for the SPEC (this draft; the purposes are the owner's to settle).

_28 screens in 7 purposes; 0 with an open item._
