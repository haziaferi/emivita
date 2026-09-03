# Emivita — Project Specification

This is the source of truth. Anything decided in conversation and not reflected
here didn't happen — fold it in before it can affect the code. Anything here
that conflicts with a stray Kotlin comment or an old chat message loses to
this file until this file is edited.

Status key used throughout: 🔒 locked (don't revisit without a real reason) ·
✅ built · 🚧 designed but not built · ❓ open, needs a decision.

### Where everything went 🔒

This file is the only place Emivita decisions live. It did not use to be:
the reasoning was spread across five documents, two of them outside the
project directory and one of them already deleted while still being cited
by name. Every one of them is now folded in here.

| Was | Held | Now |
|---|---|---|
| `docs/ux-audit.md` | The reference-app UX audit §6's decisions came from | **Appendix B**, verbatim. File deleted — its history is in git |
| `emivita-frontend-theme-review.md` | The colour/type/layout analysis §7's T1–T6 came from | **Appendix C**. Lived outside the repo, uncommitted, cited 7× by name |
| `emivita-palette-candidates.md` | The 8 palettes with tokens and contrast checks | **Appendix A**, absorbed before this pass — verified equivalent entry by entry, nothing outstanding |
| `Emivita-audit.md` | The original audit findings | **§7's ledger**, absorbed long ago. The file no longer exists anywhere |
| `emivita_spec.md`, `emivita_spec (1).md` | Earlier copies of this same file | Retired. Verified strict ancestors — every line unique to them is superseded wording, or a roadmap item still marked 🚧 that is now ✅ |

All four originals lived in `Downloads/Builds/`, outside any repository, and
have been deleted from there. They are not lost: commit `467b760` holds them
under `docs/retired/`, and the commit immediately after removes that folder
again — so they stay recoverable from history without a second copy sitting
in the tree to drift from this one.

    git show 467b760:docs/retired/emivita-frontend-theme-review.md

Before deleting, each was checked rather than assumed superseded: the theme
review differs from Appendix C only in heading depth, the palette candidates
contribute zero hex values and zero contrast ratios this file does not
already carry, and the two old spec copies differ only in wording and in
roadmap items whose status has since advanced.

Two files cited in these pages are **not** Emivita documents and are not
absorbed: medTimer's own `docs/UseCases.md` (the upstream reference app's
file, §3/§5) and `visual-craft`'s `design-lexicon.md` (a skill's file, §6).
Both are external inputs this spec was reasoned against, not rival records
of what Emivita is.

`README.md` is not a source of truth either — it is setup and orientation,
and it loses to this file wherever the two disagree. Same for
`dist/vc1/MANIFEST.md`, which describes one archived binary, not the design.

**The rule this pass establishes:** a new finding, audit, or review does not
get its own file. It lands in §7's ledger with its reasoning in an appendix
if the reasoning is worth keeping. Two files describing one project's state
is how §8 came to claim three built screens were placeholders (§7 R19), and
how three of the five documents above ended up cited but unreadable.

---

## 1. Purpose & constraints 🔒

Personal medication reminder app. Single user, single device, not
distributed. A structural revisitation of
[Futsch1/medTimer](https://github.com/Futsch1/medTimer) — its scheduling/
notification/backup *logic* is a reference to port from (MIT-licensed, clean
to adapt), its UI is not (Fragments/XML/Navigation-Component, not Compose;
see §5).

Non-negotiable, checked against every batch before it ships:

| Constraint | Why it's non-negotiable |
|---|---|
| SAF (Storage Access Framework) for all file I/O | No legacy file paths, no scoped-storage workarounds |
| Local-first | Zero `INTERNET` permission, zero HTTP client of any kind |
| De-googled | No Firebase, no GMS/Play Services, no `google-services` plugin |
| minSdk 30 (Android 11+) | Floor for the target device |
| targetSdk 36+ (Android 16+) | Ceiling tracks current, not legacy behavior |
| Unrooted device | No root assumptions anywhere (Shizuku, su, etc. are out of scope) |
| Personal use only, no Google Play | No signing/release config, no Play-specific APIs |
| Windows 11 / Android Studio / JDK 21 | JDK 21 runs Gradle; `sourceCompatibility`/`jvmTarget` stay at 17 (deliberate, not a mismatch) |
| Kotlin, not TypeScript | 100% Kotlin/Compose, no JS/TS anywhere |
| `applicationId`/root package `com.hazia.emivita` | Fixed project identity — **✅ applied this batch (§9 item 0).** The scheduling-engine batch (§4) had shipped under a placeholder identity (`com.personal.medkeeper` / "MedKeeper" — applicationId, namespace, every class/package name, `rootProject.name`, app label); renamed now rather than letting it compound over more batches. A package/applicationId change is effectively a fresh install on Android (new app-private storage path, new Keystore alias) — done while §8 still confirms no real medication data exists yet, so nothing was lost |

---

## 2. Architecture 🔒

- **DI: manual `AppContainer`, not Hilt.** Decided explicitly (see §9 for the
  tradeoff writeup). OS-instantiated entry points (`BroadcastReceiver`, and
  the Glance `AppWidgetProvider` subclasses `NextRemindersWidgetReceiver` /
  `StockLevelsWidgetReceiver`, both shipped) reach the graph via
  `(context.applicationContext as EmivitaApplication).container` — the same
  idiom throughout, not a special case. Revisit only if a future batch
  genuinely needs multi-instance scoping (nothing on the roadmap does).
- **UI: Jetpack Compose / Material3 throughout.** The only XML views are the
  unavoidable pre-Compose splash theme (`themes.xml`), the notification
  small-icon vector drawable, and `res/layout/glance_default_loading_layout.xml`
  — a `FrameLayout` + `ProgressBar` that both widget descriptors point at as
  their `android:initialLayout`, because `AppWidgetProviderInfo` cannot name
  Glance content directly and the platform needs *some* RemoteViews for the
  window before Glance composes. Unavoidable for the same reason as the other
  two, not a lapse.
- **DB: Room + SQLCipher**, encrypted at rest. Key is a random 256-bit value
  (never user-typed). 🔒 **Resolved this batch (efficiency pass, supersedes
  the `EncryptedSharedPreferences`/`MasterKey`/DataStore+Tink item below):**
  `PassphraseProvider` now (1) hands SQLCipher the key via **Raw Key BLOB
  syntax** (`x'<64 hex chars>'`) instead of treating it as a passphrase
  string — SQLCipher's documented behavior is to skip its PBKDF2 KDF pass
  entirely for a Raw Key, since that KDF exists to stretch a weak
  *human-typed* passphrase, which a SecureRandom 256-bit value already isn't
  — removing real cold-start cost with no change to the key's actual
  entropy; and (2) wraps that key at rest with a **direct
  `AndroidKeyStore`-backed AES/GCM `Cipher`** rather than
  `EncryptedSharedPreferences`/`MasterKey`, dropping the
  `androidx.security:security-crypto` (Tink) dependency entirely — same
  Keystore-backed guarantee, no deprecated-API liability, smaller APK. The
  DataStore+Tink migration item this bullet used to defer to §9 is now moot;
  removed from §9's list below.
- **Navigation:** Navigation-Compose, type-safe routes via `@Serializable`
  sealed interface `EmivitaRoute`. Anything needing an argument (a specific
  medicine/reminder id) gets its own `@Serializable data class` as it's
  built, not a string route.
- **Reminder config storage:** polymorphic — `Reminder.type` (enum) +
  `Reminder.configJson` (the type-specific payload), decoded via the
  sealed `ReminderConfig` hierarchy through standalone `encodeReminderConfig`/
  `decodeReminderConfig` functions (`ReminderConfigCodec.kt`) — kept
  standalone specifically so the scheduling engine doesn't have to depend on
  `ReminderRepository` (which depends on `SchedulingCoordinator`; routing
  through the repository would be circular).
- **Shared occurrence-projection cache, not per-screen simulation.** Today
  screen, History/Calendar screen, Widgets, and `LowStockAlert`/
  `ExpirationAlert` triggering all read from one singleton that projects
  reminder occurrences forward over a requested window, rather than each
  running its own simulation. Decided from medTimer's own documented
  experience (see §4/§5/§9): they built these screens independently first,
  found it caused redundant computation and inconsistent data across
  screens, and had to consolidate afterward (ADR-0003 → ADR-0005). Adopting
  the consolidated shape from the start avoids repeating a mistake that's
  already on record.
- **Backup and sync: one writer, two triggers, not two subsystems.** SAF is
  confirmed for disaster-recovery backup/import — it is **not** the mechanism
  for keeping a Syncthing-fork-visible folder current. Both needs are real
  (see §9 #9), but they share the same underlying password-protected-zip
  writer; only the trigger (manual button tap vs. a debounced explicit-change
  trigger) and the password policy (optionally-skippable vs. always-on)
  differ. Reconciled this way specifically so the project doesn't end up
  building a one-off export flow and a separate ad hoc sync mechanism that
  drift apart — the same mistake §2's projection-cache bullet above already
  avoids for a different subsystem, applied here too.
- **Package layout:** (all under root package `com.hazia.emivita`)
  ```
  core/data/{entity,dao,repository,config,db}   — Room schema, repositories, config codec
  core/scheduling/                               — alarm math, AlarmManager wiring, receivers
  core/notification/                             — channels, notification building
  core/di/                                       — AppContainer
  feature/{today,medicines,history,settings,alarm} — Compose screens
  navigation/                                     — NavHost, routes
  ui/theme/                                       — palettes, typography
  ```

---

## 3. Data model 🔒

**Entities:** `Profile`, `Medicine` (+ `Tag`, many-to-many), `DoseGroup`
(named set of reminders always taken together; `priority` overrides its
members' individual notification priority while the grouping is active),
`Reminder` (belongs to a `Medicine`, optionally to a `DoseGroup`, optionally
chained to a parent `Reminder` via `parentReminderId` — see `LinkedFollowing`
below), `DoseEvent` (a materialized, resolvable instance of a reminder
firing), `StockInfo`.

**`ReminderConfig` sealed hierarchy** (JSON-encoded into `Reminder.configJson`):

| Type | Fields | Scheduling engine support |
|---|---|---|
| `FixedTime` | hour, minute, dose, `RepeatPattern` (Daily / SpecificDaysOfWeek / EveryNDays / **CyclicActivePause**) | ✅ built, all four `RepeatPattern` cases including `CyclicActivePause` |
| `RepeatingInterval` | intervalMinutes, dose, `IntervalStartBehavior`, anchorEpochMillis | ✅ built |
| `DailyWindow` | intervalMinutes, dose, window start/end hour+minute, `IntervalStartBehavior` | ✅ built |
| `LowStockAlert` | threshold, trigger mode (Once/Always/Daily) | ✅ built — `LowStockExpirationAlertEvaluator`, triggers off the projection cache's computed stock run-out date (§4/§9), not a separate periodic check |
| `ExpirationAlert` | lead days, frequency (Once/Daily) | ✅ built — same evaluator, off the projection cache's date tracking |
| **`LinkedFollowing`** | delayMinutes (1–1440), dose, `IntervalStartBehavior` — `parentReminderId` lives as a real `Reminder` column, not here (see below) | ✅ built, including the cascade-reschedule trigger points |

**`RepeatPattern.CyclicActivePause(firstCycleStartEpochDay: Long, activeDays: Int, pauseDays: Int)`**
— birth-control-style cyclic reminders. A 4th case on the existing
`RepeatPattern` used by `FixedTime`, not a new top-level `ReminderConfig` —
cyclic reminders are still "a time of day + a day-selection rule," exactly
like Daily/SpecificDaysOfWeek/EveryNDays already are, just a different rule.
Unlike `EveryNDays`, the pattern is truly periodic in both directions —
active on dates *before* `firstCycleStartEpochDay` too, matching medTimer's
own documented behavior ("the cycle will be calculated only based on that
date and reminders would also be active before that date"). A date is
active when `floorMod(date.epochDay - firstCycleStartEpochDay, activeDays +
pauseDays) < activeDays` — floor-mod specifically (not `%`, which returns
negative remainders for dates before the anchor in Kotlin/Java).

**`ReminderConfig.LinkedFollowing`** — chained "following dose" reminders.
`Reminder.parentReminderId: Long?` is promoted to a real, indexed, FK column
on `Reminder` (`ON DELETE SET_NULL` — deleting a reminder detaches its
following-dose children rather than deleting them, and
`ReminderRepository.delete()` switches each detached child off in the same
transaction, so none is left enabled and unfirable; §7 R25 settled this
against the `ON DELETE CASCADE` this paragraph used to specify, whose stated
justification — that the chain is nested in the UI — was never true of the
shipped flat reminder list) — the same precedent `groupId` already set, and
necessary here because the engine needs to efficiently query "children of
reminder X," which a JSON blob can't support. `delayMinutes` is capped at
1440 (24h), matching medTimer exactly. Next occurrence = the parent's most
recent event time (fired-at for `ON_REMINDED`, resolved-at for
`ON_RESOLVED`) + `delayMinutes`. Chains of arbitrary length compose for
free: each link only ever looks at its own direct parent, and every fire/
resolve event independently triggers a one-level-down cascade to reschedule
direct children (§4) — nothing recursive has to happen at any single moment
for a long chain to work correctly over time.

`IntervalStartBehavior.ON_REMINDED` = fixed grid anchored at creation, never
drifts. `ON_RESOLVED` = anchored to the *actual* last-resolved `DoseEvent`
timestamp (read fresh each time, not a persisted/mutated field — see
`SchedulingEngine`'s doc comment).

**Cross-cutting `Reminder` fields** (apply regardless of `type` — live on
`Reminder` itself, not inside any one `ReminderConfig`, so the engine checks
them once per reminder rather than duplicating the check five times):

| Field | Resolves | Behavior |
|---|---|---|
| `enabled: Boolean` (existing) | — | Scheduling engine returns no next occurrence when false |
| `autoResolve: Boolean = false` | Silent tracking for another person | `ReminderAlarmReceiver` materializes the `DoseEvent` and immediately resolves it `TAKEN` (stock still decrements) — no notification is built at all. Intended for a reminder whose `Medicine` belongs to a `Profile` other than the device owner's; not schema-enforced, just the intended use |
| `activeFromEpochDay: Long?`, `activeUntilEpochDay: Long?` | Tapering / time-period-bounded reminders | `null` = unbounded (today's default behavior). `SchedulingEngine.nextOccurrenceFor()` computes the candidate as normal, then clamps: outside the range → no occurrence. A taper schedule is just several ordinary `FixedTime` reminders with adjacent, non-overlapping ranges — no new `ReminderConfig` type needed |
| `repeatOnUnanswered: RepeatConfig?` where `RepeatConfig(delayMinutes: Int, maxRepeats: Int)` | Auto-repeating "nagging" reminders | Distinct from user-initiated Snooze. After the initial notification posts, if set, schedule a one-off "nag" alarm `delayMinutes` later on the same slot mechanism `AlarmScheduler.scheduleSnooze` already uses (system-triggered, not user-triggered). On nag fire: no-op if the `DoseEvent` is no longer `PENDING`; otherwise re-notify, increment `DoseEvent.repeatCount`, and schedule the next nag until `maxRepeats` is reached |

**`DoseEvent` fields beyond the basics:**

| Field | Resolves | Behavior |
|---|---|---|
| `reminderId: Long?` (was non-null `Long`) | PRN / ad-hoc dose logging | `null` marks a manually-logged dose not tied to any scheduled occurrence |
| `medicineId: Long` (new — denormalized) | PRN / ad-hoc dose logging | Scheduled events keep it in sync with `reminder.medicineId` at materialization time; PRN events have no reminder to derive it from, so it's stored directly. `DoseEventRepository.logAdhocDose(medicineId, doseAmount, atEpochMillis)` creates one with `reminderId = null`, `status = TAKEN` immediately (logged retrospectively, no `PENDING` state), `scheduledEpochMillis = resolvedAtEpochMillis`. Default dose-amount suggestion in the UI is the medicine's last-used amount — simpler than medTimer's "inactive reminder as preset" indirection, same outcome |
| `stockBefore: Double = -1.0`, `stockAfter: Double = -1.0` | Event editing / undo | Sentinel, not nullable columns — same reasoning as medTimer's ADR-0004: Room `AutoMigration` handles non-null defaulted columns more predictably than nullable `Double`, and "was stock tracked for this event" collapses to one check (`stockBefore != stockAfter`) that also correctly covers old migrated rows and un-tracked medicines. Recorded by `resolve()` whenever the medicine has stock tracking active. `DoseEventRepository.switchStatus(doseEventId, newStatus)` (Taken↔Skipped) uses these recorded values to restore/reapply stock correctly instead of blindly re-decrementing. `editRecordedTime`/`editDoseAmount` correct the event's own fields but don't retroactively touch stock — matches medTimer's scope, only a status switch does |
| `repeatCount: Int = 0` | Auto-repeating "nagging" reminders | Incremented per nag fire, capped at `repeatOnUnanswered.maxRepeats` |

**`DoseStatus.REFILLED`** (new 4th status, alongside PENDING/TAKEN/SKIPPED) —
medTimer's `RefillProcessor`, ported: `DoseEventRepository.logRefill(medicineId,
refillAmount, atEpochMillis)` mirrors `logAdhocDose` (`reminderId = null`,
`status = REFILLED` immediately) and reuses `StockInfo.refillQuantity`
(already in the schema — no gap there, just no behavior using it yet) as the
one-tap default amount, falling back to a manual entry if it's unset. Uses
the *same* `stockBefore`/`stockAfter` sentinel pair, just with `After >
Before` instead of `<` — one mechanism for both directions of stock change,
not two. Shows up in the History timeline as an ordinary `DoseEvent` row,
distinguished by status, exactly like medTimer folds refills into the same
event stream rather than a separate log.

These seven fields resolve five of the gaps found comparing against medTimer's
`docs/UseCases.md` (PRN logging, event editing/undo, nagging reminders,
silent auto-resolve tracking, tapering) — locked into the schema now so the
Add/Edit Reminder UI (§9 #1) doesn't need a retrofit right after shipping.
The *UI* to configure the advanced ones (nag delay/count, active date range,
auto-resolve toggle) can still phase in progressively — the schema doesn't
have to wait for the UI, only the reverse.

**Previously "not yet representable," now resolved:** cyclic, linked, and
weekend-mode reminders were flagged here as needing a schema decision with
no slot to hold them. Cyclic and linked now have one (`CyclicActivePause`
above, `LinkedFollowing` above). Weekend-mode turned out not to need a
`ReminderConfig` slot at all — checking medTimer's actual
`WeekendModeSchedulingDecorator` shows it's a **global preference, not a
per-reminder setting**: one app-wide toggle, day-of-week set, and time
window, applied as a final adjustment to every reminder's computed next
occurrence uniformly (no per-reminder opt-out in medTimer either — matched
as-is). See below.

**Global user preferences (DataStore, not the SQLCipher medical DB) 🔒:**
non-sensitive, device-wide settings that aren't attached to any one
`Reminder`/`Medicine` row, stored via `androidx.datastore:datastore-preferences`
(pure AndroidX, no GMS — fits §1; this is the same dependency §9's
snooze-duration item already needed, now covering both):

| Field | Resolves | Behavior |
|---|---|---|
| `snoozeDurationMinutes: Int = 10` | Configurable snooze | Replaces the hardcoded `DEFAULT_SNOOZE_MINUTES` constant; exposed on "Alarms & Snooze" |
| `weekendModeEnabled: Boolean = false` | Weekend-mode delay | Exposed on the already-existing "Weekend Mode" Settings destination |
| `weekendDays: Set<Int> = emptySet()` | Weekend-mode delay | `1=Mon..7=Sun`, consistent with `SpecificDaysOfWeek`'s convention |
| `weekendStartTime: LocalTime`, `weekendEndTime: LocalTime` | Weekend-mode delay | The window within which a reminder gets pushed |
| `lockRemindersUntilTakenByDefault: Boolean = false` | `cannotBeSkipped` global default | The global half of the override `Medicine.remindersLockedUntilTaken`'s own doc comment already promises but that never got built — see below |

**Bug found in already-shipped code, not just a future gap:** `Medicine
.remindersLockedUntilTaken` is typed `Boolean` (default `false`), but its own
comment calls it "a per-medicine *override* of the global setting" — a plain
`Boolean` can't represent "no override, inherit the global default," only
explicit true/false, which contradicts what it's documented to do. Needs to
become `Boolean?` (`null` = inherit `lockRemindersUntilTakenByDefault` above,
non-null = explicit override) — a real (if small) migration, not just a spec
note. Separately, and more importantly: **nothing currently reads this field
at all.** `ReminderNotificationBuilder` offers Skip unconditionally on every
notification regardless of it.

🔒 **Both halves resolved** (recorded here rather than rewritten away, since
the reasoning below is what got built). The `Boolean?` widening shipped in
`Migrations.MIGRATION_2_3`, and the effective lock state now resolves through
one place, `SettingsRepository.isLockedUntilTaken(medicine)` — exactly the
`medicine.remindersLockedUntilTaken ?: lockRemindersUntilTakenByDefault`
fallback described below it. Four sites honour it:
`ReminderNotificationBuilder` omits the Skip action entirely when locked
(not "adds it disabled", so there is no button to guard), and
`ReminderActionReceiver` plus `AlarmActivity` (twice) each re-check
independently, which is the defensive rejection this paragraph asked for
against a stale notification button surviving a setting change.
Distinct from `DoseGroup.priority`
(§3 above) — that's a notification-loudness default, this is a hard
behavioral constraint; the two aren't the same mechanism and §6's UX bullet
about this was wrong to conflate them, corrected there now.

**`Medicine.sortOrder: Double`** (new field, missing entirely) — manual
drag-to-reorder for the medicines list. A `Double`, not an `Int` index, so
reordering one item only ever needs a new value between its new neighbors
(e.g. `(before + after) / 2`) rather than renumbering every row on every
drag — medTimer's own choice, carried over as-is rather than reinvented,
now confirmed directly against medTimer's actual (recently modularized)
source: `MedicineDao.getAllFlow()` really is `ORDER BY sortOrder`, same
column, same ordering. Resolved:

- **Migration (existing rows):** one-time backfill, `ORDER BY name ASC`,
  values spaced apart (e.g. `1000.0, 2000.0, 3000.0…`, not `1, 2, 3…`) so
  the list reads identically to today's `ORDER BY name ASC` output
  immediately after the schema change, and so a future drag-reorder can
  slot between any two rows without renumbering the rest.
- **New medicines (post-migration):** `sortOrder = createdAtEpochMillis` —
  append at the end. Deliberately **no** alphabetical-insertion
  computation — a newly added medicine lands at the bottom of the list
  until manually dragged, a small, accepted difference from today's
  always-alphabetical behavior, reasonable for a short, hand-curated
  personal list.
- **`MedicineDao.observeForProfile()` switches from `ORDER BY name ASC` to
  `ORDER BY sortOrder ASC`** as part of #1 (§9) — the column is inert
  until this query change ships alongside it.

**`Medicine.barcodeValue: String?`** (new field) — stores a scanned
barcode/QR code's decoded content, for quick re-identification only, not
automatic drug-data lookup: no local drug database exists to resolve a
code into a name/dosage, and an online one is off the table (§1,
local-first). Two uses: (1) attach a code to a medicine when first adding
it — scan a bottle, save the code alongside the manually-entered
name/dose; (2) later, scan the same bottle again to jump straight to that
medicine instead of searching the list by name. Scanning itself is
delegated entirely to whatever scanner app the user has installed — no
barcode library, no camera permission, no new dependency in Emivita at
all. Uses the de facto standard `com.google.zxing.client.android.SCAN`
`Intent` contract (honored by BinaryEye, among others — MIT, F-Droid) via
the Activity Result API, reading the returned `SCAN_RESULT` extra. Needs a
`<queries>` block in the manifest declaring that intent action — Android
11+ (§1's own minSdk floor) restricts package visibility, so
`resolveActivity()` silently returns nothing without it. Graceful
degradation if no scanner app is installed: the scan button still shows,
but tapping it with nothing to resolve to explains what's missing and
points to BinaryEye on F-Droid by name, rather than failing silently or
crashing.

**`Medicine.indication: String?`** and **`Medicine.storageNote: String?`**
(new fields, promoted from the parking lot) — short free-text "what it's
for" (e.g. "blood pressure") and storage requirements (e.g. "requires
refrigeration"). Promoted rather than left parked: stress-tested against
the scheduling engine, projection cache, and export plan specifically and
confirmed to be non-events — plain nullable columns with zero interaction
with anything else in the app, no different in risk from any other field
in #1's form.

**`Medicine.doseUnit: String?`** (new field, this batch — device-testing
session request) — a free-text "Measurement" label ("capsules", "drops",
"tablet") entered once on Add/Edit Medicine, then displayed next to every
formatted dose amount app-wide (Today's timeline, History's List/Table/
CSV/PDF) via `formatDoseWithUnit()`, and shown as a read-only suffix hint
next to every dose-amount *entry* field (the 4 reminder-type forms' shared
`DoseField`, the PRN "Log dose" dialog, `DoseEditDialog`) so the unit is
visible while typing without being re-typed at each of those points.
**Deliberately a separate column from `StockInfo.unit` below, not a reuse
of it** — that field's *nullability* is load-bearing elsewhere
(`DoseEventRepository`, `LowStockExpirationAlertEvaluator`,
`OccurrenceProjectionCache` all read `stockInfo.unit == null` as "this
medicine has stock tracking off"); typing a display label must never
silently flip stock-tracking behavior on for a medicine that never asked
for it. Migration: `MIGRATION_3_4` (v3→v4), a single nullable
`ALTER TABLE medicines ADD COLUMN doseUnit TEXT` — same shape as
`MIGRATION_2_3`, covered by `MigrationFrom3To4Test` (direct-fixture
technique, same reasoning as that test's own doc comment). History's
Table view keeps the unit inline in the existing Dose column rather than
adding a 5th column (explicit ask); the CSV export gets a genuinely
separate Measurement column instead (structured/spreadsheet format, where
a plain numeric Dose column stays usable for `SUM()`), while the PDF
report (a human-readable document, like the in-app screens) keeps it
inline like everywhere else. Device-verified (OnePlus 6T): Measurement
field on Add Medicine, suffix hint on a Fixed Time reminder's Dose amount
field and the Log dose dialog, and the resulting "1 tablets" text on
Today, History List, and History Table.

**`Medicine.color: String?`** (hex, new field) — 🔒 resolved. An HSV
saturation/value square + hue strip + hex readout (Material-style picker, opened
as a modal from a "Color" toggle row, confirmed via Ok/Cancel) — not a
curated swatch set, so differentiation between medicines isn't capped at
a fixed count. Toggle **off** → flat `#B8ABA9` neutral card, not an
icon-derived default — passes AA/AAA with dark text (~9.4:1), muted
enough that it won't read as a deliberately-chosen hue (see §6 for the
picker's other UX details). ❓ still open, doesn't block #1: whether the
picker surfaces a live differentiation hint (new color's contrast against
every other medicine's assigned color drops too low) before Ok, or is
left entirely to the user's judgment.

**Confirmed: the hex picker above replaces `Medicine.colorArgb: Int?`, it
doesn't sit alongside it.** The scheduling-engine batch's schema already
carries a `colorArgb: Int?` field doing this same "custom card color" job —
the paragraph above was written as if `color` were a clean new field without
addressing that overlap. Resolved now: `colorArgb` is dropped in favor of
`color: String?` (hex). Since the Add/Edit Medicine UI (§9 #1) — the only
thing that would ever populate `colorArgb` — hasn't shipped yet, §8 confirms
there's no real data to actually convert; treat this as a column rename +
type change in the migration rather than a real value-preserving backfill,
but write it as a real Room migration regardless, in case a test build has
sample rows. 🔒 **Resolved:** `Profile` also drops `colorArgb: Int?` in favor
of `color: String?` (hex) — same move as `Medicine`, and specifically the
*same* HSV saturation/value square + hue strip + hex-readout picker component,
not a second, simpler swatch-based widget built just for profiles. The
premise that a curated swatch set would be "enough" for profiles doesn't
hold up: someone tracking more people/pets than a small fixed palette
offers, or who wants to deliberately arrange related hues on purpose (e.g.
grouping doses of the same medication family so they're recognizable at a
glance), runs past whatever a swatch set could offer. The app adapts to how
people actually color-code their real life, not the other way around — one
picker, reused everywhere a custom color is picked, rather than two widgets
of different capability living side by side. (Open follow-on, not blocking:
whether Profile's toggle-off state reuses `Medicine.color`'s flat `#B8ABA9`
neutral fallback, since it's the same component, or gets its own — hasn't
come up yet because no Profile UI is scheduled in §9 at all.)

Weekend mode is applied as the last step in
`SchedulingEngine.nextOccurrenceFor()`, after the type-specific calculator
and the `activeFrom/UntilEpochDay` clamp: if the computed occurrence's
day-of-week is in `weekendDays` and its time-of-day falls in
`[weekendStartTime, weekendEndTime)`, push it to `weekendEndTime` on that
same day; otherwise leave it untouched. Ported faithfully from medTimer's
decorator, including the "applies to every reminder type unconditionally,
no opt-out" behavior — `SchedulingFactory` wraps every scheduler with it
regardless of type, and Emivita does the same rather than inventing a
per-reminder override medTimer doesn't have.

---

## 4. Scheduling engine — status ✅ built (this batch)

Built: exact-alarm scheduling (API 31+ permission gating, inexact
fallback), the alarm-fired path (materialize `DoseEvent` → build & post
notification → conditionally reschedule), Take/Skip/Snooze, full-screen
alarm intent gated behind `canUseFullScreenIntent()`, boot/update
rescheduling, `DoseGroup` priority override in notification building.

`SchedulingEngine.nextOccurrenceFor()` answers exactly one question — "the
single next occurrence for this reminder" — because that's all
`AlarmScheduler` needs to arm the next exact alarm. It stays exactly as-is;
the projection cache (§2/§9) is a second, new consumer of the same pure
calculators in `ReminderScheduling.kt`, not a replacement — it needs "list
of occurrences over a date range," which today's calculators don't do yet
but are the right shape to extend into (call them repeatedly, walking
forward, the way medTimer's `SchedulingSimulator` does).

**Since built, in later batches (§9 #4/#14) — this list is stale, kept only
as a historical note per §10's "corrected before the next handoff" rule
(found during a routine "what's next by dependency" pass, same pattern as
R6/R12/R14):** the projection cache (`OccurrenceProjectionCache.kt`), the
five schema-driven gap resolutions (`autoResolve`, `activeFrom/UntilEpochDay`
clamping, `repeatOnUnanswered` nagging, PRN `logAdhocDose`, `switchStatus`
stock-correct undo), and `CyclicActivePause`/`LinkedFollowing`/the
weekend-mode adjustment step — including both cascade-reschedule trigger
points (`ReminderAlarmReceiver.cascadeRescheduleOnRemindedChildren`,
`RealSchedulingCoordinator.onDoseResolved`) — are all real, working code
now, verified directly against `SchedulingEngine.kt`/`ReminderScheduling.kt`/
`AddReminderScreen.kt` while regenerating this section, not assumed from the
§9 checkmarks alone.

Still genuinely open:
- `AlarmActivity`'s visual design — it's functionally complete
  (Take/Skip/Snooze work end-to-end) but deliberately plain; the
  "triangle-button alarm view" design is layout/frontend work for later.
- One unverified item: `BootRescheduleReceiver`'s `exported="true"` — went
  with the empirically-safer choice over the theoretically-cleaner one
  (see the manifest comment for the full reasoning); confirm reminders
  survive a reboot on the actual test device.

---

## 5. medTimer reuse map 🔒 (decided, don't re-litigate per-file)

| Category | Verdict |
|---|---|
| Scheduling/alarm/notification logic | Portable as a functional reference — ported this batch |
| **`FutureRemindersRepository` pattern** (now `SimulatedRemindersRepository` in medTimer's current, modularized source — verified directly against that source, not assumed: singleton forward-simulation cache, per-consumer window via `requestWindow`/`releaseWindow`, debounced + time-budgeted recompute, stock run-out date computed as a side effect of the same pass) | Portable pattern — adopted as a locked architecture decision (§2); resolved implementation design in §9 #4; foundation for §9's Today/History/Widget/LowStock batches |
| **Compose charting/table/calendar libraries** (ADR-0001: Vico for charts, hand-rolled `SortableTable`, Kizitonwose Calendar's Compose artifact — all Compose-native, GMS-free, verified against a de-googled flavor) | Direct library picks for the History screen (§9) — not a pattern to adapt, a shopping list. **✅ All three built** (§9 #6): `AdherenceChart.kt` (Vico), `HistoryTableView.kt` (hand-rolled), `HistoryCalendarView.kt` (`com.kizitonwose.calendar:compose`, the Android-only artifact, not `compose-multiplatform` — this app targets Android exclusively, §1; pinned to `2.7.0`, not the current latest, R16 in §7) |
| SAF backup/restore JSON (`BackupManager`/`FileHelper`, SAF-based already) | SAF I/O plumbing is a portable pattern; the password-protection layer is **original** — medTimer has no equivalent since its DB isn't encrypted (§2), Emivita's is. Resolved design in §9 #9 (password-protected zip via `zip4j`, Catima-style) |
| **CSV/PDF history export** | Concept adopted, library choice diverges deliberately: medTimer uses the third-party `SimplyPDF`; Emivita uses Android's native `android.graphics.pdf.PdfDocument` (zero new dependency, no de-googled question to even ask). CSV needs no library either way |
| **Stock `stockBefore`/`stockAfter` sentinel** (ADR-0004) | Adopted verbatim into `DoseEvent` (§3), including the `-1.0`-sentinel-over-nullable reasoning |
| **"Log additional dose" (PRN)** | Concept adopted, simplified: medTimer offers inactive reminders as amount presets; Emivita just suggests the medicine's last-used amount (§3) — same outcome, one less indirection |
| **`WeekendModeSchedulingDecorator`** (global preference + unconditional final adjustment step, not a per-reminder field) | Adopted faithfully into `SchedulingEngine` (§3/§4) after actually reading the implementation rather than assuming it was per-reminder config |
| **`RefillProcessor`** (one-tap restock using a preset amount, logged via the same stock-sentinel mechanism) | Adopted as `DoseStatus.REFILLED` + `logRefill` (§3) — `StockInfo.refillQuantity` already existed in the schema, only the behavior was missing |
| **`Medicine.sortOrder`** (fractional-double reordering, no renumbering on drag) | Adopted verbatim (§3) — was missing from the entity entirely |
| Widgets | Portable *concept*, not code — medTimer uses `AppWidgetProvider`/RemoteViews; Emivita uses **Glance** (Compose-based), per the manifest's own forward-looking comment. Rebuild in Glance idiom. |
| De-googled `foss` flavor pattern (`NoOpLocationProvider`/`NoOpGeofenceRegistrar`) | Reference pattern for how to no-op location-based snooze — not yet needed since that feature isn't planned |
| Biometric lock (`Biometrics.kt`) | Small, portable near-verbatim when built |
| Entire UI layer (Fragments, XML, Navigation Component, PreferenceX, TableView/androidplot/IconDialog) | **Not portable** — functional spec only, every screen is original Compose work |
| DI (Hilt) | **Not adopted** — see §2 |
| DB encryption | medTimer has none; Emivita's SQLCipher layer is original, not ported |

---

## 6. UX decisions — confirmed 🔒 (from the UX audit, now Appendix B)

- **Today screen:** linked/timeline pattern with aggregate grouped-dose
  nodes. ✅ built (`TodayScreen.kt`'s `TimelineNode`/`TimelineEntry`, §9 #5).
- **Medicine card:** full-card color, icon-per-medicine-type, tags as
  colored chips.
- **Add-medicine reminder-type picker:** 5 labeled cards — icon + name +
  one-line description visible *in the picker itself* (no separate help
  screen). Tapping a card goes straight to its config, no disambiguation
  step. Labels: "Fixed times", "Repeating interval", "Daily window",
  "Low-stock alert", "Expiration alert".
- **Medicine color picker:** HSV saturation/value square + hue strip + hex
  readout (Material-style modal, "Color" toggle row opens it, confirmed
  via Ok/Cancel) — not a curated swatch set, so differentiating many
  medicines isn't capped at a fixed count (§3). Toggle off → flat
  `#B8ABA9` neutral card. Card text color is resolved dynamically per
  chosen hex — whichever of near-black/near-white clears AA (4.5:1)
  against it; if neither does, fall back to a subtle scrim behind the
  text rather than shipping unreadable labels.
- **Add/Edit Medicine form layout:** single-column, order: name + inline
  "scan barcode" action → appearance (icon-per-type grid + the color
  picker above) → tags (inline, matches the Medicine-screen rule below)
  → indication/storage note (short free-text, deliberately low visual
  weight — §3 calls these "non-events") → lock-until-taken override →
  Save/Cancel. Appearance carries more visual weight than the name field
  — once the medicine's back in the list, color+icon is the actual
  scan-anchor (`Medicine card`, above), not the name.
- **Reminder-config sub-form layout** (Fixed Time / Repeating Interval /
  Daily Window): single-column, shared order across all three — type
  context (inherited from the picker, non-editable) → primary schedule
  fields → start-behavior toggle (`IntervalStartBehavior`, only where the
  engine uses it — not Fixed Time) → dose → Save/Cancel. Fixed Time's
  `RepeatPattern` control (Daily / Specific days / Every N days) reveals
  its day-chip or every-N-days stepper progressively, only once picked;
  `CyclicActivePause` ships as the 4th `RepeatPattern` chip ("Active/pause
  cycle") inside the Fixed Time card, revealing its active/pause day steppers
  the same way (§9 #14). It is a pattern within that card, not a new card —
  which is what the "not a new pattern" note here was protecting, and that
  part held. The "stays out of the picker for now" half did not.
- **Semantic state tokens** (selected/unselected, focus, error, disabled)
  are resolved relative to whichever of the 8 palettes' Accent is active
  (was 4 — updated this session along with the palette replacement above),
  not new fixed hues per palette. The old edge case this table used to
  carry — Neutral's Accent being fully desaturated, leaving no hue for the
  Error rule's "+150–165°" to rotate from — is now moot rather than fixed:
  Neutral is dropped entirely, and all 8 replacement palettes carry a real
  accent hue, so every one of them can run this formula as written. **✅
  Shipped (§9 item 0b):** the Error row specifically is now computed code,
  not just a table rule — `colorSchemeFor()` rotates each palette's own
  accent hue by a fixed +158° (one concrete point inside this row's
  150–165° range) at S60/L45, per palette, in both light and dark. Worth an
  eyeball once it's actually on-screen: rotating off *accent* rather than
  targeting red means a few palettes land on a non-red error color (e.g.
  Golden Hour's computes to a teal), which is the formula working as
  specified but an unusual choice for a medication app's error state:

  | State | Rule |
  |---|---|
  | Selected/active | Accent fill, text auto-picked (dark if Accent L>60%, else light) |
  | Unselected | Surface −8% L, 1px border at Accent 40% opacity |
  | Focus ring | Accent hue, S+10, L−5, 2px outline, no fill |
  | Error | Accent hue +150–165° (compound/near-complementary — related, never confusable with Accent), S60/L45 |
  | Disabled / "coming soon" | Surface desaturated to S8–12%, muted text — reuses the Low-Stock/Expiration treatment above |
- **Medicine screen:** Notes and Tags are inline visible sections (like
  Color), not overflow items. Overflow menu keeps only Duplicate,
  Duplicate-with-reminders, Delete.
- **Stock & expiration:** ✅ built (`StockExpirationScreen.kt`,
  `EmivitaRoute.StockExpiration`) — one screen, three visually zoned
  sections, each on a distinct tonal container color (`primaryContainer`
  for current stock, `tertiaryContainer` for low-stock alert,
  `errorContainer` for expiration) — resolving T5's register-distinction
  finding via color zoning rather than a separate layout treatment.
  Current stock (qty/unit/refill-amount, computed runs-out date read live
  from the projection cache, one-tap **Refill now**), Low-stock alert
  (enable switch/threshold/`FilterChip` trigger-mode row), Expiration
  (enable switch/`DateField`/lead-days/`FilterChip` frequency row). Reached
  from `MedicineDetailScreen`'s overflow menu. **One entry point, no
  duplicated fields elsewhere** is now actually enforced, not just
  designed: `LowStockAlert`/`ExpirationAlert` were removed from the Add
  Reminder type picker (their create path is this screen only); the edit
  path for a reminder created before this change still resolves through
  `EditReminderScreen`'s existing branches, left in place deliberately
  rather than risking a bigger refactor.
- **Settings:** 6 destinations grouped under section headers, not a flat
  list — matches the already-built `SettingsScreen.kt` groupings exactly.
  Every row gets a plain-language one-line description, not just some.
- **Text hierarchy:** heading (bold, full-contrast) / value (medium
  weight, full-contrast) / helper text (regular weight, muted) —
  consistent across every screen, not just some. **❓ Open, from
  the frontend/theme review (Appendix C):** dose amounts, times, and schedule
  text should probably never render at `labelSmall` (11sp, the system's
  smallest size) regardless of which screen reuses that style for
  chip/badge labels elsewhere — flagged given the app's own
  safety-critical-adherence purpose (§1), not yet made a hard rule.
- **Power-user features kept at full capability, just better labeled:**
  per-medicine "can only be dismissed by marking taken"
  (`Medicine.remindersLockedUntilTaken`, overriding a new global default —
  §3; *not* `DoseGroup.priority`, which is a separate, softer
  notification-loudness default — corrected here after conflating the two
  earlier), and the three notification priority levels (Standard/High/
  Alarm) — discoverability is the fix, not simplification.
- **Palettes:** the original 4 (Clinical/Cozy/Neutral/Playful) are
  **fully replaced**, not supplemented — decided specifically because
  those 4 were picked without discussion (see §7 for the ledger entries
  this closes out), and **✅ shipped this batch (§9 item 0b)**: the old 4
  palette objects are gone from the codebase, not just superseded on
  paper. 8 new palettes, each with light + dark, each still traceable to
  `visual-craft`'s `design-lexicon.md`
  (inspired-by, not exact-hex, per this session's brief) and each
  now audited at AAA (text/surface) and AA (mutedText/surface,
  outline/surface *and* outline/background — the two roles the old audit
  found broken in all 4 outgoing palettes, fixed by construction this
  time rather than patched after the fact):

  | Palette | Family | Persona | Lexicon source |
  |---|---|---|---|
  | Golden Hour | Amber | Warm caregiver | Cozy → Cozy Cocoa |
  | Sunlit Focus | Amber | Everyday professional | Extroverted-sunny |
  | Forest Floor | Green | Wellness-minded | Naturecore → Mossland |
  | Sage Clinical | Green | Calm clinician | Clinical/Medical, greener |
  | Ink & Ledger | Blue | Methodical & disciplined | Scholastic → Navy Scholastic |
  | Cobalt Porcelain | Blue | Refined & elegant | Ming Dynasty Porcelain |
  | Rose Petal | Pink | Soft & youthful | Coquette/Girly, light register |
  | Velvet Ribbon | Pink | Confident & editorial | Coquette/Girly, dark register |

  Ink & Ledger and Cobalt Porcelain were deliberately built to *not*
  resemble each other — an earlier same-session pass paired Cobalt
  Porcelain with a more literal Clinical-Blue-style second blue and both
  read as "cool professional," so the second blue was rebuilt around
  Scholastic's red margin-rule accent instead, giving it a real second hue
  rather than just a hue-shifted copy.

  Two phone-specific adaptations apply to every palette's dark register,
  the second beyond what any consulted skill covers, reasoned from first
  principles this session — flag if either turns out wrong once it's
  actually running on-device:
  - OLED dark backgrounds sit at 4.5–5.9% lightness (not the 8–12% that
    reads fine on a desktop-tuned dark theme), keeping each family's own
    hue rather than dropping to neutral black.
  - Astigmatism/eye-comfort pass: no pure `#000000`/`#FFFFFF` anywhere,
    peak dark-mode text/surface contrast capped around 12–15:1 rather
    than pushed toward extremes (high-contrast bright elements on very
    dark OLED can produce halo/glare artifacts, worse for astigmatic
    vision, and blue scatters more due to chromatic aberration) — pair
    this with no glow/blur box-shadows on bright dark-mode elements and
    regular/medium body-text weight rather than thin, once real
    typography is wired to these palettes.

  Full token tables (all 8 × light/dark, every role, every contrast ratio,
  computed not eyeballed) are inlined in full in **Appendix A** at the end
  of this file. There is no separate palette file anymore — it was merged
  in here and retired, specifically so the actual hex values can't end up
  living in a second document that goes stale or doesn't get re-uploaded
  next session.

  ❓ **New from this batch, not decided here on purpose:** nothing above
  specifies a *default* palette, and no persisted-preference mechanism
  exists yet either (§8 — Settings/DataStore hasn't landed). §9 item 0b's
  code needed to pick something as `MedPalette`'s default parameter to
  compile; **Golden Hour** was used (first-listed above, arbitrarily, not
  a design decision) — revisit once either a real default is chosen on
  purpose or the persisted-preference plumbing (§9 #12-adjacent) makes the
  question moot.
- **"Log dose" entry point** (PRN, §3/§9): a secondary action alongside the
  Today screen's FAB, not a separate bottom-nav destination — picks a
  medicine, suggests its last-used amount, defaults to "just now."
- **Event editing:** tap-to-edit on a Today/History entry, opening the
  shared `DoseEditDialog` — status switch (Taken↔Skipped), recorded time,
  and dose amount are each editable; editing amount doesn't retroactively
  touch stock (only a status switch does, per §3). Shipped as a tap rather
  than medTimer's swipe deliberately (`DoseEditDialog.kt`): every field this
  bullet calls out is editable either way, and a modal step before altering
  a medication record reads safer than a gesture that is easy to trigger by
  accident.
- **Snooze duration is user-configurable**, not hardcoded — lives on the
  already-planned "Alarms & Snooze" Settings destination, default 10
  minutes (§9).

---

## 7. Audit findings ledger

Originally from a separate `Emivita-audit.md`, absorbed into this table
long ago; that file no longer exists anywhere and nothing is missing from
what follows. Kept here so status doesn't have to be re-derived by reading
old chat every time.

Rows are append-only: a finding that turns out to be wrong, or whose
subject later changes, gets a **new** row rather than an edit, so the
reasoning that produced the original stays readable. The **Superseded by**
column is how you tell without replaying the whole table — if it is
populated, read the row it names before acting on this one. It is only
used where a later row corrects or invalidates the subject — not for a
restructuring that leaves the original conclusion standing, and not for
merely related reading.


| # | Finding | Status | Superseded by |
|---|---|---|---|
| 1 | Gradle/AGP version mismatch (§2.1) | ✅ Fixed |  |
| 2 | `NeutralPalette` AA contrast failure (§4.1) | ✅ Fixed |  |
| 3 | Stale `libs.versions.toml` (§2.2) | ✅ Fixed (deleted) |  |
| 4 | Playful dark-theme comment overstated contrast (§4.2) | ✅ Fixed |  |
| R1 | `MedicineRepository.addTag()` not transaction-wrapped | ✅ Resolved — wrapped in `database.withTransaction { }`, shipped alongside #1 |  |
| R2 | `MedicineRepository.decrementStock()` dead code | ✅ Resolved — deleted (§9 #15); `DoseEventRepository` always called `medicineDao.decrementStock` directly, so the repository-level wrapper had zero call sites |  |
| R3 | No tests for `resolve()`/tag-dedupe race-prone logic | ✅ Resolved (§9 #15) — `DoseEventRepositoryTest`/`MedicineRepositoryTest`, in-memory Room (not mocked DAOs, since `withTransaction` is inline) |  |
| §4.4 | `Color.asOnSurfaceSafe()` documented no-op | ✅ Resolved (§9 #15) — real WCAG relative-luminance contrast ratio against the given background, blended toward black/white by the minimum amount needed to clear 4.5:1 | **R21** — subject deleted |
| §7 | Stock launcher icon | ✅ Resolved — adaptive icon (`mipmap-anydpi` XML + per-density foreground PNGs) landed with the rename batch, but only 3 of 5 densities (xhdpi/xxhdpi/xxxhdpi) shipped; hdpi and mdpi were missing until a separate icon patch supplied them — merged in during this reconciliation pass. `ic_launcher_background` color (colors.xml) was already correct in both. |  |
| §8 | Placeholder-screen copy ("...once the data layer is wired up") | ✅ Resolved — moot once #1's real screens shipped; no such string remains anywhere in the codebase |  |
| §2.3 | Full-screen alarm intent gating | ✅ Fixed (this batch) |  |
| R4 | `androidx.health.connect:connect-client` dependency + manifest Health Connect scaffolding present in delivered code, contradicting #13's ❌ Dropped decision (found in spec-vs-code review, not `Emivita-audit.md`) | ✅ Resolved — shipped in §9 item 0: dependency and both manifest comments removed |  |
| R5 | `androidx.work:work-runtime-ktx` (WorkManager) dependency present for undesigned "periodic auto-backup," contradicting §9 #4's debounce+explicit-trigger design (found in spec-vs-code review) | ✅ Resolved — reconciled in §9 #9, dependency removed in §9 item 0 | **R14, R21** — still present transitively |
| R6 | This batch's own bundled `README.md`/`docs/SPEC.md` describe the prior (data-layer) batch, not the scheduling-engine batch actually shipped — see §10's note on spec/code lag | ✅ Resolved — both regenerated this batch (rename batch, §9 item 0); previously described a batch two behind, not one |  |
| R7 | `Medicine.colorArgb: Int?` (shipped) vs. `Medicine.color: String?` (hex, spec'd) never reconciled; `Profile.colorArgb` undocumented entirely | ✅ Resolved — `color: String?` (hex) replaces `colorArgb` on both `Medicine` and `Profile` (§3), same picker component reused for both |  |
| R8 | §9 item 14's UI-scope wording treated `CyclicActivePause` as needing its own top-level Add-Reminder-picker card ("a 6th card"), contradicting §3/§6's locked decision that it's a 4th `RepeatPattern` case inside the existing "Fixed Time" card, not a new top-level type — found reviewing this batch's docs regeneration | ✅ Resolved — reworded in §9 item 14 (this batch); only `LinkedFollowing` is an actual new top-level `ReminderConfig` needing a new picker card. **Not yet reflected in whatever copy of this spec lives outside this repo** — same fix needs applying there too |  |
| R9 | `app/build.gradle.kts` pinned `androidx.security:security-crypto:1.1.0` — no such stable artifact has been published; §2's own text already correctly describes the dependency as `1.1.0-alpha07` (the release where `EncryptedSharedPreferences`/`MasterKey` were deprecated), so the plain `1.1.0` string in the build file wouldn't resolve at all — found in spec-vs-code review | ✅ Resolved — build file now pins `1.1.0-alpha07`, matching §2 (this batch, §9 item 0) |  |
| T1 | `LightOutline` fails 3:1 against `LightSurface` in all 4 outgoing palettes (1.48–1.68:1, checked against pure-white surface rather than the tint it was actually tuned against) — found in the frontend/theme review (Appendix C), this session | ✅ Resolved — moot, all 4 replaced (§6); every one of the 8 new palettes has its `outline` solved to clear 3:1 against **both** background and surface by construction, not patched per-hex after shipping |  |
| T2 | Row-description helper text (`onSurface.copy(alpha = 0.6f)`) fails AA normal-text contrast (3.38–3.97:1) in all 4 outgoing palettes — the same gap Appendix B's finding #8 already named ("helper text... secondary/muted tone") but the opacity shortcut didn't actually deliver; found in the frontend/theme review (Appendix C), this session | ✅ Resolved — moot, all 4 replaced (§6); every one of the 8 new palettes ships a real audited `mutedText` hex at ~4.6:1 against surface, not `.copy(alpha=)` |  |
| T3 | `colorSchemeFor()` only overrides `background/surface/primary/primaryContainer/secondaryContainer/tertiaryContainer/outline` — `secondary`/`tertiary`/`error`/`onError`/`errorContainer` are unset and silently fall back to Material3 stock red/etc.; found in the frontend/theme review (Appendix C), this session | ✅ Resolved — shipped in §9 item 0b for all 8 palettes; `onSurfaceVariant` (T2's mutedText token) wired in the same pass, not originally in scope for T3 but the natural M3 slot for it |  |
| T4 | The Error state-token rule (§6 above, "Accent hue +150–165°") has no hue to rotate from against Neutral's fully-desaturated Accent — found in the frontend/theme review (Appendix C), this session | ✅ Resolved — moot, Neutral dropped entirely (§6); all 8 replacement palettes carry a real accent hue |  |
| T5 | Stock & Expiration and History share no explicit `app-register-layout` register distinction, though their actual data (threshold/countdown vs. trend-over-time) calls for different ones — found in the frontend/theme review (Appendix C), this session | ✅ Resolved — Stock & Expiration shipped with distinct tonal-container zoning per section (§6); audited against History's plain-list treatment and found no conflict — the two screens now read differently by construction |  |
| T6 | `labelSmall` (11sp) has no rule against rendering dose/schedule-critical text at that size, given the app's own safety-critical-adherence purpose (§1) — found in the frontend/theme review (Appendix C), this session | ❓ Still open — logged in §6, not yet made a hard rule |  |
| R10 | Reconciliation across five separately-exported zips found a genuine fork: `Emivita_edit_medicine_reminder_batch` and `Emivita_rename_and_palette_batch (3)` both unpack to the same folder name but diverged in 17 shared files (schema v1→v2, `sortOrder`/`barcodeValue`/`indication`/`storageNote`, `RepeatConfig`, nullable `DoseEvent.reminderId`, the `addTag()` transaction fix (R1), the 4 new screens, etc.) | ✅ Resolved — confirmed the edit-medicine-reminder branch is a strict forward evolution of the rename/palette branch (nothing in the older branch's versions of those 17 files was missing from the newer branch), so it was taken as the base. The older branch, `MedKeeper_scheduling_engine_batch` (pre-rename ancestor), and `Emivita_missing_resources_patch` were all fully superseded — nothing in any of them was absent from the base — and are retired from the working tree as of this pass. Only `Emivita_launcher_icon_patch`'s hdpi/mdpi foreground PNGs (§7 row above) contributed anything the base didn't already have. |  |
| R11 | `EmivitaDatabase` declares `exportSchema = true` (needed for Room migration testing) but no `app/schemas/` directory exists in any of the reconciled zips, and `app/build.gradle.kts` has no `room { schemaDirectory(...) }` block pointing Room's annotation processor anywhere | ✅ Resolved — the `ksp { arg("room.schemaLocation", ...) }` wiring turned out to already be in place (this row's premise was stale by the time it was actually checked); running a build with it confirmed `app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase/{2,3}.json` now export for real (no `1.json` — this source tree never compiled `EmivitaDatabase` at version 1, so there was never a schema for Room to have captured at that version). The part that was actually missing — real test coverage exercising the `Migration` objects, not just a baseline sitting unused — is closed by `MigrationFrom1To2Test`/`MigrationFrom2To3Test` (`app/src/test/java/.../core/data/db/`): both build the "before" table shape from raw SQL and run the real `Migrations.MIGRATION_1_2`/`MIGRATION_2_3` objects against it, asserting on the transformed data (colorArgb→hex incl. the negative-Int bitmask case, sortOrder backfill, denormalized `dose_events.medicineId`, the new nullable column). `MigrationTestHelper` (schema-JSON-diff-based, the more rigorous option for MIGRATION_2_3 specifically, since both its endpoints' schemas exist) was tried first and dropped — it hit a Room 2.8.4/Robolectric driver bug (`IllegalArgumentException: This driver is configured to open a database named 'X' but '<path>/X' was requested`, thrown before any migration logic runs) unrelated to this app's schema; the direct-fixture technique used instead is a real regression test either way, just not diffed against the exported JSON. Both tests run and pass under `./gradlew testDebugUnitTest`. The one thing this row's coverage structurally cannot address — an in-place upgrade of a database written by a real previous build — is tracked in §9 #1, and is no longer blocked: a signed versionCode 1 APK is archived at `dist/vc1/`. |  |
| R12 | `README.md` still described the pre-#1 (data-layer-only) batch — "not built yet: PRN, projection cache, History, low-stock/expiration alerts, backup/restore, biometric lock UI" — directly contradicting §9's ✅ marks on items 2/4/6/8/9/11, the exact stale-docs pattern R6 already named once (found during a routine "what's next by dependency" pass, not a code review) | ✅ Resolved — before rewriting, verified against the actual source rather than trusting either doc: every claimed-✅ §9 item (1–16) really is implemented with working logic, not a stub, and #13 (Health Connect) is genuinely absent. `README.md` regenerated to match. Same pass also removed a harmless but stale leftover: `app/proguard-rules.pro` still `-keep`/`-dontwarn`'d `androidx.health.connect.client.**`, a class no longer on the classpath since #13 was dropped |  |
| R19 | §8's closing paragraph still read "Not built — literal placeholder text, no data-entry UI exists at all: `TodayScreen`, `MedicinesScreen`, `HistoryScreen`" — flatly false since #1 shipped, and the third recurrence of the pattern R6 and R12 already named (found while advising on next steps, not by a doc review). It survived the reconciliation pass *and* R12's README rewrite because R12 fixed the copy in `README.md` and left the copy in §8 | ✅ Resolved — and treated as a structural defect rather than another stale paragraph: the recurrence is caused by §8 and §9 both recording build status, so every batch has two places to update and reliably updates one. §8 is now explicitly not a status ledger (§9's checkmarks are), keeping only the per-screen detail §9's one-line entries cannot carry. Same pass corrected §9 #1's "no v1-era APK left to install-then-upgrade from" and `README.md`'s "this zip is not a buildable Gradle project" and "no archived v1 APK" claims, all three falsified by this session's work |  |
| R13 | §5/§9 #6 called for a Vico "pie + stacked bar" adherence chart, but the pinned `com.patrykandpatrick.vico:compose-m3:2.1.0` dependency has no pie chart at all — Vico 2.x's cartesian module ships line/column/candlestick layers only; the pie layer existed in Vico 1.x and was dropped in the 2.x rewrite (found by extracting and inspecting the resolved `.aar`'s `classes.jar` directly, not assumed from the library's reputation) | ✅ Resolved — built the one shape the pinned version actually supports and the one that fits the data better anyway: a stacked daily bar (taken/skipped/missed per day, `ColumnCartesianLayer` + `MergeMode.stacked()`) reading as a real trend over the visible window, not a single-window aggregate a pie would give. Matches T5's own "History's data calls for a trend-over-time register" finding more directly than a pie would have. `AdherenceChart.kt` (`feature/history/`), wired into `HistoryScreen.kt` between the adherence-summary card and the entry list, hidden when every day in the window has zero taken/skipped/missed. Colors: Taken=`primary`, Skipped=`tertiary`, Missed=`error` (a fired-and-unresolved dose is the one outcome worse than a deliberate Skip, so it's the one that gets the app's reserved alarm-adjacent color) — all resolved through `MaterialTheme.colorScheme`, so the chart repalettes correctly across all 8 palettes/both theme modes with zero chart-specific color code. `app/build.gradle.kts`'s stale "pie + stacked bar" comment corrected to describe what's actually available and actually built. |  |
| R18 | User requested a dose-measurement label next to every dose amount, on the same device-testing session — assessed via `architecture-designer` before implementing (user explicitly asked for a skill-backed assessment) rather than guessed at, since the obvious shortcut (reuse the existing `Medicine.stockInfo.unit`) turned out to already be overloaded elsewhere as a stock-tracking-enabled flag (confirmed by reading `DoseEventRepository`/`LowStockExpirationAlertEvaluator`/`OccurrenceProjectionCache`'s actual conditionals, not assumed) | ✅ Resolved — new, separate `Medicine.doseUnit` field (§3), `MIGRATION_3_4`, threaded through 6 entry points and 4 display points (§3's own entry lists them). Full test suite + `assembleDebug` pass; device-verified end-to-end on the OnePlus 6T (Add Medicine's Measurement field, a reminder form's suffix hint, the Log dose dialog's suffix hint, and the resulting text on Today/History List/History Table). |  |
| R17 | Launcher-icon foreground artwork (clock + two capsules + "zzz" sketch, `ic_launcher_foreground.png`, all 5 densities) extended to ~67% of the adaptive-icon canvas's half-width from center — past the standard 61% (66dp/108dp) adaptive-icon safe-zone guideline, and visibly clipped by `core-splashscreen`'s own circular icon mask (used on this minSdk-30 device since it's below the real platform SplashScreen API at 31+) — confirmed by screenshotting the actual splash on the OnePlus 6T mid-launch, not assumed; user flagged the visible clipping | ✅ Resolved — content rescaled/recentered (measured via the PNG's own alpha channel, not eyeballed) to ~50% of canvas half-width, a safety margin below even the 61% guideline, then all 5 density PNGs regenerated by downscaling the corrected xxxhdpi source (not independently shrinking each existing density, to avoid compounding per-density rounding). No shape swap attempted — confirmed there's no supported `windowSplashScreenIconShape`-equivalent attribute; `core-splashscreen`'s compat path hardcodes a circular mask, so the fix is designing the art to fit it, the same discipline adaptive icons already require for launcher-mask compatibility. Device-verified: relaunched on the OnePlus 6T, clock/both bells/the zzz mark/both capsules all now fully visible with clear margin, confirmed via screenshot. |  |
| R16 | `com.kizitonwose.calendar:compose`'s current latest (2.10.1) is compiled with a Kotlin compiler whose metadata this project's pinned Kotlin 2.1.0 plugin (root `build.gradle.kts`) can't read — `kspDebugKotlin` failed with "Module was compiled with an incompatible version of Kotlin... binary version... 2.3.0, expected... 2.1.0," confirmed by actually trying 2.10.1 against this build, not assumed from a changelog | ✅ Resolved — pinned to `2.7.0` instead (§9 #6, this batch), the newest release confirmed to compile clean against this project's Kotlin 2.1.0; `compileDebugKotlin`, the full `testDebugUnitTest` suite, and `assembleDebug` all pass. Revisit the pin once the project's own Kotlin version moves (§1's toolchain table), not before |  |
| R15 | §4's "Deliberately not built yet" list (projection cache, the five §3 gap-resolution fields, `CyclicActivePause`/`LinkedFollowing`/weekend-mode + both cascade-reschedule trigger points) was still describing all of it as unbuilt, directly contradicting §9 #4/#14's own ✅ marks and the actual source (`OccurrenceProjectionCache.kt`, `SchedulingEngine.kt`, `AddReminderScreen.kt`'s 4-card picker) — the same stale-docs pattern R6/R12 already named, found during this session's "what's next by dependency" pass | ✅ Resolved — §4 rewritten to state plainly that list is stale and point at the real implementation; only the two genuinely-still-open items (`AlarmActivity` visual design, boot-reschedule on-device confirmation) kept as open |  |
| R14 | Full audit pass (`android-app-auditor` + `code-reviewer` + `code-verification-core`, this session) — a systematic security/permission/manifest/build/logic sweep of the whole app, not a diff review. Full agent-report detail lives in this session's transcript, not reproduced here; this row is the durable record. | ✅ Mostly resolved, a few items deliberately left for a follow-up decision (see below). **Fixed this pass:** (1) **CRITICAL** — `POST_NOTIFICATIONS` was declared and correctly gated with `checkSelfPermission` before every post, but *never actually requested anywhere* — on a fresh Android 13+ install the permission defaults to denied and every reminder notification would silently never show, for an app whose entire purpose is showing them. Fixed with a one-time request (`EmivitaApp.kt`'s new `NotificationPermissionEffect`, tracked via a new `SettingsRepository.notificationPermissionRequested` flag so it asks exactly once, no re-nagging). (2) **CRITICAL/HIGH, doubly confirmed by two independent review passes** — `AppLockGate` defaulted its DataStore-backed `lockEnabled` read to `false` (`collectAsState(initial = false)`), so on cold start, with the lock genuinely on, the app rendered full unlocked content for the composition frames before the real setting loaded. Fixed: nullable-tri-state read, blank loading frame instead of `content()` while unknown. (3) `BiometricAuthenticator.availability()`'s implicit `else -> NO_HARDWARE` bucketed real transient-error codes together with genuine no-hardware/not-enrolled without that being a conscious choice; made explicit (same fail-open outcome, now a reasoned one — no PIN fallback exists, so refusing to fail open on these codes would strand the user with no recourse) — and `AppLockGate`'s own availability check moved off a `remember(activity)`-cached-for-the-Activity's-whole-lifetime pattern onto one that re-checks every lock cycle. (4) `DoseEventRepository.resolve()`/`switchStatus()` read-validated their target event *before* opening the transaction, not inside it — two near-simultaneous calls (double-tap, notification action racing an in-app tap) could double-decrement stock for one real dose; both now read/validate/write atomically inside one transaction. (5) `DoseGroupRepository.confirmGroup()`/`disbandGroup()` had no transaction at all around their multi-row insert-then-loop-update sequences; now wrapped in `database.withTransaction {}`, matching every other repository. (6) `DoseEventRepository.materializeIfAbsent()` returned Room's raw `@Insert(onConflict=IGNORE)` result — `-1`, not `null`, on a suppressed conflict — straight to callers expecting "null means already materialized," so a real (if narrow) race on the alarm-materialization unique index misrouted into `ReminderAlarmReceiver`'s error-log branch instead of its intended "duplicate delivery, re-show existing notification" path; now translates `-1` to `null` at the source. (7) `applyWeekendMode()` assumed `weekendStart < weekendEnd`; an overnight window (e.g. 22:00–06:00 — nothing in the `LocalTime` pickers prevented configuring one) silently never triggered the push in either half of the window; now handles the midnight-spanning case explicitly. (8) All four `BroadcastReceiver`s did an unguarded `context.applicationContext as EmivitaApplication` synchronously in `onReceive()`, before `goAsync()`/the coroutine's own try/catch exist to catch anything; now a safe cast that logs and drops just that one event instead of risking a process crash. (9) `AutoSyncManager` deleted the existing auto-sync backup *before* confirming the replacement wrote successfully — a `createFile`/`openOutputStream` failure partway through left zero backup files on disk; now writes to a staging name first and only swaps it in after a confirmed-complete copy, and write failures are now logged instead of silently swallowed. (10) `BackupZipWriter.read()` only caught zip4j's own `ZipException`; a genuinely non-zip/truncated input file could throw a different exception type and crash restore instead of resolving to `Corrupt`; broadened. (11) `Migrations.MIGRATION_1_2`'s `dose_events` backfill computed `medicineId` via a correlated subquery that returns `NULL` for any row whose `reminderId` doesn't resolve (an orphaned v1 row) — `NOT NULL` on the new column would abort the whole migration on such a row; added a `WHERE reminderId IN (SELECT id FROM reminders)` filter (safe: v1's `dose_events.reminderId` was itself `NOT NULL`, so this can't accidentally drop a legitimate PRN row). (12) Three genuinely unused permissions removed from the manifest per least-privilege (PERM-001) — `WAKE_LOCK`, `FOREGROUND_SERVICE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — confirmed by grep that nothing in the codebase ever calls the corresponding APIs; the receivers' correct `goAsync()` usage (verified this same pass) is what makes them genuinely unneeded, not an oversight. All fixes covered by the existing test suite (`./gradlew testDebugUnitTest`, all green) plus a full `assembleDebug`; no regressions. **Follow-up pass (same session, "apply the remaining fixes by dependency"):** closed out everything flagged above except one deliberately-left nuance. In dependency order — foundation before consumer: (a) `AutoSyncPassphraseStore.kt`'s `get()`/`save()` rewritten to convert CharArray<->ByteArray directly via `CharsetEncoder`/`CharsetDecoder` (`charsToBytes`/`bytesToChars`), never routing through an un-wipeable `String`, with every intermediate buffer explicitly zeroed (`Arrays.fill`) in a `finally`; `BackupSyncScreen.kt`'s `exportPassword` field now clears itself after use, matching `restorePasswordInput`/`syncPassphraseInput`'s existing behavior (the genuine limit here, stated plainly rather than overclaimed: Compose's `OutlinedTextField` is String-backed by the Android IME pipeline itself, so a password typed through a standard text field cannot avoid materializing *a* String at the framework level regardless of what type the app's own state holds — minimizing that String's lifetime is the realistic ceiling, not eliminating it). (b) The save-button-stays-disabled-forever bug fixed in the three files that actually had it (`AddMedicineScreen.kt`'s `MedicineFormBody`, `TodayScreen.kt`'s `LogDoseDialog`, `DoseEditDialog.kt`) — each save now wraps its suspend call in try/catch, resets `saving = false` on failure, and shows an inline error `Text` in the app's existing error-color convention. (`AddReminderScreen.kt` turned out not to share this bug on closer inspection — its `saveEnabled` is plain form-validity, not a saving-in-progress flag, so there was nothing to fix there.) (c) BUILD-014 closed: `app/build.gradle.kts` now pins `kotlin { jvmToolchain(21) }`, distinct from the `sourceCompatibility`/`jvmTarget` = 17 pair (compiled-bytecode target, unchanged) — verified safe before keeping it, not assumed: `./gradlew --offline --rerun-tasks compileDebugKotlin` and a full `--offline testDebugUnitTest assembleDebug` both passed, confirming Gradle resolves the JDK 21 toolchain from what's already installed without needing network access in this environment. Two dead Android-Studio-template files (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` — inert since `allowBackup="false"`), initially blocked by this session's own permission classifier, were removed in this pass too; `assembleDebug` confirmed clean afterward. **Left as-is, on purpose:** the MEDIUM/LOW-confidence nuance where a snooze re-fire re-runs the same cascade-reschedule side effects as a genuine first occurrence in `ReminderAlarmReceiver.handleAlarm` — currently harmless for every reminder type that's actually deterministic, and the fix (gating the cascade on "was this materialization actually new") is more invasive than the rest of this list, better done deliberately than folded into a cleanup pass. Also newly documented, not a defect: `androidx.work` (WorkManager) is *not* fully absent from the APK despite R5/§9 item 0's "dependency removed" framing — `androidx.glance:glance-appwidget` pulls in `androidx.work:work-runtime(-ktx):2.7.1` transitively for its own internal widget-session management (confirmed via `./gradlew app:dependencies`), which is what `ACCESS_NETWORK_STATE` in the merged manifest turned out to be too (WorkManager's own manifest declares it). This doesn't reopen R5 — the app's own code still uses zero WorkManager, and R5's actual concern (avoiding a second background-work paradigm for the app's *own* debounce-driven cache/sync logic) still holds — but "no WorkManager, no Health Connect" in `README.md`/§1's dependency list overclaims for the first half; worth a wording pass next time that file is open. | **R20, R21** — two items overturned |
| R20 | `res/xml/data_extraction_rules.xml` was deleted as a dead template file (R14's follow-up pass: "inert since `allowBackup=\"false\"`"). True for cloud backup, not for device-to-device transfer. Checked against Android's own backup documentation rather than reasoned from memory: for apps targeting Android 12+ — this app targets 36 — "on devices from some device manufacturers, specifying `android:allowBackup=\"false\"` disables cloud-based backup and restore (such as Google Drive backups) but doesn't disable device-to-device transfers for the app." So on those OEMs the app's files are still eligible to be carried to a new phone, with nothing in the project expressing an opinion about it. Found while verifying the pre-reconciliation batches were safe to delete — those two res/xml files were the only content in any of them absent from the reconciled tree | ✅ **Resolved this pass.** The exposure was never a data leak: the SQLCipher passphrase is wrapped by a non-exportable `AndroidKeyStore` key (§2, `PassphraseProvider`), which by design does not travel. A D2D transfer therefore lands ciphertext on the new device that nothing there can ever decrypt — the failure mode is a database that cannot be opened on first launch, not readable data in the wrong hands. That is worse than a clean empty install, and it bypasses the app's own intended migration path (§9 #9's SAF backup/restore, which carries its own password and works precisely because it is not Keystore-bound). Fixed: a real `res/xml/data_extraction_rules.xml` (written, not restored — the file R14 removed was the unmodified Studio stub with every rule commented out, and is still at `git show 19a7f19:docs/retired/data_extraction_rules.xml` if the original is ever wanted), excluding `root`/`file`/`database`/`sharedpref`/`external` from **both** `<device-transfer>` and `<cloud-backup>`, wired up with `android:dataExtractionRules` on `<application>`. The cloud half is deliberately spelled out rather than left to `allowBackup="false"`, so flipping that flag later cannot silently re-enable cloud extraction. Verified rather than assumed applied: `assembleDebug` and `lintVitalRelease` both pass, the attribute reaches the merged manifest, and `res/xml/data_extraction_rules.xml` is present in the packaged APK. **What stays unverified, and cannot be closed from this machine:** the documentation says "some device manufacturers", so whether the OnePlus 6T would have transferred anything in the first place is unknown, and no D2D transfer has been attempted before or after. This row fixes a documented behavior, not an observed one — the rules file is correct either way, but nobody has watched it prevent anything | **R22** — scope bounded |
| R21 | Second full audit pass (`android-app-auditor` registry + Android Lint + a line-by-line read of all ~12,700 lines), run against the reconciled tree rather than a diff. Eleven commits, `e03be7f`-`4ac7d3b` inclusive. Same shape as R14 and, like R14, the durable record is this row rather than the transcript | ✅ Resolved, with two items left as decisions and two needing a device. **Supersedes R14 item 12.** That row records `WAKE_LOCK`/`FOREGROUND_SERVICE`/`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as removed per least-privilege, "confirmed by grep". The grep was right about the app's own code and the fix did not work: deleting a `<uses-permission>` line removes it from *this* manifest only, and the manifest merger put `WAKE_LOCK` and `FOREGROUND_SERVICE` straight back from `androidx.work` (transitive via `glance-appwidget`, the same route R14's own follow-up note already identified for `ACCESS_NETWORK_STATE`). Only the battery-optimizations one had actually gone. Verified by reading `app/build/intermediates/merged_manifest/`, which carried nine permissions against the five declared. Now genuinely removed via `tools:node="remove"` — four of them, adding `USE_FINGERPRINT` (from `androidx.biometric`, deprecated since API 28, dead at minSdk 30) — and confirmed in both the debug *and* release merged manifests, which now carry exactly the five. The general lesson, worth more than the fix: a permission claim is only checkable against the merged manifest, never against the source one. **CRITICAL, and structurally identical to R14's `POST_NOTIFICATIONS` finding:** `SCHEDULE_EXACT_ALARM` was declared and read (`AlarmScheduler.canScheduleExact()`) but never requested anywhere — `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` appeared exactly once in the whole codebase, inside a KDoc promising a Settings prompt that was never built. From Android 14 the permission is not granted at install to an app targeting 34+, so on a fresh install of the release build every reminder, nag and snooze silently took the inexact, Doze-batched branch, leaving only a `Log.w`. For a medication-adherence app that is the failure the app exists to prevent. Fixed by declaring `USE_EXACT_ALARM` (install-granted, no user interaction, no Kotlin change) and capping `SCHEDULE_EXACT_ALARM` at API 32. **Also fixed:** the one Android Lint *error* — `Locale.getDefault()` read inside a composable, so History's calendar headers never followed a locale change; whole-database JSON serialization, AES-256 zip encryption and SAF stream copies running on the main thread in History's CSV/PDF export and both of Backup & Sync's flows (`rememberCoroutineScope()` dispatches on Main; `AutoSyncManager` already had this right); `Today`/`History` computing their date windows with a keyless `remember`, so a screen left open across midnight kept rendering the previous day (five further derived values were keyed on stale inputs and had to move with it); `AutoSyncPassphraseStore`'s `CharArray` contract being ignored by its only caller, plus three more unwiped password arrays in `BackupSyncScreen`; and weekend-mode preferences being re-read off DataStore per projected occurrence inside a loop bounded at 500 per reminder per recompute. **Removed as dead, each verified unreferenced first:** `asOnSurfaceSafe` (whose KDoc claimed it was used by the alarm screen and notification buttons — it never was), `supportsDynamicColor`, `NoOpSchedulingCoordinator`, `src/main/keepRules/rules.keep` (commented-out WebView boilerplate, and inert regardless — that is not an AGP application-module source path), an orphaned `ic_launcher_round.xml`, both New-Project template tests and the three `androidTestImplementation` entries they were the only users of, six unreferenced DAO/repository members, and nine unused imports. **Comments corrected:** six said a schema field was "not read yet" when all six were read (`autoResolve`, `repeatOnUnanswered`, `activeFrom`/`activeUntilEpochDay`, the stock sentinels, `repeatCount`, `remindersLockedUntilTaken`), and four contradicted the artifact — including two files still describing medicine deletion as an open cascade-vs-archive question after it shipped cascading. Development chronology ("this batch", "previously unbuilt") stripped; every SPEC reference and design rationale kept. **Also:** seven duplicate number formatters collapsed to one, six hardcoded neutral-accent literals to one constant, `DetailHeader` and the POST_NOTIFICATIONS guard each written twice to once; `Theme.kt` 448 → 302 lines (all 352 colour assignments proven identical to the previous revision before and after, since it is not otherwise testable); SQLCipher 4.16.0 → 4.17.0. Lint went 1 error / 39 warnings / 15 hints → 0 / 32 / 1. **Left as decisions, not defects:** the shared-keystore exposure (see below) and whether to take the remaining dependency bumps. **Not verifiable from this machine, and the two things to check on a device before trusting this build:** that the home-screen widgets still update now that `WAKE_LOCK`/`FOREGROUND_SERVICE` are gone (Glance's session manager is the real WorkManager consumer — this is the one genuine regression risk in the ten commits, and `AndroidManifest.xml` records what to restore if it bites), and that an existing 4.16.0-created database still opens under SQLCipher 4.17.0 — no test touches SQLCipher at all, because the Room tests deliberately build an unencrypted in-memory database. |  |
| R22 | `SIGN-001` — the release-signing comment understated its own consequence. It read "anyone could build an APK that installs over this one", which reads as a nuisance | ✅ Documented, deliberately not "fixed". AndroidKeyStore aliases are scoped to (package name, signing identity) and survive an in-place upgrade. The debug keystore is committed and its password is the published Android default, so anyone holding this repository can build an APK Android accepts as an *upgrade* — with no signature-mismatch prompt, since an identical signer is the entire point of sharing the identity — which inherits the data directory **and** the `emivita_db_wrap_key`/`emivita_autosync_wrap_key` aliases, and can therefore read out the SQLCipher key and the auto-sync backup password in plaintext. So the at-rest design is in practice only as strong as control of `keystores/my-shared-debug.keystore`: it still holds against the threat it was built for (a lost device reading the raw `.db`), and does not hold against installing a build someone else produced. This also bounds R20: `data_extraction_rules.xml`'s "arrives as ciphertext the new install can never open" is true for a device-to-device transfer and false for an upgrade install under this identity. The configuration is unchanged, because for the current threat model — the owner's own installs on the owner's own devices — the existing trade is still the right one. `app/build.gradle.kts` now states the real scope and lists the two fixes in preference order (a separate release keystore read from a gitignored `keystore.properties`; or `setUserAuthenticationRequired(true)` on the auto-sync wrap key) for whenever this reaches a second person. Related: `.gitignore` now ignores `*.keystore` by extension and re-admits the debug key by name, so a future `keystores/prod.keystore` cannot be committed by being differently named. |  |
| R23 | Third full audit pass, run against the reconciled tree after R21 rather than as a diff review. The method differed from R14/R21 deliberately, because two passes had already swept the obvious layer: 22 parallel readers covering every file, 6 cross-cutting sweeps (deactivated code, dead symbols, comment-claim verification, duplication, concurrency, dev workflow), and a separate 4-reader fleet for documentation-versus-code, with every finding then put to an adversarial verifier and every behavioural one to three diverse lenses (correctness, author-intent, regression-risk) instructed to refute rather than agree. 264 agents, 250 raw findings, 177 surviving. One commit, `24e0fc2` -- this row's own reference to it necessarily landed in the follow-up commit after it. Crucially it was **not** a reading-only pass: `assembleDebug`, `lintDebug` and `testDebugUnitTest` were actually run, which is the only reason the backup-rules item below was caught. The thesis, borne out: what survives two audits is not sloppiness but defects living in the *seams* between two individually-correct pieces of code | ✅ Resolved, with four items left as decisions and one structural gap named but not closed. **CRITICAL, and the most user-visible defect ever recorded in this ledger: editing a reminder silently did nothing.** All four edit paths in `AddReminderScreen` called `updateConfig(current, ...)` and then `updateAdvancedFields(current, ...)`, passing the *same pre-edit* `Reminder` to both. `ReminderDao.update` is a plain Room `@Update`, which writes every column, so the second call restored the pre-edit `configJson` over the first call's. Changing a reminder's time or pattern ran a success path and kept the old schedule; the `LinkedFollowing` path reverted `parentReminderId` the same way. `StockExpirationScreen` had the identical shape twice (`updateConfig` then `setEnabled`). Fixed by collapsing each into one write via a new `ReminderRepository.updateConfigAndAdvancedFields()` -- two full-row writes describing one edit is what made this recurrable, so the fix is the single `copy()`, not a re-ordering. **CRITICAL: three form paths committed a config the scheduler then threw on, permanently.** `"0".toIntOrNull()` is `0`, not null, so Every-N-days' `?: 2` fallback never fired and `saveEnabled` gated patterns 1 and 3 but not 2; `RepeatingInterval` and `DailyWindow` guarded raw hours `> 0` while storing `(hours * 60).toInt()`, so 0.01h stored 0 minutes. `ReminderRepository.create` inserts before notifying the coordinator, so the bad row was already committed when `matchingDateOnOrAfter` and the interval calculators hit their `require(... >= 1)` -- thrown on a `Dispatchers.Default` scope with no `CoroutineExceptionHandler`, and re-entered on *every* projection recompute thereafter. Fixed on both sides: the three forms now validate the value they actually store, and `matchingDateOnOrAfter` returns null for `n < 1` instead of throwing, which is what its own KDoc already promised ("a UI validation bug if it ever happens, not something this function should crash over") while the code nine lines below it contradicted that. **Low-stock alerts fed their own trigger.** `ALWAYS_WHEN_BELOW` wrote `lastAlertNotifiedEpochDay` unconditionally; that UPDATE lands on `reminders`, one of the two tables `observeAllWithReminders()` re-fires on, which invalidates the projection cache, whose recompute calls the evaluator again -- self-sustaining at the cache's own 1s debounce for as long as any medicine sat below threshold, re-posting the notification each pass. The class doc asserted the opposite in as many words ("this can't become unbounded spam"). The write is now guarded exactly like the rearm path above it already was. **Saving Weekend Mode did nothing until the next reboot:** four DataStore writes then `onBack()`, and nothing observes those keys -- `rescheduleAll()` ran only from the boot receiver and backup restore, and the projection cache watches tables, not DataStore. It now reschedules before `onBack()`, before rather than after because dismissing the sheet cancels the composition scope mid-flight. **The first-launch database key was written with `apply()`** and then immediately used to create `emivita.db`. A crash before the async flush would leave a database the next launch could never open, with no `fallbackToDestructiveMigration` anywhere -- an unrecoverable brick caused by a lost preference write. Now `commit = true`; `AutoSyncPassphraseStore` deliberately stays on `apply()`, where a lost write costs nothing worse than a re-prompt. **Backup export reported success after writing zero bytes** (`openOutputStream(uri)?.use { }` -- a null stream made the block a no-op that fell through to "Backup exported."), while restore and auto-sync both already treated the same null as an error. **Also fixed:** `getMostRecentForMedicine` had no status filter, so the first Log dose after any refill suggested the pack size -- `logRefill` writes the restock quantity into the same `doseAmount` column and a restock is by construction the newest row; and Linked Following's parent picker offered Low-Stock/Expiration reminders, which `supportedTypes` excludes and which therefore never produce a `DoseEvent`, making them an anchor that can never resolve and, being oldest-first, a selectable default parent. **Backup rules had a version hole that R20's own reasoning denied, and only a real Lint run surfaced it.** R20 wrote `data_extraction_rules.xml` and argued that spelling both sections out meant "flipping that flag later cannot silently re enable cloud extraction". `android:dataExtractionRules` is ignored below API 31 and this app's minSdk is 30, so on Android 11 that sentence was false: the flag flip it defends against would have re-enabled an unfiltered cloud backup. Android Lint says precisely this as a `DataExtractionRules` warning on `<application>`, which had been sitting unread in the report. Fixed with `res/xml/backup_rules.xml` (the same exclude list in the pre-Android-12 `<full-backup-content>` format) wired via `android:fullBackupContent`, and the false sentence corrected in place. Verified the way R20 asks rather than assumed: the warning is gone, the attribute reaches the merged manifest, and the file is packaged in the APK. Bounds R20 further than R22 did. **Removed as dead, each grep-verified across `app/src`, `res/`, the manifest and the tests first:** four unused imports R21's sweep missed -- including `EmivitaNavHost`'s byte-identical *duplicate* `Icon` import, which a usage-based sweep structurally cannot catch because the symbol *is* used -- plus `DoseEventDao.insertAll` (no call site; its referring comment retargeted to `insert`), the `ReminderDao` injected into `DoseEventRepository` and never read, `NextReminderRow.unit` (populated, never rendered), and an orphaned duplicate KDoc block stacked above `colorSchemeFor`, an earlier draft of the block below it that documents nothing and is the closest thing to commented-out code left in the tree. **Comments corrected**, beyond those already named: `ColorPickerDialog`'s KDoc described the inverse of its own code -- it said the square resolves hue+saturation by "polar mapping" and the strip controls lightness, where the square sets saturation/value by plain linear mapping and the strip sets hue, and the file's own inline comment 30 lines below had it right all along; `EmivitaRoute` called `DataBackupSettings` "the one Settings destination" warranting a real route, contradicted by two more route objects four lines below it; `SchedulingEngine`'s orphaned-`LinkedFollowing` branch said "shouldn't happen" about the exact state its own FK is designed to produce; plus references to symbols that do not exist (`notificationPermissionGate`, `[isCyclicActiveOn]`, `ProfileDao.rename`), a default documented as HIGH that is ALARM, and a count of three reminder types that is four. **11 `docs/SPEC.md` sites corrected** where the source of truth had gone stale against shipped code: §3's lock-until-taken "nothing currently reads this field at all" (four sites read it), §8's claim that all three main screens read through the projection cache (only Today does), §6's Today-timeline and `CyclicActivePause` "not yet built" markers, §9 #14's "what's left" list (all built), §9 #15's "Only Data & Backup pushes a real route" (three do), §9 #3's swipe-to-edit (shipped as tap, deliberately), §2's "future `AppWidgetProvider`" and its XML-views exception, the colour-picker wording in three places, and §8's Appearance paragraph still comparing itself to deleted onboarding. README's step 4 claimed the daemon JDK is "still an IDE/machine-local setting Gradle has no supported way to pin" while `gradle/gradle-daemon-jvm.properties` is tracked, pins it, and `app/build.gradle.kts` says so in as many words. `dist/vc1/MANIFEST.md` named a schema path that does not exist and, read on its own, invited exactly the versionCode-1-means-schema-1 confusion §9 #1 spells out a caveat against; both corrected. **Left as decisions, not defects:** (a) `Reminder.parentReminderId`'s FK is `ON DELETE SET_NULL` while §3 specifies `ON DELETE CASCADE`, and the entity comment cites §3 for the rule §3 does not state -- schema 4 already records SET_NULL, so changing it costs a migration. It is unreachable today only because `ReminderRepository.delete()` has no call sites at all; wiring a per-reminder delete makes orphaned, still-enabled, never-firing children reachable, which is why that branch now documents the hazard instead of asserting it cannot occur. (b) `switchStatus()` writes an absolute recorded stock quantity, discarding intervening changes -- documented as deliberate in four places, so recorded here rather than "fixed" against its own spec. (c) Today's 2-day window is the only `requestWindow` registrant and therefore shrinks the shared projection the stock consumers read. (d) `DoseGroupRepository` is constructed nowhere and its five methods are unreachable. **Named, not closed -- the structural gap:** 12 tests, all Room, none touching `SchedulingEngine`/`ReminderScheduling`'s ~650 lines of pure, Android-free date math. That is exactly where this pass and R21 both found real bugs by reading, and it is the most testable code in the app -- no Android dependencies, no mocking required. There is also no CI and no lint gate, so R21's hand-won 0-error state is a snapshot rather than an invariant, as this pass demonstrated by finding a warning that had been sitting unread in the report. **Verified, not assumed:** `assembleDebug`, `lintDebug` and `testDebugUnitTest` all green after the changes, 12/12 tests passing on a forced full rerun, 0 lint errors. **Still needs a device, unchanged from R21:** the widget-update and SQLCipher-upgrade checks. |  |
| R24 | R23's one **named-but-not-closed structural gap**: 12 tests, all Room, none touching `SchedulingEngine`/`ReminderScheduling`'s ~650 lines of pure, Android-free date math -- the most testable code in the app and, by two audits' evidence, where the real bugs live. Closed here rather than by another reading pass, on the reasoning that a third read of the same lines was the lower-yield move | ✅ Resolved. 59 new tests (`ReminderSchedulingTest`, 41; `SchedulingEngineTest`, 18), suite 12 -> 71, all green. The pure-calculator suite is plain JUnit with no Robolectric runner and no mocking, which is the whole point of that file being Android-free; the engine suite mocks only its two collaborators (`DoseEventDao`, `SettingsRepository`). Both run in `Europe/Rome`, deliberately not UTC -- a UTC-only suite structurally cannot catch an offset bug -- on a calendar chosen so weekdays read off the constant (2026-06-01 is a Monday). **The suite found a defect on its first run, and it is R23's own defect one branch further down.** R23 fixed `matchingDateOnOrAfter`'s `EveryNDays` case, replacing `require(n >= 1)` with a null return because the row is committed before the scheduler ever sees it and the projection cache re-reads every reminder on a loop, so one bad row threw on *every* recompute rather than at the point of entry. The `CyclicActivePause` branch immediately below it still called `isActiveOn`, which carries `require(activeDays >= 1 && pauseDays >= 0)`, for any cycle length >= 1: `activeDays = 0, pauseDays = 7` and `activeDays = 2, pauseDays = -1` both raised `IllegalArgumentException` from inside the recompute loop. (`activeDays = 0, pauseDays = 0` was already safe, via the empty `0 until 0` range -- pinned so it stays that way.) Reachability is R23's own argument verbatim: `AddReminderScreen` gates both fields, but `BackupRepository.restoreAll()` writes reminder rows straight back with no config revalidation. Fixed identically -- a null return in the scheduler's branch, leaving `isActiveOn`'s `require` intact as the contract for direct callers -- so the guard is now symmetric across all three fallible patterns instead of two of three. The lesson is narrower than R23's and worth keeping: fixing a defect *class* one instance at a time leaves the siblings, and only a test that enumerates the cases finds them. **Otherwise the math held.** The other 58 passed unmodified, including the cases written specifically to break it: both sides of midnight on an overnight weekend window, a previous day's resolution leaking into today's `DailyWindow` grid, a late-firing interval dragging its own grid, and the empty-day-set and end-before-start degenerate configs. **Three behaviours pinned rather than judged**, each defensible, each now expensive to change by accident: a `FixedTime` slot inside the spring-forward gap resolves to the first real instant (02:30 -> 03:30 on 2026-03-29) rather than being skipped for the day; a 24h `RepeatingInterval` is elapsed-time not wall-clock math, so it lands an hour later on the clock across a DST boundary; and weekend mode is applied *after* the `activeFrom`/`activeUntil` clamp per §4's stated ordering, so an overnight push can put a dose one calendar day past `activeUntil`. Only the third is arguable -- ordering the clamp last would instead drop the dose entirely, which is not obviously better -- and it is recorded as observed behaviour, not endorsed. **Still open, unchanged from R23:** no CI and no lint gate, so green remains a snapshot rather than an invariant; and R23's four decision items (the `parentReminderId` FK contradiction, `switchStatus()`'s absolute stock write, Today's window shrinking the shared projection, unreachable `DoseGroupRepository`) are untouched here. The orphaned-`LinkedFollowing` path the FK question governs is now pinned by a test, so settling that decision has a regression check waiting for it. Shipped in `24ba0de`, one commit shared with R25 -- and as with R23, this row's own reference to it necessarily landed in the follow-up commit after. |  |
| R25 | R23's decision item (a): `Reminder.parentReminderId`'s FK declares `ON DELETE SET_NULL`, schema 4 records it, and §3 specified `ON DELETE CASCADE` -- a contradiction R23 named but deliberately left open, because aligning code to spec costs a migration. Settled here. The deciding fact was not the migration cost but that **both sides justified themselves with a premise untrue of the shipped code**: §3 specified CASCADE because it matched "how the chain is nested in the UI", and `MedicineDetailScreen` renders a flat `LazyColumn`, one row per reminder labelled by type, where a following dose is indistinguishable from a standalone reminder and no chain is visible at all; the entity comment specified SET_NULL because "a Low-Stock/Expiration alert reminder can be spawned from and tied to the medicine's primary dosing reminder", citing §3 for a rule §3 did not state, and `parentReminderId` is written in exactly two places (`AddReminderScreen` 196 and 316), both `LINKED_FOLLOWING`, so no alert reminder ever sets it. One rationale described a UI that does not exist, the other a feature that does not exist | ✅ Resolved -- **SET_NULL kept, §3 corrected to match, and detached children now switched off.** CASCADE was rejected on its merits rather than on cost: its own justification is void, and without the nested UI it assumed, cascading deletes rows the user never selected from a list that cannot show them, in a medication app -- and since §3's own chains compose to arbitrary length, the blast radius is unbounded. The migration was the smaller objection (schema 5 plus a full `CREATE`/`INSERT`/`DROP`/`RENAME` table rebuild, since SQLite cannot alter an FK in place -- precedent `MIGRATION_1_2`, and it would have wanted a `MigrationFrom4To5Test` to match the existing three). What was genuinely wrong with SET_NULL, and is what got fixed: a detached child kept its row, kept `enabled = true`, kept showing in the medicine's reminder list, and never fired again, *silently*. `ReminderRepository.delete()` now reads the children first -- the FK nulls `parentReminderId` as part of the delete, so afterwards there is nothing left to query by -- then disables them and deletes the parent inside one `database.withTransaction { }`, which is why the repository gained an `EmivitaDatabase` (matching `MedicineRepository`, and R1's precedent for why the wrap is a transaction rather than two writes). Coordinator notification stays outside the transaction, the same OS-alarm-versus-DB-write split `MedicineRepository.delete()` already documents; the parent routes through `onReminderRemoved`, each child through `onReminderChanged`, because a disabled child's row still exists and it is `scheduleOrCancel`'s "no next occurrence" branch that cancels its alarm. **Scope is one level down**, matching `getChildrenOf`'s own documented scope and `setEnabled`'s existing semantics -- disabling a chain's head has never disabled the rest of the chain, and a grandchild is not orphaned by this, merely following a parent that no longer fires. **The engine's null-parent guard stays load-bearing** and its comment now says why instead of pointing at an open decision: `delete()` is not the only route in, because `BackupRepository.restoreAll()` writes reminder rows back verbatim, so a backup whose parent row is missing restores an enabled child with a null parent, as would any row predating this change. **Tests:** 5 new in `ReminderRepositoryTest` (real in-memory Room, not mocked DAOs -- `withTransaction` is an inline extension MockK cannot intercept, and the SET_NULL behaviour under test is SQLite's, not the repository's), covering the disable, the detach-not-delete, the coordinator hand-off for every child, the childless case, and the one-level boundary. Suite 71 -> 76. **Honest limit:** `delete()` still has no call sites -- there is no per-reminder delete affordance, only medicine-level delete, which cascades parent and children together through `medicineId` and never touches the self-FK. This settles the semantics so that building that affordance is a UI change rather than a data-model decision; it does not itself make anything reachable. R23's other three decision items are untouched. Shipped in `24ba0de`, the same commit as R24. |  |
| R26 | The gap R24 carried forward from R23 and left open in its own turn: no CI and no lint gate, so a green `assembleDebug`/`testDebugUnitTest`/`lintDebug` was a snapshot someone remembered to take rather than something the repository enforced. R23 proved the cost concretely -- the `DataExtractionRules` finding it caught had been sitting unread in the lint report through an entire audit cycle, and it was found only because that pass happened to run lint at all. The precise hole: `app/build.gradle.kts` had no `lint { }` block whatsoever, so lint ran on defaults, where `abortOnError` stops the build for an **error** and a **warning** has no consequence at all -- and warning is exactly the severity the finding that escaped had | ✅ Resolved, in two halves that land differently. **The gate** (`app/build.gradle.kts`, new `lint { }` block): `abortOnError = true` and `warningsAsErrors = true` against a new `app/lint-baseline.xml`, so any finding not recorded the day the gate went up fails the build, locally and in CI alike. **The design call worth keeping:** of the 32 findings present, 25 are version-currency checks -- 13 `GradleDependency`, 11 `NewerVersionAvailable`, 1 `OldTargetApi` -- which describe the outside world rather than this code. Baselining those under `warningsAsErrors` would have meant the first AndroidX release after today turning the build red on a commit that touched nothing related, and a gate that reddens for reasons outside the change under test is one people learn to switch off, which lands straight back in R23's actual problem of a report nobody opens. They are therefore **demoted to `informational` rather than baselined**: still printed in the report and still uploaded as a CI artifact, so the upgrade list stays visible, but unable to block. `OldTargetApi` is in that set for the same reason and is already a documented decision (compileSdk 37 needs AGP 9.x; the note above the dependency block explains why 8.13.2 was pinned instead). That leaves 6 gate-relevant findings -- 5 `UnusedAttribute`, 1 `ChromeOsAbiSupport`, all stable and minSdk-related. Note for anyone opening the file: the baseline nonetheless contains all 32 entries, because AGP records everything it finds regardless of severity demotion; the 26 informational ones are inert, and it is the demotion, not the baseline, that protects against calendar churn. **Verified that the gate bites rather than assumed it**, which this ledger has had to learn twice: one baselined `UnusedAttribute` entry was deleted, `lintDebug` then exited 1 with "Lint found errors in the project; aborting build" on that warning-severity finding, and the baseline was restored byte-identical and re-confirmed green. A gate nobody has watched fail is not yet a gate. **The CI** (`.github/workflows/ci.yml`): one `ubuntu-latest` job running `assembleDebug`, `testDebugUnitTest`, `lintDebug` as three steps -- the same triad this ledger has been quoting by hand after every audit pass -- on push to master/main, on every pull request, and on manual dispatch. JDK 21 via `actions/setup-java` to match `gradle/gradle-daemon-jvm.properties`'s `toolchainVersion=21` and `jvmToolchain(21)`, so Gradle does not auto-provision a daemon JVM from foojay on every run; `android-actions/setup-android` for SDK licences, since the runner image does not reliably carry API 36; `gradle/actions/setup-gradle` for caching. Reports upload under `if: always()`, because a failing run is precisely when the report is worth having. One job rather than three parallel ones on purpose: splitting would triple Gradle configuration and SDK setup for a single-module app whose full local run is about two minutes, while three steps already attribute a failure to the right task. **Honest limits.** There is no git remote (`git remote -v` is empty), so the workflow is inert until one exists and is pushed to -- it is correct and self-activating, but today the lint gate is the half with actual teeth. And a local `assembleDebug` still does not run lint, only the lint task does, so enforcing the triad before every push on this machine would need a `core.hooksPath` pre-push hook; that is a separate decision and was deliberately not made here. README's command block now lists `lintDebug` and states that it is a gate rather than a report. |  |

---

## 8. Current implementation status

**Built and functional:** encrypted Room DB + full repository layer,
scheduling engine end-to-end (§4), notification posting, boot recovery,
Settings *home* screen (real UI, grouped list, matches confirmed mockup —
all 6 detail destinations now have real content: Data & Backup, Alarms &
Snooze and Weekend Mode as bottom sheets, biometric lock inline, and as of
this batch Notifications & Alerts and Appearance as real routed screens —
see below), app shell (bottom-nav Scaffold, type-safe `NavHost`, theming
works end-to-end), Stock & Expiration screen (§6/§9 #1), cascade-delete
medicine flow (§9 #1), the full Add/Edit Reminder Advanced-fields UI
(auto-resolve, active date range, repeat-until-answered/nagging — §3/§9
#1), and medTimer-derived nagging alarm mechanics end-to-end (§9 #1).
**Also completed this batch (§9 items 0/0b):** project identity is now
`com.hazia.emivita` / "Emivita" throughout (was `com.personal.medkeeper` /
"MedKeeper" — see R4/R5/R9 in §7), and the theming *mechanism*'s 8
palettes (Golden Hour → Velvet Ribbon) are now the ones actually wired in
`colorSchemeFor()` — the Clinical/Cozy/Neutral/Playful set §6 replaced is
gone from the codebase entirely, not just superseded on paper (see T3/T4
in §7).

**Notifications & Alerts** (`NotificationsAlertsScreen`): lists the 3 real
notification channels (Standard/High/Alarm) with plain-language
descriptions and a button deep-linking to Android's own
`ACTION_APP_NOTIFICATION_SETTINGS` for per-channel sound/vibration — a
deliberate scope boundary, not a stub: Android doesn't let an app override
a user's own per-channel choice once the channel exists, so there's
nothing further for in-app UI to do here.

**Appearance** (`AppearanceScreen`): palette grid (all 8, §6) + theme-mode
picker (Light/Dark/System), `SettingsRepository`-backed. This is now the
*only* place either is ever chosen: the onboarding screen this paragraph
used to compare against was deleted outright (§9 #1's reversal), so a fresh
install silently takes `SettingsRepository.palette`'s `GOLDEN_HOUR` default
with no prompt at all. That makes the ❓ note under §6's palette bullet more
open, not less — this screen gives the arbitrary default a revisit path,
but nothing anywhere asks the user to choose, and a real *default*-palette
decision is still open.

**Corrected — the three main screens are built.** `TodayScreen`,
`MedicinesScreen` and `HistoryScreen` all read real DB contents, and all
three are device-verified (§9 #1, #5, #6). Only `TodayScreen` reads it
*through the projection cache*, which this paragraph used to claim for all
three: `MedicinesScreen` observes `MedicineRepository.observeForProfile()`
and `HistoryScreen` observes `DoseEventRepository.observeInRange()`, which
is §9 #6's direct-query side. The screens are:
the live timeline, medicines list plus detail, and History's
List/Calendar/Table views. The paragraph that stood here said the exact
opposite — "literal placeholder text, no data-entry UI exists at all",
no Add Medicine button anywhere — left over from the data-layer-only
batch and never updated when #1 shipped. It survived a full reconciliation
pass and two README rewrites while being flatly false.

**So this section is no longer a status ledger and should not be read as
one.** §9's checkmarks are the ledger. Keeping a second copy of the same
state here is what produced that contradiction — not a disagreement about
the code, just two places to update and only one of them ever updated.
Anything shaped like "is X built?" is answered by §9. What stays here is
the detail §9's one-line entries cannot carry: the Notifications & Alerts
and Appearance notes above, which explain *why* those two screens stop
where they do.

---

## 9. Roadmap & open decisions

Ordered by dependency, not just priority — each batch needs the one above
it to already exist. Items **0** and **0b** below are numbered outside
that sequence deliberately: neither is a dependency of #1, both are "do
this before touching the codebase again at all" items, both confirmed
this session.

0. **✅ Rename batch — shipped this batch.** Applied §1's
   `com.hazia.emivita`/"Emivita" identity to a codebase that had shipped
   under a placeholder one. Scope: `applicationId`
   + `namespace` in `build.gradle.kts`, every `com.personal.medkeeper` package
   statement, `MedKeeperApplication`→`EmivitaApplication`,
   `MedKeeperRoute`→`EmivitaRoute`, `MedKeeperNavHost`→`EmivitaNavHost`,
   `MedKeeperDatabase`→`EmivitaDatabase`, `Theme.MedKeeper`→`Theme.Emivita`,
   `strings.xml` `app_name`, `rootProject.name` in `settings.gradle.kts`.
   `.debug` applicationIdSuffix carried over onto the new id unchanged. Also
   caught two lowercase stragglers a literal find-and-replace on the scope
   list above would have missed: the Room DB filename (`medkeeper.db` →
   `emivita.db`) and the `EncryptedSharedPreferences` file name
   (`medkeeper_secure_prefs` → `emivita_secure_prefs`). Same batch, same
   reason (existing code contradicting an already-decided spec position),
   two small dependency removals:
   - Dropped `androidx.health.connect:connect-client` and the two Health-
     Connect-shaped manifest comments — contradicted #13's ❌ Dropped
     decision, which was decided *because* Health Connect needs Play
     services even on its "built-in" path, a direct conflict with §1's
     de-googled constraint.
   - Dropped `androidx.work:work-runtime-ktx` (WorkManager) — see #9's
     housekeeping note; superseded by the debounce+explicit-trigger design
     specified for both the projection cache (#4) and the auto-sync
     folder (#9).
   - Not originally scoped, found and fixed in the same pass since the
     file was already open (R9, §7): `security-crypto` was pinned to a
     plain `1.1.0` in the build file, a version that doesn't correspond to
     any published artifact — repinned to `1.1.0-alpha07`, matching what
     §2 already correctly says the dependency is.
0b. **✅ Palette replacement batch — shipped this batch.** Same "outside
    the dependency sequence, existing code contradicts an already-decided
    spec position" shape as item 0, just for §6's palette decision instead
    of §1's identity one. Scope: replaced the 4 shipped palette objects
    (Clinical/Cozy/Neutral/Playful) with the 8 named in §6 (Golden Hour,
    Sunlit Focus, Forest Floor, Sage Clinical, Ink & Ledger, Cobalt
    Porcelain, Rose Petal, Velvet Ribbon) — a straight swap, not an
    addition, matching §6's "old 4 are fully replaced" decision. Hex
    values and every role (background/surface/outline/primary/secondary/
    accent/text/mutedText) came from Appendix A, not re-derived. Two
    things closed out in the same batch, not deferred:
    - T3 (§7): resolved `secondary`/`tertiary`/`error`/`onError`/
      `errorContainer` for all 8 palettes in `colorSchemeFor()`, done once
      across all 8 rather than per-palette later, same reasoning as fixing
      outline/mutedText once via construction instead of patching four
      separate hex edits (§6, T1/T2). `onSurfaceVariant` wired to the
      audited mutedText token in the same pass (T2's fix, not originally
      in T3's scope but the natural M3 slot for it).
    - Nothing currently persists a user's chosen palette (confirmed by
      searching the codebase during this batch — Settings/DataStore
      hasn't landed, §8), so there was no stored identifier to reconcile
      to the new 8. `MedPalette`'s default parameter needed *something* to
      compile, though — Golden Hour was picked (first-listed above,
      arbitrarily), flagged as its own item in §6's palette bullet rather
      than treated as settled.
1. **🟡 Add/Edit Medicine + Add/Edit Reminder UI, wire Today/Medicines to
   real data (minimal) — shipped this batch, with caveats below.** Blocks everything else — nothing past this point
   is testable without it. "Minimal" here means Today/Medicines show actual
   DB contents, not yet the full timeline/projection UI from §6 — that
   waits on #4. Build against the full schema from §3 (including the seven
   gap-resolution fields and the newly-added `Medicine.sortOrder` /
   `Medicine.barcodeValue` / `Medicine.indication` / `Medicine.storageNote` /
   `Medicine.color` — layout and the toggle-off fallback are locked, §6/§3)
   even though the form only exposes the 3 core types + basic fields at
   first —
   see §4's note on `autoResolve`/active-range clamping being natural to
   wire in alongside this batch. **✅ Closed in a later batch (spec-vs-code
   audit):** `autoResolve` (`ReminderAlarmReceiver` resolves TAKEN silently,
   no notification) and the `activeFromEpochDay`/`activeUntilEpochDay` clamp
   (`SchedulingEngine.nextOccurrenceFor()`, before weekend mode) are both
   wired. `repeatOnUnanswered` nagging is still schema-only — it needs its
   own alarm channel (a re-fire distinct from the reminder's own next
   occurrence), not just a clamp, so it wasn't guessed at alongside the
   other two. **✅ Also closed, per medTimer's own mechanism** (`AlarmProcessor`'s
   secondary-alarm slot + `RepeatProcessor`, ported onto Emivita's per-reminder
   `RepeatConfig` rather than medTimer's global on/off preference, since the
   per-reminder opt-in is what §3 already locked): `AlarmScheduler` gained a
   third PendingIntent slot (`scheduleNag`/`cancelNag`, distinct from both
   the regular and snooze slots so none of the three can clobber each
   other); `ReminderAlarmReceiver` routes a nag fire to its own
   `handleNagFire` rather than sharing `handleAlarm`'s path, so the regular
   chain's reschedule/cascade side effects can't re-trigger on every
   re-notification (matching medTimer's own `RepeatProcessor` living wholly
   apart from `ScheduleNextReminderNotificationProcessor`); `DoseEvent.repeatCount`
   increments per nag fire up to `repeatOnUnanswered.maxRepeats`; nagging
   stops the moment a dose is genuinely answered
   (`DoseEventRepository.resolve()` → `SchedulingCoordinator.cancelNag`) --
   snoozing doesn't count as answered, so a nag mid-snooze-window is
   intended, not a bug. Two calls confirmed this session, both
   the recommended option, no longer open:
   - 🔒 **Reminder types in the first version of the Add form: just the 3
     the engine actually schedules** (Fixed Time, Repeating Interval,
     Daily Window). Low-Stock/Expiration cards still appear in the picker
     (matches the confirmed 5-card UX in §6) but land on a "coming soon"
     state rather than silently accepting data nothing will ever act on —
     those two types have no engine behavior to connect to until the
     projection cache (#4/#8) exists regardless of whether their form is
     built, so there's nothing to gain by building it early. `CyclicActivePause`
     (a 4th `RepeatPattern` case inside the existing Fixed Time card, §3/§14)
     and `LinkedFollowing` (§3/§14) are deferred the same way, for different
     reasons — Cyclic is cheap to add later (one pure calculator function,
     no schema change, no new card) so there's no urgency either way;
     LinkedFollowing needs its own cascade-reschedule design (§4-adjacent,
     touching `SchedulingEngine`/`ReminderAlarmReceiver`/
     `DoseEventRepository`) that deserves to be built deliberately as #14,
     not folded into this batch.
   - 🟡 **CRUD scope: add + list shipped previous batch; edit shipped this
     batch** as the immediate follow-up already anticipated when add
     shipped ("edit needs the same form as add anyway once add exists").
     `EditMedicineScreen` and `EditReminderScreen` reuse the same
     field-level form bodies as Add (`MedicineFormBody` in
     `AddMedicineScreen.kt`; `FixedTimeFormBody`/`RepeatingIntervalFormBody`/
     `DailyWindowFormBody` in `AddReminderScreen.kt`, extracted from the
     Add-only forms specifically for this reuse) rather than being separate
     screens with duplicated field logic. Reached from
     `MedicineDetailScreen`: an edit icon in the top bar for the medicine,
     tap-to-edit on each reminder row. Reminder edit does not offer a type
     picker — switching a `Reminder.type` is treated as delete-and-recreate,
     not a field edit, since type determines the schema, scheduling
     behavior, and notification history keyed to that row. Tag
     reconciliation on medicine edit is a plain set diff against a one-time
     snapshot taken when the screen opens (`Flow.first()`, not a continuous
     collector) — adequate for a single-user, single-device app with no
     concurrent writers. **Delete: ✅ built, see below** — cascade delete,
     resolved via `AskUserQuestion` rather than guessed (the one genuinely
     undecided data-integrity question this spec left open, distinct from
     the already-decided-but-unbuilt gaps closed without asking).

   Barcode scanning (§3) also lands in this batch: an optional "Scan
   barcode" action on the Add form (attaches a code to the medicine being
   created) and a "Scan to find" action on the Medicines list (looks up an
   existing medicine by its stored code, falls back to "not found, add
   new?" if nothing matches) — delegated entirely to an installed scanner
   app via `Intent`, no bundled library, no camera permission.

   **What actually shipped vs. what's still open**, confirmed this batch:
   - Migration (§3 schema): `Migrations.MIGRATION_1_2` recreates
     `medicines`/`profiles`/`reminders`/`dose_events` rather than patching
     columns in place (SQLite's `ALTER TABLE` can't add a FK, change
     nullability, or reorder columns). **✅ Now covered by an automated
     regression test** (R11, §7): `MigrationFrom1To2Test` builds the v1
     table shapes from raw SQL and runs the real `Migration` object against
     sample rows, asserting the colorArgb→hex conversion (including the
     negative-Int bitmask case), the `sortOrder` backfill, and the
     denormalized `dose_events.medicineId` backfill all come out correct —
     this is the actual risk `Migrations.kt`'s own doc comment was flagging,
     not just "does it run." What's still genuinely unverified is on-device
     behavior specifically: an in-place SQLite upgrade against a real
     installed v1 database file, which a JVM-side test with a hand-built
     fixture can't stand in for. This environment does have a working
     Android SDK (confirmed all batch — `gradlew`/`adb` both used directly),
     so that's a real gap to close, not an environment limitation.
     **✅ The blocker on closing it is gone as of this pass:** what made it
     unclosable was having no installable predecessor to upgrade *from* —
     `assembleRelease` had never once been run, and an unsigned APK cannot
     be installed at all. Release builds are now signed (`signingConfigs`,
     shared debug identity — see `app/build.gradle.kts`'s comment for the
     trade that accepts) and v0.1.0 / versionCode 1 is archived at
     `dist/vc1/`, tracked in git so it cannot be cleaned away, with its
     R8 mapping file and full provenance in `dist/vc1/MANIFEST.md`.
     Two things to know before using it: the DB it ships is **schema
     version 4**, not 1 — `versionCode` and Room's version are unrelated
     numbers — so it is the baseline for the *next* migration, not for
     `MIGRATION_1_2`, which stays fixture-tested only (no v1-era build of
     this source tree has ever existed — see R11's note on why there is no
     `1.json`). And an in-place upgrade only succeeds while the newer APK
     carries the same signature, so replacing that keystore with a real
     release key retires this baseline and requires cutting a fresh one.
     The remaining work is the run itself: install it, create real data
     through its own code paths, bump `versionCode`, `adb install -r`.
   - Icon set (§3/§6 "icon-per-type grid"): no set existed anywhere before
     this batch. Shipped with a curated placeholder of ~11 stock Material
     Symbols (`ui/icons/MedicineIcons.kt`), by the user's own call, with a
     deliberate indirection seam (`Medicine.iconKey` stores a string key,
     never the rendered icon itself) so a future custom line-art set can
     drop in as a rendering-layer change only.
   - Color picker (§3/§6): the HSV-square + hue-strip + hex-readout
     component is real, working code (`ui/components/ColorPickerDialog.kt`),
     not a placeholder — but hasn't been visually reviewed against a
     running build either, for the same no-SDK reason as the migration.
   - Current-profile resolution: **not previously scoped anywhere in this
     spec** (§9 confirmed no "current/active profile" concept existed at
     all before this batch) — #1 can't wire Today/Medicines to real data
     without one. Single-profile assumption only; no multi-profile
     switcher. 🔄 **Reversed in a later batch** (device-testing session,
     this reconciliation): the first-run onboarding *screen* (name + theme
     palette form) that originally resolved this was removed on user
     request ("an unnecessary extra layer") — `EmivitaApp.kt` now creates
     the one `Profile` row this single-profile app needs **silently**,
     the instant `profileRepository.observeAll()` is observed empty, with
     a fixed `DEFAULT_PROFILE_NAME = "Me"` (confirmed by search: a
     profile's `name` is never displayed anywhere in the UI outside
     creation, only its `id` is ever read afterward via
     `LocalCurrentProfileId` — so a fixed string is genuinely safe, not a
     placeholder standing in for a real field). Palette persistence
     (`SettingsRepository`, DataStore, pulled forward from #12's scope)
     is unaffected — `palette`'s own Flow already defaulted to
     `GOLDEN_HOUR` when nothing's persisted (§6's still-open "no
     deliberate default chosen" note), so onboarding's explicit
     `setPalette()` call was redundant with that default and needed no
     replacement. `feature/onboarding/OnboardingScreen.kt` deleted
     outright, not deprecated in place. Device-verified: a fresh install
     lands directly on Today's empty state, no form shown, confirmed on
     the OnePlus 6T used for this session's other device testing.
   - Today's "minimal" content (previously undefined in this spec):
     resolved as a live-computed flat list from each enabled reminder's
     `nextOccurrenceFor()`, not a DB observation — there's no reactive
     invalidation without #4's projection cache, so a manual refresh
     button is the acknowledged stopgap.
   - Medicine detail screen shape (previously undefined — §6 only
     specifies the eventual full *edit* screen): scoped down to a
     read-only summary + reminders list + "Add reminder", matching the
     add+list-only CRUD scope above. **✅ Also closed this batch:** an
     overflow menu (`MoreVert`) next to the existing edit icon adds
     **Stock & Expiration** (routes to the new screen, §6) and **Delete**.
     Delete resolves the previously-open cascade-vs-archive question —
     **cascade delete**, the recommended and confirmed option:
     `MedicineRepository.delete()` now deletes the medicine's reminders
     (`SchedulingCoordinator.onReminderRemoved` per reminder, cancelling
     their alarms) and every `DoseEvent` tied to it, including PRN/refill
     events that only carry the denormalized `medicineId` column and have
     no FK to catch them (§3) — a plain `DELETE FROM dose_events WHERE
     medicineId = :id` alongside Room's own reminder cascade, wrapped in
     one `withTransaction`. A confirm dialog ("This removes every reminder
     and all dose history for it. This can't be undone.") gates it.
     Covered by a repository-level test (`MedicineRepositoryTest`,
     in-memory Room, same rationale as R1/R3).
   - "Scan to find" on the Medicines list: **built in a follow-up pass this
     batch**, after initially being left out and flagged as dropped. Worth
     recording why the flag was wrong: this action was never in tension with
     the local-first/no-`INTERNET`-permission constraint (§1) — it's a
     lookup against `Medicine.barcodeValue` in the on-device DB
     (`MedicineDao.getByBarcodeValue`), not a call to any online product
     database. Scanning itself is the same delegated-`Intent` hand-off to
     an installed scanner app (e.g. Binary Eye) already used by "Scan
     barcode" on the Add form — no camera permission, no new dependency.
     A miss opens a "not found, add new?" dialog that carries the scanned
     code straight into the Add form (`EmivitaRoute.AddMedicine.prefilledBarcode`)
     rather than making the user re-scan.
   - Fixed alongside, since the file was already open for this batch's
     `addTag()` call site: R1 (§7) — `MedicineRepository.addTag()` is now
     wrapped in `database.withTransaction { }`. R2 (dead-code
     `decrementStock` wrapper) was **not** touched — out of scope, no
     code path in this batch calls it.

2. **✅ PRN / ad-hoc dose logging** (§3/§6) — the "Log dose" entry point and
   `logAdhocDose`. Small and largely independent of the Add/Edit Reminder
   form itself; natural to ship right alongside or immediately after #1.
   The Stock & Expiration screen (§6, part of #1) gets a manual **Refill**
   action the same way — `logRefill` doesn't need #8's notification-triggered
   path to be useful on its own.
3. **✅ Event editing / undo** (§3/§6) — `switchStatus` (stock-correct
   Taken↔Skipped), `editRecordedTime`, `editDoseAmount`, and the
   tap-to-edit `DoseEditDialog` shared by Today and History (a tap, not the
   swipe §6 originally specified — see that bullet for why). Needs #1
   shipped first (nothing to edit otherwise).
4. **✅ Shared occurrence-projection cache** (§2's locked decision; the
   Emivita equivalent of medTimer's `FutureRemindersRepository`, now
   verified against medTimer's actual — recently modularized —
   `SimulatedRemindersRepository` source rather than assumed). A singleton
   that projects reminder occurrences forward over a requested window and
   computes each medicine's stock run-out date as a side effect of the
   same pass, extending `ReminderScheduling.kt`'s pure calculators into
   range-projection rather than just next-occurrence (§4). This is the
   foundation #5–#8 build on — building any of them independently first
   repeats the exact mistake medTimer's own ADRs document making and
   having to undo. Resolved design, adopted close to verbatim from
   medTimer's implementation (~140 lines in their current source — small
   enough to copy the shape directly rather than simplify away):
   - **Window ownership:** a plain `Map<consumerId: String, days: Long>`.
     `requestWindow(consumerId, days)` sets/updates an entry; the
     effective window is the max across all current entries (28 days if
     none registered — medTimer's own default, no reason to pick a
     different number). `releaseWindow(consumerId)` removes the entry but
     does **not** force an immediate recompute — shrinking isn't urgent,
     it takes effect on the next trigger that happens anyway.
   - **Invalidation has two channels, not one:**
     (a) *Reactive:* one collector on a `Flow` from a `@Transaction`,
     `@Relation`-joined query. medTimer gets "any medicine *or* reminder
     edit triggers a recompute" from a single watcher because their Flow
     query already embeds each medicine's reminders
     (`FullMedicineEntity`), so Room's own change-tracking re-fires it on
     writes to either table. **Emivita doesn't have this shape yet** —
     `MedicineDao.observeForProfile()` only embeds `Tag`
     (`MedicineWithTags`), not `Reminder`. This batch needs a new
     `@Relation`-joined aggregate (mirroring `MedicineWithTags`, adding
     reminders from the `reminders` table via `medicineId`) built
     specifically for the cache's watcher — prefer this over two separate
     Flow collectors combined, since it matches medTimer's actual
     mechanism and keeps it to one watcher.
     (b) *Explicit:* calls after dose-event processing (Take/Skip/Snooze,
     PRN logging, refill) and after each alarm-fired reschedule — medTimer
     funnels these through one command-bus method rather than relying on
     a `DoseEvent` Flow watcher, since a dose resolution needs the
     *next-alarm* recompute regardless of the cache. Emivita's
     `DoseEventRepository` methods (`resolve`, `switchStatus`,
     `logAdhocDose`, `logRefill`) should each call the cache's trigger
     explicitly, matching this, rather than adding a third Flow watcher.
   - **Debounce + time budget, both cheap insurance worth keeping:**
     trigger requests coalesce (medTimer: a conflated channel + 1-second
     delay, so rapid-fire edits collapse into one recompute, not one per
     edit), and the computation itself gets a hard time budget (medTimer:
     2 seconds) so a pathological case stops and returns partial results
     rather than blocking.
5. **✅ Today screen** (full timeline UI from §6) — consumes the
   projection cache.
6. **✅ History/Calendar screen** — future entries from the projection
   cache, past entries from a direct DB query (medTimer's own split, per
   ADR-0002; History itself only reads the direct-query side — Today owns
   the cache-driven forward-looking half). Shipped scope: the
   list/adherence-summary core (grouped by day, reverse-chronological,
   refills folded into the same event stream), #10's CSV/PDF export, and
   the Vico adherence trend chart (`AdherenceChart.kt`, §7 R13) — plus, as
   of this batch, the two items from §5's ADR-0001 shopping list that were
   still open: a **Calendar view** (`HistoryCalendarView.kt`, Kizitonwose
   `compose` artifact — month grid, taken/skipped/missed dot per day,
   tap-a-day detail panel; re-queries the DB per visible month rather than
   `HISTORY_LOOKBACK_DAYS`'s fixed 30-day window, since browsing arbitrary
   past months is the point of a calendar) and a **hand-rolled sortable
   table** (`HistoryTableView.kt` — Date/Medicine/Status/Dose columns, tap a
   header to sort, same 30-day dataset the List view already loads). All
   three (List/Calendar/Table) sit behind a `FilterChip` row on
   `HistoryScreen`, the same view-mode-switcher idiom `StockExpirationScreen`
   already established for its own trigger-mode/frequency rows — List is the
   default. **Device-verified** this session (OnePlus 6T, physical device):
   onboarding → Add Medicine → Add Reminder (Fixed Time, confirming
   `CyclicActivePause` renders as the "Active/pause cycle" 4th option) → PRN
   `Log dose` → all three History views exercised with real data — List
   (summary card + chart + day groups), Calendar (month grid dot indicators,
   tap-a-day detail panel reusing `HistoryEntryRow`, Italian-locale day
   names/date formatting from the device's own locale), Table (column-sort
   toggle confirmed switching both the active column and sort direction).
7. **✅ Widgets** (Glance, not RemoteViews — see §5) — reads the
   projection cache rather than running its own simulation. Two widgets:
   Next Doses and Stock Levels, each with a one-tap action (Take / Refill).
8. **✅ `LowStockAlert`/`ExpirationAlert` triggering** — alerts off the
   stock run-out / expiration dates the projection cache already computes.
   No separate `WorkManager` polling job; the cache's existing DB-change
   and alarm-fired triggers keep it current on their own. Its notification
   gets a **Refill** action alongside Take/Skip/Snooze once `logRefill`
   (§3) exists, using `StockInfo.refillQuantity` as the one-tap amount.
9. **✅ SAF backup/restore + auto-sync folder (password-protected ZIP)**
   (medTimer's `BackupManager`/`FileHelper` pattern for the SAF I/O plumbing,
   §5 — the password-protection layer is original, since medTimer's DB isn't
   encrypted and Emivita's is, §2/§5). Two distinct needs, reconciled into
   one writer per §2's locked "one writer, two triggers" decision rather
   than built as separate subsystems:
   - **(a) Manual disaster-recovery/migration export+restore** — a
     deliberate, present-user action (new phone, handing a snapshot to
     someone, before a risky change), distinct from #10's human-readable
     CSV/PDF export.
   - **(b) Auto-sync folder** — the app keeps a folder current on its own so
     a folder-sync tool the user already runs (a Syncthing fork, e.g.) can
     mirror it to another device. SAF is right for (a); it is *not* itself
     a sync mechanism, and (b) doesn't get its own separate export format or
     ad hoc writer — it reuses (a)'s exact zip writer under a different
     trigger and password policy, below.

   Shared writer, both paths:
   - **Format:** always a `.zip` containing the JSON backup, whether or not
     it's password-protected — keeps restore a single file type; "does
     this need a password" is read off the zip's own header at import
     time rather than tracked separately. `net.lingala.zip4j` (Apache 2.0)
     for AES-256 zip read/write — the standard library for this on
     Android/Kotlin, same shape as Catima's (open-source) optional
     password-protected export/import.

   (a)'s UX, unchanged:
   - **Password checkbox: on by default, resets to on every time the
     export screen opens** — not a remembered setting. Unchecking it
     doesn't export immediately; only pressing Export then triggers an
     unskippable confirm dialog ("Export without a password? This file
     will contain readable medication data." / Cancel / Export anyway),
     where dismissing it *any* way — back button, tap outside, Cancel —
     always resolves to "not exported." Only an explicit tap on "Export
     anyway" proceeds to a plaintext zip.
   - **Restore is a separate SAF flow from export** — `ACTION_OPEN_DOCUMENT`
     (single-file picker), not the `OpenDocumentTree` folder-access pattern
     export uses — prompting for a password only if the picked zip turns
     out to be encrypted.

   (b)'s design, new:
   - **Folder chosen once, not per-write.** A new "Backup & Sync" Settings
     row grants a SAF folder via `OpenDocumentTree`, `takePersistableUriPermission`'d
     so it survives reboots — distinct from (a)'s per-tap picker, since an
     automated writer can't re-prompt. (a)'s export screen offers this same
     saved folder as its default target (still overridable per export) so
     there's normally one real folder in play, not two.
   - **Trigger: debounced + explicit, no `WorkManager`, no periodic job** —
     the same idiom §9 #4 already established for the projection cache
     (conflated-channel + delay, coalescing rapid changes into one write).
     Fires after the same events that already invalidate the cache (dose
     resolution, PRN logging, refill, medicine/reminder edits) rather than
     on a timer. Consistency matters here specifically because the opposite
     choice — introducing `WorkManager` for this while the cache
     deliberately avoids it — would mean two different background-work
     paradigms doing conceptually the same "don't poll, react" job.
   - **Always password-protected — no per-write confirm dialog.** There's
     no user present to click through (a)'s unencrypted-export warning on
     an automatic write into a folder that leaves the app's sandbox and
     potentially replicates to less-trusted storage. 🔒 **Passphrase is
     user-typed once, when the folder is first granted** — a real prompt,
     Catima-style, the same way (a)'s export password already works — not
     silently Keystore-generated the way the DB's is (§2). Independent from
     the DB passphrase entirely: the DB secret never leaves the sandbox,
     this zip does by design, so the two shouldn't share a blast radius.
     Once typed, it's cached (Keystore-backed storage, same mechanism §2
     uses to hold the DB secret at rest) so it isn't re-entered on every
     debounced write — but the value itself came from the user, not the
     app, which also settles restore safety for free: since the user chose
     it, they're expected to remember/record it the same way they'd
     remember any password they set themselves — no separate "reveal your
     auto-sync password" screen needed. Not exposed as a skippable option
     the way (a)'s is.
   - 🔒 resolved: debounce interval is **12s** (vs. #4's cache at 1s) — long
     enough that a normal editing burst (a few reminders adjusted back to
     back) coalesces into one zip write instead of several, short enough
     the synced folder doesn't feel stale. A plain runtime constant, cheap
     to retune once it's actually running.
   - ✅ built: `BackupSyncScreen.kt` (`EmivitaRoute.DataBackupSettings`).
     Both flows are clearly separated (Manual backup / Restore from backup
     as their own labeled sections, Auto-sync folder below a divider) and
     the passphrase dialog states plainly that it's the only copy and
     won't be re-shown. Should make clear, at minimum,
     that (1) this is distinct from (a)'s manual export and doesn't replace
     it, and (2) the password typed here is the only copy of it — write it
     down, because restoring this file on a different device (the actual
     disaster-recovery case a backup exists for) needs it typed back in,
     and there's nothing to reveal or recover it from if it's forgotten.

   **Housekeeping note:** the scheduling-engine batch's `build.gradle.kts`
   already carries an `androidx.work:work-runtime-ktx` (WorkManager)
   dependency labeled "periodic auto-backup, stock-simulation recompute" —
   that predates this reconciliation and should come out. Nothing in this
   spec calls for a periodic job anywhere; both this item and #4 use
   debounce + explicit triggers instead. Folded into §9 item 0.
10. **✅ CSV/PDF history export** (§5) — human-readable dose-history
    reports for sharing with a doctor; native `PdfDocument`, no new
    dependency, distinct from #9's JSON backup.
11. **✅ Biometric app-lock** (`BiometricPrompt`, fail-open when the device
    has no enrolled biometric or no hardware — `Biometrics.kt`'s medTimer
    port, §5). `MainActivity` moved to `FragmentActivity` (needed as a
    `BiometricPrompt` host). Scoped to just the toggle, per #15's Settings
    click/page-count pass: it's inline on the Settings list, no dedicated
    screen. "Hide names" / "block screenshots" — the other two things the
    original "App Lock & Privacy" row description promised — remain
    unbuilt; no `FLAG_SECURE` or name-masking code exists anywhere.
12. **✅ User preferences (DataStore): snooze duration, weekend mode,
    lock-until-taken default** (§3/§6) — moves `DEFAULT_SNOOZE_MINUTES`
    from a hardcoded constant to a persisted setting on "Alarms & Snooze,"
    wires the already-existing "Weekend Mode" Settings destination to the
    new global preferences (§3) the engine now reads, and adds
    `lockRemindersUntilTakenByDefault`. That last one also carries a small
    real fix to already-shipped code, not just new settings UI: migrate
    `Medicine.remindersLockedUntilTaken` from `Boolean` to `Boolean?` so it
    can actually inherit the new global default the way its own doc comment
    already claimed it does, and make `ReminderNotificationBuilder` /
    `ReminderActionReceiver` / `AlarmActivity` actually honor the resolved
    value (omit Skip, reject it defensively too) instead of ignoring it as
    they do today. One new `androidx.datastore:datastore-preferences`
    dependency (pure AndroidX, no GMS — fits §1) covers all three
    preferences; also the natural home for #16's eventual Tink-based
    passphrase storage, if that migration ever happens — worth building
    once, not twice.
13. **❌ Dropped — Health Connect integration.** No medTimer equivalent to
    port; decided against because Health Connect's own current
    requirements name Google Play services as a dependency even on the
    Android 14+ "built-in" path, not just the Android 13-and-below
    Play-Store-app path — a direct conflict with §1's 🔒 de-googled
    constraint, not a stylistic mismatch. App stays local: no Health
    Connect permission, dependency, or integration code in any batch.
14. **✅ Cyclic, linked (following-dose), and weekend-mode reminders**
    (§3/§4) — schema and engine design are now resolved (`CyclicActivePause`,
    `LinkedFollowing` + cascade reschedule, the weekend adjustment step off
    #12's preferences). ✅ **All of it is now built**, and the R8 scoping
    call it rested on held up: `CyclicActivePause` is a 4th `RepeatPattern`
    case inside the already-existing "Fixed Time" card, needing no new
    top-level picker card (R8, §7 — an earlier draft of this item wrongly
    scoped it as "a 6th and 7th card"). What shipped: the `SchedulingEngine`
    branches for both new types (`SchedulingEngine.kt`'s `LinkedFollowing`
    case; `ReminderScheduling.kt`'s `RepeatPattern.CyclicActivePause` case),
    the weekend adjustment step (`ReminderScheduling.applyWeekendMode`,
    called from `SchedulingEngine`), and both cascade-reschedule trigger
    points (`ReminderAlarmReceiver` and `RealSchedulingCoordinator`, each
    gated on the child config being `LinkedFollowing`). On the form,
    `LinkedFollowing` landed as a top-level card and `CyclicActivePause` as
    the 4th chip in Fixed Time's `RepeatPattern` selector — so the picker is
    4 cards, not the "6th card" this item anticipated, because the two alert
    types came out of it (§7 R15). The "or nested under an Advanced entry
    point — not yet decided" question is therefore closed by what shipped.
15. **✅ Cheap fixes** (§7's still-open rows: R1, R2, R3, `asOnSurfaceSafe`,
    launcher icon, placeholder copy). All resolved: R1 (transaction-wrapped
    `addTag`) landed alongside #1; R2 (`MedicineRepository.decrementStock`
    dead-code wrapper) deleted outright rather than kept unused; R3 (no
    tests for `resolve()`/tag-dedupe) closed with real coverage —
    `DoseEventRepositoryTest`/`MedicineRepositoryTest`, an in-memory Room
    instance rather than mocked DAOs, since `withTransaction` is an inline
    extension MockK can't intercept; `asOnSurfaceSafe` is now a real WCAG
    (4.5:1) contrast clamp, not a documented no-op; launcher icon and
    placeholder copy were already resolved (§7 table). Also folded in here:
    a Settings click/page-count pass — Alarms & Snooze and Weekend Mode now
    open as `ModalBottomSheet`s instead of full page pushes, and the
    biometric-lock toggle (#11) is inline on the row itself, cutting three
    of the built destinations down to zero or one tap instead of a full
    navigate+back cycle. Data & Backup (#9), Notifications & Alerts and
    Appearance still push real routes — each genuinely multi-flow content
    that doesn't fit a sheet. (This line read "Only Data & Backup" until the
    third audit pass; `EmivitaNavHost` navigates to all three and
    `EmivitaRoute` declares all three.)
16. ~~**EncryptedSharedPreferences → DataStore+Tink migration**~~ — ✅
    resolved directly (§2, efficiency pass this batch), not deferred:
    `PassphraseProvider` dropped `EncryptedSharedPreferences`/`MasterKey`
    for a direct `AndroidKeyStore` AES/GCM wrap, so there's no deprecated
    dependency left to migrate away from.

### Not yet scoped — parking lot ❓

Ideas surfaced in review but deliberately not resolved into the
dependency-ordered items above — captured so they aren't lost, not yet
designed, sized, or prioritized. To be discussed more in depth before any
of them become a real item. (`Medicine.indication`/`storageNote` were
promoted out of this list into §3/#1 — stress-testing found them to be
non-events, nothing left to discuss.)

- **Doctor & pharmacy contact info** (new `Profile` fields: doctor
  name/phone/address, pharmacy phone) plus **allergies** and **blood
  type** (informational text only, never algorithmically checked against
  anything — no interaction logic, just display). Would feed an expanded
  version of #10's emergency export. Deliberately not a lock-screen
  widget: Android's own built-in Emergency Information / Personal Safety
  feature already covers that case better than any third-party app can
  (readable without unlocking the phone at all). Emivita's angle would
  be a printable/emailable document — handing a new doctor a page,
  keeping one in a wallet — which the OS feature can't produce.
  **Blocked, not just unscoped:** stress-testing found no "current/active
  profile" concept anywhere in this batch (`ProfileRepository` is a flat
  `observeAll()`, nothing tracks which profile is active) — and #10's
  current one-line scope doesn't say what an export is scoped to at all,
  which matters more here than for a dose-history CSV: mixing two
  profiles' identities on one ICE card would be actively wrong, not just
  untidy. Needs that upstream question answered — where/how "current
  profile" gets resolved — before this is scopeable at all; the fields
  themselves were never the hard part.
- **Tapering schedules** (step-down dosing, e.g. 10mg → 7.5mg → 5mg over a
  set timeline) — real, distinct need (dedicated apps exist for just
  this). Smaller blast radius than first assessed: `DoseEvent.doseAmount`
  is already snapshotted per-event at materialization time (for an
  unrelated reason — so editing a reminder's dose doesn't retroactively
  rewrite history), which is exactly the shape tapering needs. The actual
  work is contained to a new `Tapering` `ReminderConfig` variant (a list
  of `{dateRange, dose}` steps instead of one static `dose: Double`) plus
  the one function that resolves "which step applies on this date" during
  materialization — it doesn't ripple into `DoseEvent`, history, or
  export. Still not promoted: nothing here is blocked or unclear anymore,
  it's just genuinely not needed yet. Revisit if that changes.

---

## 10. Working agreements

- **This file is the spec-first checkpoint.** Small changes discussed in
  chat get folded in here; large batches don't start until the relevant
  section above is confirmed — that's the whole point of writing this.
- **The spec is allowed to run ahead of shipped code by several iterations**
  — that's what already happened before the scheduling-engine batch (this
  file kept evolving in chat while that batch's code stayed fixed), and it's
  fine. It stops being fine only if a *build* batch ships without first
  reconciling against whatever this file currently says — the direction of
  sync is always code catching up to spec, never the reverse. One concrete
  consequence: a delivered batch's own bundled `README.md`/`docs/SPEC.md`
  snapshot going stale relative to this file (see R6, §7) isn't itself a
  conflict to panic over — it's just lag — but it should get corrected
  before the *next* handoff, so staleness doesn't compound across batches
  and start looking like a real contradiction when it's really just drift.
- **XML comments never use `--`** anywhere in the body (only the
  legitimate `<!--`/`-->` delimiters) — single hyphens, `=`, or commas for
  separators instead. Kotlin comments are unaffected by this rule.
- Every batch gets checked against §1's constraint table before it ships.
- **One file.** Findings, audits, reviews and design rationale go in this
  file — §7's ledger for the finding, an appendix for the reasoning. No new
  standalone Emivita `.md` alongside it, and nothing that matters left
  outside the repo where it cannot be committed (see "Where everything
  went" at the top for what that habit cost).

---

## Appendix A — Palette token reference (full tables)

Referenced from §6's Palettes bullet. Every hex here is computed, not
eyeballed — contrast ratios are WCAG relative-luminance calculations,
verified with a script, not estimates.

**Role legend:**

- **bg** — page-level background
- **surface** — elevated card/container background
- **outline** — border/divider color, solved to clear **3:1 against both
  bg and surface** (WCAG 1.4.11) — the fix for T1 (§7)
- **primary** — brand/CTA color, intentionally sits at 3–8:1 against
  surface — large-element/UI use, not small body text, matching how the
  lexicon's own entries (e.g. Cozy Cocoa) use this role
- **secondary / accent** — supporting and decorative roles
- **text** — primary reading color, always ≥8.5:1 against surface (AAA)
- **muted** — the real, audited `mutedText` token at ~4.6:1 against
  surface — the fix for T2 (§7)

**OLED note:** every dark `bg` sits at 4.5–5.9% lightness (phone-target
range) rather than the 8–12% that reads fine on a desktop-tuned dark
theme, while keeping the family's own hue — never neutral black.

**Astigmatism/eye-comfort note** (reasoned extension, not in any
consulted skill file): high-contrast bright elements on a very dark OLED
panel can produce halo/glare artifacts, worse for astigmatic vision; blue
hues scatter more due to chromatic aberration. So across every dark
register below: no pure `#000000`/`#FFFFFF`, peak text-on-surface
contrast is kept around 12–15:1 rather than pushed toward extremes, and
the one place the lexicon's own source material goes furthest (Ming
Porcelain's inverted near-white-on-cobalt) has been deliberately
softened. In implementation, pair this with: no glow/blur box-shadows on
bright dark-mode elements (mimics the real artifact), and regular/medium
body-text weight rather than thin in dark mode.

### 1 · Golden Hour (amber) — "warm caregiver"

Inspired by Cozy → Cozy Cocoa. Low-contrast, unhurried, never a
cool-toned black even at its darkest.

| Role | Light | Dark |
|---|---|---|
| bg | `#FBF3E6` | `#140D08` |
| surface | `#F5EAD6` | `#1F150E` |
| outline | `#9C7A54` | `#816545` |
| primary | `#B9722E` | `#D98F45` |
| secondary | `#D9A868` | `#B98A5A` |
| accent | `#5C3D2E` | `#E8C79A` |
| text | `#3A2415` | `#F1E4D2` |
| muted | `#7A654E` | `#927E66` |

Checks — Light: text/surface 12.2:1, muted/surface 4.6:1, outline/surface
3.3:1, outline/bg 3.6:1, primary/surface 3.2:1.
Dark: text/surface 14.3:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 5.5%.

### 2 · Sunlit Focus (amber) — "everyday professional"

Inspired by Extroverted-sunny, pulled slightly less desaturated than the
lexicon's original so it still reads as amber rather than
bleached-neutral. Crisper and less domestic than Golden Hour — daytime,
task-focused.

| Role | Light | Dark |
|---|---|---|
| bg | `#F7F4EC` | `#14120A` |
| surface | `#EFE9D8` | `#1E1B10` |
| outline | `#8E7D50` | `#7A6C44` |
| primary | `#A97D2E` | `#D9A94A` |
| secondary | `#C9A661` | `#B79A66` |
| accent | `#6B4A1E` | `#E9CE94` |
| text | `#3B2E18` | `#EFE7D6` |
| muted | `#706751` | `#8D846A` |

Checks — Light: text/surface 10.9:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 3.1:1.
Dark: text/surface 14.0:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 5.9%.

### 3 · Forest Floor (green) — "wellness-minded"

Inspired by Naturecore → Mossland. Warm yellow-green (H≈75°),
forest-floor register rather than a cold clinical green.

| Role | Light | Dark |
|---|---|---|
| bg | `#F2EFD9` | `#0E1207` |
| surface | `#E6E2C0` | `#171C0D` |
| outline | `#707F42` | `#64723A` |
| primary | `#5E7A34` | `#8CA857` |
| secondary | `#93A85E` | `#A6AE7C` |
| accent | `#7A5A3A` | `#C79868` |
| text | `#23280F` | `#E7E3CC` |
| muted | `#5F654B` | `#7F876A` |

Checks — Light: text/surface 11.6:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 3.7:1.
Dark: text/surface 13.5:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 4.9%.

### 4 · Sage Clinical (green) — "calm clinician"

Inspired by Clinical/Medical's teal register, pulled greener (H≈155°) to
land as green rather than teal — same restrained, low-decoration logic
as Clinical Blue used to carry, just a different hue family.

| Role | Light | Dark |
|---|---|---|
| bg | `#E4ECE6` | `#0A120E` |
| surface | `#D5E2DA` | `#131F18` |
| outline | `#4E826D` | `#477763` |
| primary | `#3F6B57` | `#7DAE93` |
| secondary | `#6E8F80` | `#92AA9E` |
| accent | `#567863` | `#B8D6C4` |
| text | `#223530` | `#DCE7E1` |
| muted | `#53655E` | `#758A81` |

Checks — Light: text/surface 9.7:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 4.6:1.
Dark: text/surface 13.4:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 5.5%.

### 5 · Ink & Ledger (blue) — "methodical & disciplined"

Inspired by Scholastic → Navy Scholastic, deliberately built this session
to *not* resemble Cobalt Porcelain below — an earlier pass paired Cobalt
Porcelain with a more literal Clinical-Blue-style second blue and both
read as "cool professional." Scholastic is "notebook-paper energy," cold
ruled-paper blue as primary, with a signature thin red margin-rule
(`#B33A3A`) carried through as a real second hue rather than a monochrome
accent — for a medication-tracking app this reads less like "efficient
professional" and more like a well-kept ledger/log.

| Role | Light | Dark |
|---|---|---|
| bg | `#F1F2F6` | `#080A10` |
| surface | `#E4E6EC` | `#11141F` |
| outline | `#6579C4` | `#4E64B3` |
| primary | `#1C2D69` | `#5E72BA` |
| secondary | `#5364A2` | `#7A88B8` |
| accent | `#B33A3A` | `#C2564F` |
| text | `#181D30` | `#DCDEE3` |
| muted | `#5E6581` | `#777F99` |

Checks — Light: text/surface 13.4:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 10.3:1, accent(red)/surface 4.7:1.
Dark: text/surface 13.6:1, muted/surface 4.6:1, outline/surface 3.3:1,
accent(red)/surface 4.1:1 (vs bg 4.5:1), bg lightness 4.7%.

The red stays a decorative/large-element accent (dividers, tags, a margin
rule motif) rather than small body text, same restraint the lexicon
applies to its own `#B33A3A` — and warm red doesn't carry the same
chromatic-aberration halation risk that saturated blue does, so it's a
safe place to add a second hue without reopening the astigmatism concern
above.

### 6 · Cobalt Porcelain (blue) — "refined & elegant"

Inspired by Ming Dynasty Porcelain (Blue-and-White). Ink & Ledger covers
the structured/institutional angle, so this one leans further into its
own lane — decorative, curved, luxury-object blue rather than
professional blue. **This is the one family where the astigmatism pass
changed something visible:** the lexicon's own dark register inverts to
near-pure porcelain white (`#F7F7F8`, 15.5:1) on deep cobalt — the
highest-glare pairing in the whole reference document. Softened here to
an off-white at a still-strong but gentler ~13:1.

| Role | Light | Dark |
|---|---|---|
| bg | `#F5F5F6` | `#060A12` |
| surface | `#E8ECF1` | `#121D31` |
| outline | `#6681B0` | `#526F9F` |
| primary | `#24518C` | `#6E90B8` |
| secondary | `#4A6B92` | `#8098B4` |
| accent | `#163A66` | `#C9D4E2` |
| text | `#1E3350` | `#E3E7EC` |
| muted | `#5D6A81` | `#7C8798` |

Checks — Light: text/surface 10.8:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 6.7:1.
Dark: text/surface 13.6:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 4.7%, accent/bg 13.2:1 (softened from the lexicon's 15.5:1).

### 7 · Rose Petal (pink) — "soft & youthful"

Inspired by Coquette/Girly's light register (Rose Petal pairing). Dusty
mauve-rose, dreamiest of the eight.

| Role | Light | Dark |
|---|---|---|
| bg | `#F3E6E9` | `#0F080B` |
| surface | `#EBD3D9` | `#221419` |
| outline | `#A55F71` | `#9A5767` |
| primary | `#9C4F6B` | `#D998AC` |
| secondary | `#C98096` | `#B97F92` |
| accent | `#7C6178` | `#C7B06B` |
| text | `#4A2E38` | `#EEDEE2` |
| muted | `#73565E` | `#937C81` |

Checks — Light: text/surface 8.5:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 4.0:1.
Dark: text/surface 13.7:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 4.5%.

### 8 · Velvet Ribbon (pink) — "confident & editorial"

Inspired by Coquette/Girly's dark/"gothic-girly" register, used as a
light-mode-forward pairing here — richer carmine-and-violet, more mature
and less overtly girlish than Rose Petal, same decorative family.

| Role | Light | Dark |
|---|---|---|
| bg | `#EDE0D8` | `#120A08` |
| surface | `#E3C9BC` | `#241811` |
| outline | `#9A5C53` | `#995C52` |
| primary | `#9E3540` | `#D96B5C` |
| secondary | `#B06B62` | `#C4917D` |
| accent | `#6E5570` | `#A5859E` |
| text | `#34211A` | `#EAD9D0` |
| muted | `#6D504B` | `#9A7D79` |

Checks — Light: text/surface 9.7:1, muted/surface 4.6:1, outline/surface
3.3:1, primary/surface 4.4:1.
Dark: text/surface 12.6:1, muted/surface 4.6:1, outline/surface 3.3:1,
bg lightness 5.1%.

---

## Appendix B — UX audit of the reference app (source findings)

Absorbed verbatim from what used to be `docs/ux-audit.md`, which no longer
exists as a separate file (its history is in git). This is the *source* the
§6 decisions were derived from — kept because the reasoning behind a
confirmed decision is what makes it safe to revisit later, and §6 records
only the outcome. Where the two disagree, §6 wins: it is the decision, this
is the argument that produced it.

Finding #8 here (flat dark-mode text hierarchy) is also the origin of §7's
T2 row.

Based on all screenshots sent. Organized as: what's genuinely good and should carry over as-is, what's confusing and why, and a proposed fix for each. This becomes the spec for the Settings and Add-medicine rebuild.

---

### Keep, unchanged in spirit

- **Linked/timeline Overview** — already agreed, becomes Today's core pattern.
- **Full-card per-medicine color** — already agreed.
- **Icon-per-medicine-type** (Rx bottle, shield-check, gender symbols, radiation, etc.) — genuinely useful at-a-glance differentiation, keep the icon set.
- **Tags as colored chips** on the medicine card — clear, keep.
- **The 5 reminder types themselves** (time-based, continuous-interval, interval-with-daily-window, stock-threshold, expiration) — the *underlying* flexibility is real power, the problem is entirely in how they're presented (see below).
- **Contextual inline hints** — e.g. "color change only affects future events" shown right where the color is set. Good pattern, underused elsewhere.
- **Existing privacy controls** — screenshot capture block, hide-name-in-notification, app-lock, disable-widgets-on-lockscreen. These already exist in the source app; we're not inventing them, just making them easier to find (see Settings restructure below).

---

### What's confusing, and why

**1. Icon-only reminder-type picker.** Adding a reminder means choosing between five icons (calendar, repeat-arrows, a bed/window glyph, a box, a no-entry sign) with zero labels in the picker itself — the explanation text ("Promemoria basato sull'orario", etc.) lives in a *separate* help screen a new user has to go find. Nothing in the picker itself teaches you what you're choosing.

**2. Three same-named "Settings" scopes.** Global *Impostazioni*, per-medicine *Impostazioni farmaco*, per-reminder *Impostazioni avanzate* — three different scopes, nearly identical naming and visual treatment. Nothing signals which scope you're in beyond the screen title.

**3. Nine-item overflow menu on the medicine screen.** *Impostazioni farmaco, Note, Etichette, Impostazioni scorte medicinali, Calendario, Attiva/Disattiva tutti i promemoria, Duplica ×2, Elimina* — all in one kebab menu. Tags and Notes in particular are core, frequently-used features hidden behind a menu that also contains destructive actions (Elimina) right below them.

**4. Stock settings mix three concerns with no visual grouping at the top.** Current quantity/unit, low-stock reminder threshold, refill quantity, and expiration tracking all live in one flat list. The first two fields aren't even under a header — only the lower two sections ("Rifornimento scorte", "Impostazioni di scadenza") are.

**5. Redundant entry points to the same setting.** Stock quantity/threshold is editable from the overflow menu *and* from inside a reminder's own Advanced Settings *and* implied again inside the stock-reminder's own advanced screen. Three doors to the same room.

**6. Flat, undifferentiated global Settings list.** Six destinations (Notifications, Alarms, Snooze, Display, Privacy, Weekend mode) with no section grouping, inconsistent one-line descriptions (some rows get a description under them, most don't), and fairly technical labels with no plain-language gloss.

**7. Truncated toggle label.** "Il farmaco non può essere.." — the actual setting name is cut off in the list; you only learn what it does by tapping in.

**8. Dark-mode text hierarchy is flat.** Headings, values, and helper text all sit at very similar color/weight, especially on the Settings screens — nothing anchors the eye to what's a heading vs. a description vs. a live value.

**9. Add-medicine is a cold start.** The initial dialog asks only for a name, then drops you on a mostly-empty detail screen with a tiny FAB as the only next step — no guided "let's add your first reminder" nudge.

---

### Proposed fixes

#### Settings: collapse three scopes into one mental model

- **App Settings** (global): grouped into visible sections with icons and one-line plain-language descriptions on *every* row, not just some — Notifications & Alerts, Alarm Behavior, Snooze, Weekend Mode, Display & Theme, Privacy & Security. Six destinations, but presented as 6 cards under 2–3 section headers instead of one flat list.
- **Medicine Settings** (per-medicine): renamed in-app to just live inline on the medicine's own screen — color, icon, notification priority, tags, notes — no separate "Impostazioni farmaco" screen at all. If it's about *this medicine*, it's on *this medicine's* screen, full stop.
- **Reminder Settings** (per-reminder): the advanced fields that are genuinely reminder-specific (threshold, interval unit, start-behavior) stay attached to that reminder, but inline/expandable on the same card rather than a second modal on top of a first.

#### Add-medicine: labeled cards, not icon soup

Reminder-type picker becomes 5 cards, each with icon **+ name + one-line description already visible** (no separate help screen needed) — "Fixed times", "Repeating interval", "Daily window", "Low-stock alert", "Expiration alert". Tapping one goes straight to its config, not a second disambiguation step.

#### Medicine screen: kill the 9-item overflow

Notes and Tags become visible inline sections on the medicine screen itself (like Color already is), not overflow items. Overflow menu keeps only what's genuinely rare: Duplicate, Duplicate-with-reminders, Delete — 3 items instead of 9.

#### Stock & expiration: one screen, clearly zoned

Three visually separated zones with icons and headers from the start: **Current stock** (quantity, unit, "runs out ~date" computed inline), **Low-stock alert** (threshold, reminder frequency), **Expiration** (dates, reminder lead time) — one entry point, no duplication.

#### Text hierarchy fix

This falls out of the theme audit we already did — real weight/color separation between heading (bold, full-contrast text), value (medium weight, full-contrast), and helper text (regular weight, secondary/muted tone) — applied consistently across every settings screen, not just some.

---

### One thing I'd flag, not fix silently

The per-medicine "reminders can only be dismissed by marking taken" override and the notification-priority levels (Standard / High / Alarm) are genuinely good power-user features worth keeping *exactly as capable* — the fix here is discoverability and labeling, not removing capability. Personal-use app for real medication adherence shouldn't lose power in the name of simplicity.

---

## Appendix C — Frontend layout & theme palette review (source findings)

Absorbed from the frontend/theme review (Appendix C), which lived outside the
project directory entirely — one folder cleanup away from taking the
reasoning behind §7's T1–T6 with it while those rows went on citing it by
name. The findings themselves are tracked as T1–T6 in §7 and in §6's text
hierarchy bullet; this is the analysis they were extracted from, including
the parts that produced no finding.

Read it as a snapshot of its own session, not as live status: it reviews the
four *outgoing* palettes (Clinical/Cozy/Neutral/Playful) that §6 has since
replaced with the eight in Appendix A, and its prioritized action list is
superseded by §9's roadmap. Its method — the harmony analysis, the
script-verified contrast checks, the register reasoning — is what remains
live, and what a future palette or type change should be re-run against.

Reviewed against: `color-optimization-core`, `accessibility-color-auditor`,
`typography-spacing-core`, `layout-architecture-core` / `app-register-layout`,
and `visual-craft` (design-lexicon).

**What's actually reviewable right now:** only `ui/theme/{Color,Theme,Type}.kt`
and `SettingsScreen.kt` are built. Today/Medicines/History are placeholder
screens per the spec's own §7/§8 status — so those are reviewed against
§6's *described* layout, not shipped code.

---

### 1. Palette provenance — better-grounded than it looks

All four palettes trace exactly to `visual-craft`'s `design-lexicon.md`, not
ad hoc choices:

| Emivita palette | Lexicon entry | Match |
|---|---|---|
| Clinical | *Clinical Blue* (monochromatic, 197–215°) | Exact hex matches: `#d5e5f1`, `#295276`, `#93acc0`, `#304050`, `#ccdbe1` |
| Cozy | *Cozy Cocoa* (monochromatic, ~20–30°) | Exact hex matches: `#956138`, `#f7ead5`, `#442510`, `#cc9159`, `#78502c`, `#f0d3a6` |
| Neutral | *Pure neutral* (achromatic utility fallback) | Exact hex matches: `#f1f1f1`, `#302b26`, `#dbd2cb`, and the original pre-audit accent `#848484` (the code comment even shows this exact hex being corrected away) |
| Playful | *Carnival Pop* (split-complementary + a deliberately off-template magenta accent) | Exact hex matches: `#f57952`, `#dc0582`, `#f0e7db`, `#1c4650`, `#fb4b08`, `#f973a9` |

This matters practically: the lexicon entries already ship their own AAA
contrast credentials (Clinical Blue 7.5:1, Cozy Cocoa 12.4:1, Carnival Pop
8.41:1, Pure Neutral 12:1+), which is why the **primary/background/text**
triad in every palette independently re-checks out strong (below). The
palette selection did its homework. The problems below live specifically in
the roles the lexicon *doesn't* define — outline/border and muted/helper
text — which Emivita had to invent itself.

---

### 2. Harmony analysis (`color-optimization-core`)

Computed actual HSL for every role color and scored angular relationships
against Matsuda's 8 templates:

| Palette | Hue relationship | Template | Harmony score | Note |
|---|---|---|---|---|
| Clinical | Primary/background/tertiary all cluster 197–213° | Monochromatic | ~1.0 | Textbook restrained clinical-blue |
| Cozy | Primary/background/tertiary all cluster 21–37° | Monochromatic | ~1.0 | Textbook warm monochrome |
| Neutral | S≈0% across every role (by design) | n/a — achromatic | n/a | Correctly built as a hue-free utility fallback, not a failed monochrome |
| Playful | Primary 14.5° vs. OnBackground 191.5° ≈ 177° apart (~3° off true complementary); Tertiary magenta at 325° sits ~49° from Primary and ~134° from OnBackground — neither classical offset | Loose split-complementary + off-template accent | ~0.6–0.7 | The lexicon entry says this outright: magenta was "picked as the single boldest accent for maximum pop," not derived from the template. Intentional, not a defect — but it is the loosest-harmony palette of the four, worth knowing if a fifth palette is ever added and needs to sit *between* Playful and the others. |

No changes recommended here — Playful's looseness is a legible design choice
matching its mood, not an error.

---

### 3. Accessibility audit (`accessibility-color-auditor`, script-verified)

Ran `scripts/audit_colors.py` against the two Settings-screen color usages
that are actually shipped in `SettingsScreen.kt` today.

#### 3a. Card border / divider (`MaterialTheme.colorScheme.outline` on white `surface`)

| Palette | Ratio | AA (need 3:1, UI component) | Suggested fix |
|---|---|---|---|
| Clinical | 1.51:1 | ❌ FAIL | `#4b7a9c` (4.6:1) |
| Cozy | 1.68:1 | ❌ FAIL | `#966e39` (4.6:1) |
| Neutral | 1.48:1 | ❌ FAIL | `#767676` (4.5:1) |
| Playful | 1.59:1 | ❌ FAIL | `#9f6b45` (4.5:1) |

**All four fail WCAG 1.4.11 non-text contrast by more than 2×.** Every
`LightOutline` value is a pale tint sitting at 75–90% lightness, checked
against a pure-white (`#FFFFFF`) `LightSurface` in every palette — the
Settings card border and row dividers (`SettingsScreen.kt` lines 125, 132)
will read as functionally invisible in light mode across all four themes,
not just one. This is a systemic pattern (every `LightOutline` was tuned
for legibility *as a background tint*, e.g. against `LightBackground`, not
for 3:1 separation against solid white `LightSurface`) rather than a
one-off miss — worth fixing as a rule (e.g. "Outline must clear 3:1 against
both Background and Surface") rather than four individual hex edits.

#### 3b. Row description text (`onSurface.copy(alpha = 0.6f)` on white)

| Palette | Effective color | Ratio | AA normal text (need 4.5:1) | Suggested fix |
|---|---|---|---|---|
| Clinical | `#838C96` | 3.41:1 | ❌ FAIL | `#6e7782` (4.5:1) |
| Cozy | `#8F7C70` | 3.97:1 | ❌ FAIL | `#847368` (4.5:1) |
| Neutral | `#83807D` | 3.93:1 | ❌ FAIL | `#787573` (4.6:1) |
| Playful | `#779096` | 3.38:1 | ❌ FAIL | `#62797f` (4.6:1) |

**All four fail AA for normal text** (they scrape past the 3:1 large-text
floor, nowhere near 4.5:1). This is the description line under every
Settings row (`"Sound, priority, how reminders behave"` etc.) — real,
shipped, currently-rendering text, not a future concern.

Notably, this is the *exact* problem the UX audit (Appendix B) already flagged
under finding #8 ("dark-mode text hierarchy is flat... nothing anchors the
eye to what's a heading vs. a description vs. a live value") and the fix
that finding called for — "helper text (regular weight, **secondary/muted
tone**)" — was a real muted *color token*, not an opacity multiply. Flat
alpha on a light background is a well-known way to reintroduce exactly the
contrast problem a "muted tone" fix is supposed to solve, and the numbers
confirm it did. Recommend a real `mutedText` role per palette (baked hex,
audited once, like every other role) rather than `.copy(alpha=)` at the
call site — the same pattern the palettes already use correctly for
`onSurface`/`onBackground` everywhere else.

Colorblind simulation (deuteranopia/protanopia/tritanopia) fails uniformly
across all three deficiency types for both issues above — expected, since
these are lightness-contrast failures, not hue-confusion failures; fixing
the base ratio fixes all three simulations at once.

---

### 4. Semantic state-token system (§6) — designed further than it's wired

The spec's state-token table is genuinely well-reasoned color theory —
worth confirming, then flagging where implementation hasn't caught up.

| State | Spec rule | Check |
|---|---|---|
| Error | Accent hue +150–165° (compound/near-complementary), S60/L45 | Correctly named — this is Matsuda template #8, and it's the right choice specifically *because* it guarantees Error never collides with Accent, which a fixed "error red" would risk in Playful (Primary sits at 14.5°, close to conventional error-red) |
| Unselected | Surface −8% L, 1px border at Accent 40% opacity | Sound relative-lightness approach |
| Focus ring | Accent hue, S+10, L−5, 2px outline | Sound |
| Disabled | Surface desaturated to S8–12% | Sound, reuses existing "coming soon" treatment |

Running the Error rule's actual numbers against each palette's real Accent hue:

| Palette | Accent hue | Error hue range (S60/L45) |
|---|---|---|
| Clinical | 208.3° | 358.3°–13.3° (red) |
| Cozy | 26.4° | 176.4°–191.4° (teal) |
| Playful | 14.5° | 164.5°–179.5° (teal) |
| **Neutral** | **undefined (S=0%, fully achromatic)** | **no hue to rotate — the rule cannot execute** |

**Neutral breaks this rule outright.** Its Primary is intentionally
desaturated gray — there's no hue for "+150–165°" to rotate *from*. This
isn't a hypothetical edge case; it's the palette the spec itself ships.
Needs an explicit decision (most consuming apps solve this by giving the
achromatic palette one deliberate fixed hue reserved only for
error/destructive states — the lexicon's own "Pure neutral" entry doesn't
address this either, since it's framed as a two-role utility fallback, not
a full semantic system).

Two implementation gaps, same root cause — **none of this is wired into
`Theme.kt` yet**:

1. `colorSchemeFor()` only overrides `background/surface/primary/
   primaryContainer/secondaryContainer/tertiaryContainer/outline`. It never
   sets `secondary`, `tertiary`, `error`, `onError`, or `errorContainer` —
   so any future component that reaches for
   `MaterialTheme.colorScheme.error` gets Material3's stock default red,
   not a palette-derived hue, silently. Nothing in the shipped code calls
   these yet (confirmed by grep), so it's not visibly broken today — but
   it will be the first time a validation error or a real "error" state
   is built, unless this is closed first.
2. `Color.asOnSurfaceSafe()` is a documented no-op (`return this`) —
   still open per the audit ledger (§7, item §4.4). This is the other half
   of the same promise: §6's Medicine color picker section explicitly
   describes dynamic per-hex text-contrast resolution ("whichever of
   near-black/near-white clears AA... fall back to a subtle scrim").
   That's this function's job, and it doesn't do it yet. Worth sequencing
   before the color-picker UI actually ships (roadmap item likely under
   §9 #1), since an arbitrary user-picked hex is exactly the case with no
   safety net right now.

---

### 5. Typography (`typography-spacing-core`)

| Step | sp values | Ratio |
|---|---|---|
| labelSmall→labelMedium | 11→12 | 1.09 |
| labelMedium→bodyMedium/labelLarge | 12→14 | 1.17 |
| bodyMedium→bodyLarge/titleMedium | 14→16 | 1.14 |
| bodyLarge/titleMedium→titleLarge | 16→19 | 1.19 |
| titleLarge→headlineSmall | 19→22 | 1.16 |

Not a fixed modular scale (all canonical ratios in `type-scales.md` start
at 1.125; several of these steps sit below that). That's not a defect —
it matches Material3's own convention of a hand-tuned, non-geometric type
scale rather than a strict ratio, which is the right call on a compact
mobile screen where a real modular ratio (1.25+) burns vertical space fast.
Line-height ratios are healthy and consistent (1.27–1.43 across every
style, body copy sitting comfortably at 1.38–1.43).

One flag worth naming explicitly: `labelSmall` is 11sp, the smallest size
in the system. `app-register-layout`'s own reference material uses a
medication-adherence "grandfather... complex multi-drug regimen, real harm
if missed" persona as a worked example for exactly this kind of app — and
notes that persona's real need ("larger text, simpler navigation") is an
accessibility concern for `typography-spacing-core`, not a tone concern.
Given that, recommend a hard rule rather than a style preference: dose
amounts, times, and schedule text should never render at `labelSmall`,
regardless of which screen reuses it for chip/badge labels elsewhere.

---

### 6. Layout & motivational register (`app-register-layout`)

`layout-architecture-core` explicitly routes recurring-use, tone-sensitive
app screens here rather than handling them itself — correct fit for a
personal medication reminder app opened daily, not a one-shot document.

**Purpose (Step 1a):** safety-relevant personal medication adherence —
closer to `app-register-layout`'s own "grandfather / safety-critical
adherence, real harm if missed" persona anchor than to its low-stakes
meditation/habit-streak anchor. That distinction matters: a naive
"it's basically a habit tracker" read would default Today's tone toward
~0.15 (meditation-streak territory) and undersell the real-harm stakes
Emivita's own notification-priority system (Standard/High/**Alarm**,
full-screen intent, lock-until-taken) is clearly built for.

| Screen | Tone | Referent | Pattern | Why |
|---|---|---|---|---|
| Today (baseline) | ~0.5 | self-history leaning fixed-target | habit-loop | Continuity-framed daily list (§6's "linked/timeline... aggregate grouped-dose nodes"), but real-harm-if-missed keeps it mid-band, not ambient |
| Today (triggered — full-screen alarm) | ~0.95 | — | status-board | This is exactly what `AlarmActivity` already is architecturally — a separate full-screen Activity for the firing-reminder case. The two-state split the framework recommends is already the shape of the code, even though nobody designed it against this model. Worth keeping that separation explicit as the UI gets built, rather than collapsing it back into Today's own composable. |
| Medicines | ~0.25–0.3 | self-history | reference/card-grid (inherited pattern, not one of the new three) | Inventory/reference screen; low-stock badges are a **per-item**, not per-screen, escalation |
| History | ~0.2–0.25 | self-history | ambient-reflection | Trend-over-time review; no red states, no comparison-to-target |
| Stock & Expiration | ~0.55–0.6 | fixed-target | leans status-board | Threshold/countdown data (run-out date, expiration lead time) is genuinely actionable, not ambient — **§6 doesn't currently draw a register distinction between this screen and History**, and it should: History is a calm trend view, Stock & Expiration is closer to a status board. Worth an explicit design note before both get built, so Stock & Expiration doesn't inherit History's calmer visual treatment by default. |
| Settings | exempt | — | — | Registerless — a preferences list, no ambient/urgent quality |
| Add/Edit Medicine, Reminder sub-forms | exempt | — | single-column (already specified) | Task-completion flows, not recurring-check screens |

One structural note on the forms (`layout-architecture-core`'s
`emphasis_coherence` lens, applied to §6's described field order): putting
Appearance (icon + color) ahead of and heavier than the Name field is a
well-justified `emphasis_primary` choice — it's explicitly tied to the
Medicine-card scan-anchor use case elsewhere in the same section, not an
arbitrary ordering. Coherent as written.

---

### 7. Prioritized action list

1. 🔴 **Outline/border contrast** — every palette's `LightOutline` fails
   3:1 against `LightSurface` (1.48–1.68:1). Affects the shipped
   `SettingsScreen.kt` card borders and dividers today. Fix as a rule
   ("outline must clear 3:1 against Surface, not just Background"), not
   four one-off hex edits.
2. 🔴 **Row description text contrast** — the `onSurface.copy(alpha=0.6f)`
   technique fails AA normal text on all four palettes (3.38–3.97:1).
   Replace with a real audited `mutedText` token per palette — this is
   the fix Appendix B's finding #8 already called for; the opacity
   shortcut didn't deliver it.
3. 🟠 **Wire the semantic-state-token roles into `Theme.kt`** —
   `secondary`/`tertiary`/`error`/`onError`/`errorContainer` are currently
   unset and fall back to Material3 stock colors. Resolve before anything
   reaches for `.error` (form validation, is the obvious first caller).
4. 🟠 **Decide Neutral's Error hue explicitly** — the spec's own
   Accent-relative Error formula is undefined for a fully desaturated
   palette. Needs one fixed reserved hue, by decision, not derivation.
5. 🟠 **Implement `asOnSurfaceSafe()`** — still a documented no-op;
   blocks the AA-safe dynamic text-on-arbitrary-hex behavior §6 already
   promises for the Medicine color picker.
6. 🟡 **Separate Stock & Expiration's register from History's** — the
   former is threshold/countdown-driven (actionable) and the latter is
   trend-driven (ambient); §6 doesn't yet draw this line and both are
   still unbuilt, so it's cheap to decide now.
7. 🟡 **Never render dose/schedule-critical text at `labelSmall` (11sp)** —
   codify as a rule given the app's own safety-critical-adherence persona.
8. 🟢 **No changes needed** to the core Primary/Background/Text triad in
   any of the four palettes — independently re-verified at 7.03–8.26:1
   (AAA) and traceable to vetted `design-lexicon.md` entries. Playful's
   looser harmony score (~0.6–0.7) is a deliberate, documented trade-off
   in the lexicon itself, not a defect.
