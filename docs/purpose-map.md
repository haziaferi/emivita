# Purpose map

Emivita's screens by purpose: what each one is for, where SPEC specifies it, its status, and what it overlaps with. A reference for SPEC, in the format of Factotum's purpose map (screens by what they do, not by where they sit in the navigation). Drafted 2026-10-13; the purposes were settled with the owner on 2026-10-20 (§7 R102): five purposes, and Settings and the first-launch notice outside them. Rendered by tools/purpose_map.py, which also checks that every screen the code can show has a place here.

Generated from `purpose-map.json` by `tools/purpose_map.py`; edit the JSON, not this page.

## Purposes

| | Purpose | Its job | Screens |
|---|---|---|---|
| P1 | **Taking today's doses** | see what is due, be told when, and answer it: taken, skipped, snoozed, or an extra dose; and when not to be told (weekend mode) | 8 |
| P2 | **The cabinet, and me** | what I take, in what form, on which schedule, and how much is left before it runs out or expires; and what a stranger or a doctor needs to know about me, reachable at once | 7 |
| P3 | **Looking back** | the record of what was taken and missed, corrected when it is wrong, and handed to a doctor | 3 |
| P4 | **Relying on it** | reminders that ring on time and a record that survives: the grants and channels without which a reminder is late or silent, the signs when one was held back, backups, recovery, the lock | 8 |
| P5 | **Making it mine** | how the app looks and reads: palette, Today's layout, language, time format | 1 |

Screens that serve no purpose of their own (the owner, 2026-10-20): **Getting around** (navigation, not a purpose; its screen is still built for density and the fewest taps, where medTimer's is not); **Notices** (said once, at first launch).

## P1 Taking today's doses

_see what is due, be told when, and answer it: taken, skipped, snoozed, or an extra dose; and when not to be told (weekend mode)_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Today | `route:Today`, `sheet:TakeGroupSheet`, `sheet:DoseDetailSheet` | §9 #5, §9 #37, R77, R107 | shipped | Next reminders widget |
| The alarm ringing (full screen) | `activity:AlarmActivity` | R80 | shipped | Reminder notifications and their actions |
| Snooze for… (from a notification) | `activity:SnoozePickerActivity`, `dialog:SnoozePickerDialog` | §9 #25, R64 | shipped | Alarms & Snooze (sheet) |
| Amount taken (from a notification) | `activity:TakeAmountActivity`, `dialog:TakeAmountDialog` | R109 | shipped | Reminder notifications and their actions, Edit a dose (status, time, note) |
| Reminder notifications and their actions | `channel:reminders_alarm_v2`, `channel:reminders_high`, `channel:reminders_standard` | §9 #25, R64 | shipped | The alarm ringing (full screen), Notifications & alerts |
| Log a dose (as needed, an extra one, or a one-off of a medicine not on the list) | `dialog:LogDoseDialog` | §9 #2, R111 | shipped | Edit a dose (status, time, note) |
| Next reminders widget | `widget:NextRemindersWidgetReceiver` | §9 #7, R108 | shipped | Today |
| Weekend mode (sheet) | `setting:WeekendMode` | §9 #12, §9 #14, R88 | shipped | Add or edit a reminder (fixed times, interval, window, cycle, follow-up, taper) |

## P2 The cabinet, and me

_what I take, in what form, on which schedule, and how much is left before it runs out or expires; and what a stranger or a doctor needs to know about me, reachable at once_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Medicines list, its menu (pause all) and a card's swipe to delete | `route:Medicines` | §9 #1, §9 #27, R127 | shipped |  |
| A medicine's page (reminders, stock, chart) | `route:MedicineDetail` | §9 #24, §9 #27 | shipped | History (calendar and list) |
| Add or edit a medicine | `route:AddMedicine`, `route:EditMedicine`, `dialog:ColorPickerDialog`, `sheet:QuickAddMedicineSheet`, `sheet:MedicineNotificationSheet`, `sheet:MedicineDetailsSheet`, `sheet:ManageTagsSheet` | §9 #1, §9 #27, R114, R125, R127 | shipped |  |
| Add or edit a reminder (fixed times, interval, window, cycle, follow-up, taper) | `route:AddReminder`, `route:EditReminder`, `sheet:ReminderGroupSheet` | §9 #1, §9 #14, §9 #24, §9 #31, R127 | shipped | Weekend mode (sheet) |
| Stock and expiration | `route:StockExpiration` | §9 #8, §9 #27, R85, R127 | shipped | Stock levels widget |
| Stock levels widget | `widget:StockLevelsWidgetReceiver` | §9 #7, R108 | shipped | Stock and expiration |
| Medical ID | `route:MedicalIdSettings`, `setting:MedicalId` | §9 #18, R85 | shipped |  |

## P3 Looking back

_the record of what was taken and missed, corrected when it is wrong, and handed to a doctor_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Edit a dose (status, time, note) | `dialog:DoseEditDialog` | §9 #3, §9 #26 | shipped | Log a dose (as needed, an extra one, or a one-off of a medicine not on the list), History (calendar and list) |
| History (calendar and list) | `route:History` | §9 #6, §9 #26 | shipped | A medicine's page (reminders, stock, chart), Edit a dose (status, time, note) |
| Export history (CSV, PDF): range and medicines | `dialog:ExportScopeDialog` | §9 #10, §9 #26 | shipped | Data & Backup (backup, restore, auto-sync, purge) |

## P4 Relying on it

_reminders that ring on time and a record that survives: the grants and channels without which a reminder is late or silent, the signs when one was held back, backups, recovery, the lock_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Notifications & alerts | `route:NotificationsAlertsSettings`, `setting:NotificationsAlerts`, `card:NotificationsBlockedCard`, `card:BatteryOptimizationCard` | §9 #25, R83, R88 | shipped | Permission setup (first launch) and the missing-permissions card, Reminder notifications and their actions |
| Alarms & Snooze (sheet) | `setting:AlarmsSnooze` | §9 #12 | shipped | Snooze for… (from a notification) |
| Permission setup (first launch) and the missing-permissions card | `sheet:PermissionSetupSheet`, `card:MissingPermissionsCard`, `card:FullScreenAlarmAccessCard` | §9 #38, R83, R88, R102 | shipped; open: say what is missing and open Notifications & alerts, not grant in place (§7 R102) | Notifications & alerts |
| 'A reminder did not fire' card | `card:MissedAlarmCard` | §9 #29, R102 | shipped; open: say what is missing and open Notifications & alerts, not grant in place (§7 R102) | Notifications & alerts |
| Data & Backup (backup, restore, auto-sync, purge) | `route:DataBackupSettings`, `setting:DataBackup`, `dialog:PurgeConfirmDialog`, `dialog:BackupPasswordDialog` | §9 #9, §9 #29, R83 | shipped | Export history (CSV, PDF): range and medicines, 'No backup yet' card |
| 'No backup yet' card | `card:NoBackupCard` | §9 #38, R83 | shipped | Data & Backup (backup, restore, auto-sync, purge) |
| Database set aside (unreadable, or its key lost) | `dialog:DatabaseRecoveredDialog` | §9 #32, R83 | shipped | Data & Backup (backup, restore, auto-sync, purge) |
| Privacy & security (app lock) | `setting:PrivacySecurity` | §9 #11 | shipped |  |

## P5 Making it mine

_how the app looks and reads: palette, Today's layout, language, time format_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Appearance (palette, Today layout, language, time format) | `route:AppearanceSettings`, `setting:Appearance` | §9 #28, §9 #36, §9 #37 | shipped | Today |

## N1 Getting around

_navigation, not a purpose; its screen is still built for density and the fewest taps, where medTimer's is not_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Settings | `route:Settings` | §9 #12, R102 | shipped; open: density and the fewest taps (the owner, §7 R102) |  |

## N2 Notices

_said once, at first launch_

| Screen | In the code | SPEC | Status | Overlaps |
|---|---|---|---|---|
| Medical disclaimer (once, first launch) | `dialog:DisclaimerDialog` | §9 #29 | shipped | Permission setup (first launch) and the missing-permissions card |

Parts of a screen, not screens: `HeroCard`, `OptionCard`, `LayoutCard`, `PaletteCard`, `MedicineCard`, `AttentionCard`.

## Screens whose purposes overlap

| | Strength | Screens | Why |
|---|---|---|---|
| O1 | strong | Notifications & alerts, Permission setup (first launch) and the missing-permissions card, 'A reminder did not fire' card | three ways to the same grants: the Settings screen, the first-launch sheet with its Today card, and the card after a reminder was held back; the owner (2026-10-20): Notifications & alerts is the one place that grants, and the first-launch sheet and both cards say what is missing and open it |
| O2 | medium | Log a dose (as needed, an extra one, or a one-off of a medicine not on the list), Edit a dose (status, time, note) | both write a dose event; one creates it, the other corrects it |
| O3 | medium | Data & Backup (backup, restore, auto-sync, purge), Export history (CSV, PDF): range and medicines | both take data off the phone: the backup to restore it, the export to read it |

## The owner's answers

- 2026-10-13: add the purpose map as a reference for the SPEC (this draft; the purposes are the owner's to settle).
- 2026-10-20: five purposes, not seven: Medical ID joins the cabinet (P2, 'The cabinet, and me'), and making it ring joins keeping the data safe (P4, 'Relying on it'); Weekend mode belongs to taking doses (P1); grants have one home, Notifications & alerts, which the sheet and the cards open; Settings is navigation, not a purpose, but its screen is still built for density and the fewest taps, where medTimer's is not. The purpose names and the disclaimer's place as a one-time notice are proposals the owner has not yet confirmed.

_29 screens in 5 purposes; 3 with an open item._
