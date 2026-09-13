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
| `emivita-palette-candidates.md` | The 8 palettes with tokens and contrast checks | **Appendix A**, absorbed before this pass — verified equivalent entry by entry. Those 8 have since been replaced by 6 (§7 R56); Appendix A now holds the 6, and the 8 survive only in §9 item 0b's record |
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
above, `LinkedFollowing` above). Weekend-mode is **split across both**, and
that split is a deliberate divergence from medTimer (§7 R36):

- *What the weekend is* — the enabled toggle, the day-of-week set, and the
  time window — stays a **global preference**, exactly as medTimer's
  `WeekendModeSchedulingDecorator` reads it. A window describes the user's
  week, not any one medicine, and duplicating it per reminder would let two
  reminders disagree about when Saturday morning ends.
- *Whether a reminder is shifted by it* is a **per-reminder opt-in**,
  `Reminder.weekendModeOptIn: Boolean = false`. No `ReminderConfig` slot: it
  is cross-cutting like `autoResolve` and the active-range bounds, so it
  lives on the row alongside them rather than inside any one type's JSON.

medTimer has no such flag — `SchedulingFactory` wraps every scheduler it
builds in the decorator unconditionally, so the setting there is
all-or-nothing across every medicine. That is the wrong default for a
schedule mixing a supplement that is happy to wait with a drug that is not:
one switch made buying a lie-in on the first cost a delayed dose of the
second. Diverged knowingly, not by oversight. See below.

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
picker's other UX details). ✅ **Settled (§7 R47)** — the question was
whether the picker surfaces a live differentiation hint before Ok, or
leaves it entirely to the user's judgment. It does both: a hint that
recomputes as the user drags, names the one medicine the candidate is
nearest to, and never disables Ok, because two medicines the user wants
in similar colours (a morning and an evening dose of the same drug) is a
real case rather than a mistake to prevent. The wording above says the
test is *contrast* dropping too low, and that is deliberately not what
was built: contrast ratio is defined on relative luminance alone, so it
is blind to hue — a pure red and a mid green sit at nearly equal
luminance and score about 1:1 while being unmistakable, and two greys one
step apart score comfortably while being genuinely hard to tell apart.
It is the right metric for text on a background, which is what §6 and
Appendix A use it for, and the wrong one for two adjacent fills. The
question being asked is perceptual distance, so the implementation is
CIELAB ΔE*ab (CIE76) at a threshold of 15 — see
`ui/components/ColorDistinguishability.kt`.

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
neutral fallback, since it's the same component, or gets its own — still
hasn't come up. The clause that used to close this sentence, "because no
Profile UI is scheduled in §9 at all", stopped being true with §9 #18: there
is a Profile UI now. It does not pick a colour, though — Medical ID edits
name and the seven card fields only — so the question is still genuinely
untouched rather than merely unasked.)

**`Profile`'s emergency/medical-card fields** (§9 #18, §7 R48), all
`String?`, all `MIGRATION_5_6`: `bloodType`, `allergies`, `doctorName`,
`doctorPhone`, `doctorAddress`, `pharmacyName`, `pharmacyPhone`. Every one
is informational text the app never parses, validates or checks against
anything — the boundary §9's parking-lot entry fixed, and what keeps the
exported card a document rather than a clinical claim. Nullable rather than
`NOT NULL DEFAULT ''` on purpose: `EmergencyCardExporter` omits an entire
contact block when its fields are absent but prints "Not recorded" for blood
type and allergies, so "not filled in" and "filled in as empty" have to stay
distinguishable at the column.

**`Profile.name` is now displayed**, on exactly one surface. `EmivitaApp`
creates the single profile silently as `"Me"` and its own comment justifies
that fixed string on the grounds that the name is never shown; the emergency
card prints it at the top. Medical ID is where the two are reconciled — it
treats `"Me"` as an empty field rather than a chosen name and refuses to
export until a real one is entered. Nothing else reads the name, so this is
not a reason to bring onboarding back.

Weekend mode is applied as the last step in
`SchedulingEngine.nextOccurrenceFor()`, after the type-specific calculator
and the `activeFrom/UntilEpochDay` clamp: if the reminder has opted in
(`Reminder.weekendModeOptIn`) **and** the global toggle is on, and the
computed occurrence's day-of-week is in `weekendDays` with its time-of-day
in `[weekendStartTime, weekendEndTime)`, push it to `weekendEndTime` on that
same day; otherwise leave it untouched.

The per-reminder gate is checked first, so a reminder that has not opted in
never reads the weekend preferences at all — which keeps the "a disabled
feature costs one Flow read" property `readWeekendMode()`'s own doc comment
describes, and for the default case costs zero.

The push itself is ported faithfully from medTimer's decorator. The *gate* is
not: `SchedulingFactory` wraps every scheduler regardless of type, with no
opt-out anywhere. Emivita diverges here on purpose — see §7 R36 for why, and
§5's reuse-map row, which is corrected to match.

One consequence of the push worth stating, unchanged by the opt-in and
**still not endorsed**: because the projection walk advances its floor past
the *pushed* time, several occurrences falling inside one weekend window
collapse into a single occurrence at the window end rather than stacking up
behind it. An interval reminder with three slots inside the window yields
one dose, not three. Faithful to medTimer, recorded as behavior. The opt-in
at least makes it something a reminder has to ask for.

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
| **`WeekendModeSchedulingDecorator`** (global preference + unconditional final adjustment step, not a per-reminder field) | Adjustment *math* adopted faithfully into `SchedulingEngine` (§3/§4) after actually reading the implementation rather than assuming it was per-reminder config. The **unconditional** half is deliberately **not** adopted: `SchedulingFactory` wraps every scheduler with no opt-out, and Emivita gates on `Reminder.weekendModeOptIn` instead (§7 R36). The one row in this map where faithfulness was the wrong call — a reuse map is for deciding what to port, which includes deciding what not to |
| **`RefillProcessor`** (one-tap restock using a preset amount, logged via the same stock-sentinel mechanism) | Adopted as `DoseStatus.REFILLED` + `logRefill` (§3) — `StockInfo.refillQuantity` already existed in the schema, only the behavior was missing |
| **`Medicine.sortOrder`** (fractional-double reordering, no renumbering on drag) | Adopted verbatim (§3) — was missing from the entity entirely |
| Widgets | Portable *concept*, not code — medTimer uses `AppWidgetProvider`/RemoteViews; Emivita uses **Glance** (Compose-based), per the manifest's own forward-looking comment. Rebuild in Glance idiom. |
| De-googled `foss` flavor pattern (`NoOpLocationProvider`/`NoOpGeofenceRegistrar`) | Reference pattern for how to no-op location-based snooze — not yet needed since that feature isn't planned |
| Biometric lock (`Biometrics.kt`) | Small, portable near-verbatim when built |
| Entire UI layer (Fragments, XML, Navigation Component, PreferenceX, TableView/androidplot/IconDialog) | **Not portable** — functional spec only, every screen is original Compose work |
| DI (Hilt) | **Not adopted** — see §2 |
| DB encryption | medTimer has none; Emivita's SQLCipher layer is original, not ported |

### 5.1 Code-level pass — 2026-09-13 (§7 R60, Appendix E)

The rows above are kept as written. This subsection records where the
function-level diff found them wrong or incomplete, so the map stays a
map and not a memory.

| §5 row | What the code-level pass found |
|---|---|
| Scheduling/alarm/notification — "ported" | Ported in outline, not in behaviour: the alarm tier did not ring (R59), an outstanding ON_RESOLVED dose did not block re-arming, a late alarm replayed missed slots, a ONCE expiry alert never re-armed (R60). Thirty of medTimer's scheduler tests now run against Emivita's engine (`core/scheduling/medtimer/`); the divergences that remain are asserted, not assumed (A-06, A-09, A-20, A-23). Days-of-month, `createdTime` guards and overnight windows are absent (A-13, A-06, A-20) |
| `FutureRemindersRepository` pattern | Confirmed, with two edges: Emivita's window is a floor where medTimer's is a fallback (A-39, Emivita better); Emivita's compute budget is checked per medicine, medTimer's per reminder (A-41); linked children contribute at most one dose to the projection (A-24, medTimer better) |
| **Entire UI layer — "not portable"** | **Stale.** medTimer's medicine list (`MedicinesScreen.kt`, drag-reorder via `sh.calvin.reorderable` + `MedicineRepositoryImpl.move()`), Statistics screen (charts, sortable/filterable table, calendar with simulated future), `SortableTable` and theme are Compose. Emivita has the `sortOrder` column and no reorder UI (C-26, D-01/02). `reorderable` is not in the offline cache; Vico is 2.1.0 here vs 3.2.3 there, and Vico 2.1.0 has no pie chart (D-25) |
| Stock sentinel — "adopted verbatim" | Same type and default; different semantics. medTimer writes `stockBefore` at raise time and never reads the pair for undo (it re-parses the amount string); Emivita writes it at resolution and `switchStatus` depends on it (C-06, C-08). Emivita's is the sounder design; the word "verbatim" was wrong |
| `Medicine.sortOrder` — "adopted verbatim" | The column and the backfill; not `move()`, not the sort menu, not the UI (C-26, C-27) |
| PRN / additional dose | medTimer's version can be backdated and named; Emivita's logs at `now` for a listed medicine only (C-15) |
| Biometric lock — "near-verbatim when built" | Built differently: medTimer binds a Keystore key to the prompt and falls back to device credential, failing closed; Emivita is a plain `BIOMETRIC_STRONG` prompt that fails open when nothing is enrolled (E-30..33). Neither is a copy of the other |
| DI, DB encryption, widgets-as-Glance, de-googled flavor | Confirmed as recorded |
| Not in §5 at all | Notification behaviours (B-05..B-12, B-39..B-45), reminder management (C-27..C-29), tag filtering (C-32), history editing (C-09..C-14), backup compatibility (E-05), CI and quality gates (F-18..F-30). Appendix E carries the rows; §9 #21 carries the owner's pick |


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
  are resolved relative to whichever of the 6 palettes' Accent is active
  (was 4, then 8 — see §7 R56 for the current set), not new fixed hues per
  palette. The old edge case this table used to carry — Neutral's Accent
  being fully desaturated, leaving no hue for the Error rule to rotate from
  — is moot rather than fixed: Neutral was dropped, and every replacement
  palette carries a real accent hue.

  **The Error row is no longer a rotation, and that is the important part.**
  The rule below once read "Accent hue +150–165°, S60/L45", implemented at
  +158°. R42 removed it because a hue chosen to stay in harmony with the
  accent cannot also carry the meaning *something is wrong* — across the
  eight palettes it produced teal, cyan, magenta, blue, and green in four
  of them, for the role `AdherenceChart` painted missed doses with. R42
  replaced it with one fixed red shared by all eight.

  **R56 then narrowed what `error` is allowed to mean at all.** It was
  doing four jobs and three were not errors — the missed-dose chart series,
  the past-due calendar dot, and a note reporting that an *optional*
  barcode-scanner app is not installed. Two roles were split out of it
  (`attention`, `neutralFill` — Appendix A defines both), and `error` now
  covers genuine failures only: form validation, a failed backup, a
  destructive confirmation. Each palette carries its own error rather than
  sharing one, because Linen's terracotta primary needs an oxblood to stay
  distinguishable from it. This is the patient-facing register's governing
  rule made mechanical: red is reserved for emergencies.

  | State | Rule |
  |---|---|
  | Selected/active | Accent fill, text auto-picked (dark if Accent L>60%, else light) |
  | Unselected | Surface −8% L, 1px border at Accent 40% opacity |
  | Focus ring | Accent hue, S+10, L−5, 2px outline, no fill |
  | Error | A per-palette red, tabled in Appendix A. **Not** derived from Accent, and not used for any state that is merely notable (§7 R42, R56) |
  | Attention | A missed dose, low stock, an approaching expiry. Warm clay/amber, never red (§7 R56) |
  | Neutral fill | A skipped dose and other deliberate non-events. Near-achromatic (§7 R56) |
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
- **Settings:** destinations grouped under section headers, not a flat
  list — matches the already-built `SettingsScreen.kt` groupings exactly.
  Every row gets a plain-language one-line description, not just some.
  (This said "6 destinations" until §9 #18 added Medical ID as a seventh.
  The count is deliberately not restated: it is a number that changes every
  time a destination is added, in a bullet whose actual content is the
  grouping rule, and `SettingsScreen.kt`'s `settingsGroups` is the list.)
- **Text hierarchy:** heading (bold, full-contrast) / value (medium
  weight, full-contrast) / helper text (regular weight, muted) —
  consistent across every screen, not just some. **✅ Now a rule
  (§7 T6 → R42, applied at the call sites by R47):** dose amounts, times and
  schedule text never render at `labelSmall` (11sp, the system's smallest
  size), regardless of which screen reuses that style for chip or badge
  labels elsewhere — flagged originally against the app's own
  safety-critical-adherence purpose (§1). The floor is named
  `Typography.doseCritical` in `Type.kt`, beside the ramp anyone choosing a
  style is already reading rather than in a document they are not, and
  resolves to `labelMedium` (12sp) so raising it later is one edit.
- **Power-user features kept at full capability, just better labeled:**
  per-medicine "can only be dismissed by marking taken"
  (`Medicine.remindersLockedUntilTaken`, overriding a new global default —
  §3; *not* `DoseGroup.priority`, which is a separate, softer
  notification-loudness default — corrected here after conflating the two
  earlier), and the three notification priority levels (Standard/High/
  Alarm) — discoverability is the fix, not simplification.
- **Palettes: 6, each with light + dark.** This is the second full
  replacement. The original 4 (Clinical/Cozy/Neutral/Playful) went first,
  because they had been picked without discussion; the 8 that replaced them
  went in turn (§7 R56), because the *register* was never settled — they
  were built palette-by-palette from lexicon entries chosen for variety
  (amber, green, blue, pink, two of each) rather than from one decision
  about what kind of app this is.

  It is a patient-facing medication app, which puts it squarely in
  `visual-craft`'s **Health / Consumer Wellbeing** register, and that
  register carries two obligations the old eight did not answer to: grounds
  stay **warm**, because the clinical blue-grey that reassures a
  professional reads as institutional to someone managing their own
  medication; and **red is reserved for genuine emergencies** — never an
  accent, never a brand colour, never the marker of an ordinary event. All
  six below answer to it, including the two cool ones, which keep warm
  grounds for exactly that reason.

  | Palette | Family | Character | Source |
  |---|---|---|---|
  | Daylight | Green | The quiet default | Health/Consumer Wellbeing → *Daylight* (verbatim) |
  | Sage | Olive-green | Softest of the six | Health register, Naturecore's herbal band |
  | Honeycomb | Amber | Warm without glare | Health register, Cozy's amber band |
  | Linen | Terracotta | Warmest of the six | Health/Consumer Wellbeing → *Linen* (verbatim) |
  | Mallow | Plum | Soft, not sweet | Health register, Coquette/Girly muted |
  | Periwinkle | Indigo | The one cool option | Health register, Scholastic indigo, warmed |

  Two are the register's own verified pairs taken verbatim; the other four
  are hue-anchored within it, spaced so the six stay mutually
  distinguishable in the picker (worst pair ΔE 14.0, Daylight/Sage).

  **What the register cost, and it is worth recording.** Non-alarming
  pushes every state into the warm desaturated middle — which is precisely
  the band where deuteranopia and protanopia collapse hues together. An
  early draft had *taken* and *missed* at ΔE 0.9: indistinguishable. The
  fix was structural rather than tuning, and both halves matter: make
  `skipped` **achromatic**, since a skip is a deliberate non-event and
  should not compete chromatically with the states that do mean something;
  and separate on **lightness**, which no colour-vision deficiency
  destroys, rather than on chroma, which is the axis that reads as alarm.
  Chromatic / neutral / chromatic now clears ΔE 15.7 worst case across all
  twelve registers including simulated CVD, against a categorical floor of
  10.

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

  Full token tables (all 6 × light/dark, every role, every contrast ratio,
  computed not eyeballed) are inlined in full in **Appendix A** at the end
  of this file. There is no separate palette file anymore — it was merged
  in here and retired, specifically so the actual hex values can't end up
  living in a second document that goes stale or doesn't get re-uploaded
  next session.

  ✅ **The default is now a decision, not an accident (§7 R56).** It is
  **Daylight**, and the reason is not that it is listed first: it is the
  Health/Consumer Wellbeing register's own documented default pair, taken
  verbatim, and it is the most nearly neutral of the six — the one that
  presumes least about a user who has not chosen. The previous default,
  Golden Hour, was explicitly arbitrary ("first-listed above, arbitrarily,
  not a design decision"). Note the one hand-maintained coupling this
  creates: `res/values/colors.xml`'s `splash_background` must match the
  default palette's light background (`#FBF9F5`), and nothing enforces it —
  a mismatch flashes between the splash screen and Compose's first frame.
  The history is worth keeping, because it shows the question surviving a
  fix that looked like it would settle it. This bullet once offered two
  exits: an onboarding prompt, or persisted preferences. Persistence landed
  (§9 #12 — `SettingsRepository.palette` through DataStore, chosen in
  `AppearanceScreen`) and the onboarding screen was deleted (§9 #1's
  reversal), which closed one exit and left the other untaken — so a fresh
  install still took an arbitrary default silently. **Persistence only
  records a choice once one is made.** What actually settled it was picking
  a default *on purpose*, which is what R56 did: not the first-listed
  palette, but the register's own documented default and the most nearly
  neutral of the six. `SettingsRepository.palette` falls back to `DAYLIGHT`
  for any name it cannot parse, which now also covers a palette name saved
  by a build that still had the previous eight.
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
reasoning that produced the original stays readable.

**Retention policy (applied 2026-09-06).** Append-only protects the record of
what was found, not the length of the narration, and at 132 KB the ledger had
become the thing nobody reads before the work that would break it. Resolved
rows are therefore kept to their finding, their outcome, and anything a reader
needs in order not to re-break them; the rule each one produced is lifted into
**§7.1** where it can be read up front. No row was removed, no finding was
dropped, and every ❓ row is untouched. Implementation detail beyond that is in
git, which is a better place for it than a table cell. The **Superseded by**
column is how you tell without replaying the whole table — if it is
populated, read the row it names before acting on this one. It is only
used where a later row corrects or invalidates the subject — not for a
restructuring that leaves the original conclusion standing, and not for
merely related reading.


### 7.1 Rules this ledger produced

Every rule below was paid for by a defect in the rows underneath. They are
collected here because a rule buried mid-way through a 4,000-character table
cell is a rule nobody reads before the work that would break it. The row
reference is where the evidence is.

1. **A permission that is declared and gated but never *requested* is a silent
   no-op.** `POST_NOTIFICATIONS` (R14) and `SCHEDULE_EXACT_ALARM` (R21) each
   shipped this way — read correctly at every call site, requested nowhere, so
   on a fresh install the app's entire purpose silently failed. Check the
   request path exists, not just the check. (R14, R21)
2. **A permission claim is only checkable against the *merged* manifest, never
   the source one.** (R21)
3. **Two full-row writes describing one edit is a lost update waiting to
   happen.** Editing a reminder silently did nothing for exactly this reason:
   `@Update` writes every column, so the second write restored what the first
   had changed. The fix is one write, not a re-ordering. (R23)
4. **Fixing a defect *class* one instance at a time leaves the siblings.** Only
   a test that enumerates the cases finds them. (R24)
5. **A green suite that never exercised the code under test is worse than no
   suite**, because it cancels the gate that made green mean something. Assert
   the property, not a proxy for it — R55's hatch defect would have passed an
   assertion that the three series were *different colours*. (R26, R28, R55)
6. **A UTC-only test suite structurally cannot catch an offset bug.** The
   scheduling suite runs in `Europe/Rome` deliberately. (R24)
7. **Documentation consistent across every call site is not evidence.** Three
   times running — R25, R27, R28 — every site agreed with every other and all
   of them rested on a premise that did not survive one check against the
   shipped code. Consistency is what a wrong shared assumption looks like.
8. **A ledger row marked resolved because the *mechanism* shipped proves
   nothing about the call sites.** R47 found two such rows; R46 had reached one
   of History's three surfaces. Verify the call sites migrated. (R46, R47)
9. **Agreement between two derived signals is not corroboration when they share
   a cause.** A build piped to `tail` reports *tail's* exit code, and stale
   `test-results` XML keeps reporting the last green run — together they
   reported a passing build three times while compilation was failing. (R57,
   and the same shape as R47)
10. **Colour chosen for harmony cannot also carry meaning.** A hue rotated to
    stay harmonious with the accent cannot mean *something is wrong*; the rule
    produced teal, cyan, magenta and green for the role that paints missed
    doses. Two independent failures of one rule is the rule being wrong, not
    the palettes. (R40, R42)
11. **Red is reserved for genuine emergencies** — never an accent, never a
    brand colour, never the marker of an ordinary event. This is the
    patient-facing register's governing rule and the reason `attention` and
    `neutralFill` exist. (R56)
12. **A colour-alone ΔE floor governs only where colour is the sole channel.**
    What overturns a "colour is the second channel" judgement is any surface
    conveying state with no icon or label beside it — which is exactly what
    `AdherenceChart` turned out to be. (R53, R54, R55)
13. **In a non-alarming register, separate on lightness, not chroma.**
    Lightness is the axis colour-vision deficiency spares; chroma is the axis
    that reads as alarm. (R56)
14. **`lintDebug` is a gate, not a report**, and CI must actually run — every
    workflow run in this repository's history failed at its first step with
    `Permission denied` while the README claimed the gates ran remotely. (R26,
    R38)
15. **One project, one build.** The `.debug` application-id suffix let a
    migration check run against a leftover database and read as a pass either
    way. (R41)
16. **`designkit` falls back to a local search silently** when
    `optimization-engines` is unreachable, and the fallback never evaluates
    `constraints` — so every fitness number quoted before R50 was computed with
    the constraint set switched off. Set `OPTIMIZATION_ENGINES_HOME` and check
    `dk status` says `portfolio` before trusting a number. (R50)

17. **A guard that degrades safely but silently is a defect the guard
    hides.** Not crashing is the easy half. `postIfPermitted` refused to post
    without permission and logged one line nobody reads; the projection cache
    published a partial pass as though it were whole; `applyWeekendMode`
    quietly turns "defer" into "skip" on a sub-daily reminder. Each is
    correct in the sense of not throwing, and each leaves the app looking
    entirely healthy while doing less than it claims. Rules 1 and 5 are the
    same shape one level in. The test is not "does this handle the failure"
    but **"can anyone tell that it did"** — and if the answer is a log line,
    the answer is no. (R58)

18. **A device pass is for what the suite cannot construct, and the owner
    finds a different class of defect than the agent does.** R59's automated
    half found seven strings and confirmed the accessibility tree; the owner,
    in a few minutes of ordinary use, found a column no screen could set, a
    header no test compared to its neighbours, a row that only squeezes at
    phone width, and a channel created with the wrong defaults. Robolectric
    asserts on nodes it built; a person taps what is there. Neither replaces
    the other, and a pass that spends the owner's minutes on what a screenshot
    can do (rule 17's cousin) wastes the scarcer input.

19. **A reference implementation's tests are a better audit than its
    code.** Reading medTimer's scheduler beside Emivita's produced 48 rows of
    careful prose and no defect; running medTimer's *tests* against Emivita's
    engine produced four failures in a minute, each for the reason the test's
    own name gives. A test encodes the case its author was burned by, which
    is exactly the case a fresh implementation has not met yet. When a
    reference exists, port its tests first and read its code second. (R60)

---

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
| T4 | **See R42, which supersedes this.** The Error state-token rule (§6 above, "Accent hue +150–165°") has no hue to rotate from against Neutral's fully-desaturated Accent — found in the frontend/theme review (Appendix C), this session | ✅ Resolved — moot, Neutral dropped entirely (§6); all 8 replacement palettes carry a real accent hue |  |
| T5 | Stock & Expiration and History share no explicit `app-register-layout` register distinction, though their actual data (threshold/countdown vs. trend-over-time) calls for different ones — found in the frontend/theme review (Appendix C), this session | ✅ Resolved — Stock & Expiration shipped with distinct tonal-container zoning per section (§6); audited against History's plain-list treatment and found no conflict — the two screens now read differently by construction |  |
| T6 | **Resolved by R42.** `labelSmall` (11sp) has no rule against rendering dose/schedule-critical text at that size, given the app's own safety-critical-adherence purpose (§1) — found in the frontend/theme review (Appendix C), this session | ❓ Still open — logged in §6, not yet made a hard rule | **R42** — closed there; this Status was never updated |
| R10 | Reconciliation across five separately-exported zips found a genuine fork: `Emivita_edit_medicine_reminder_batch` and `Emivita_rename_and_palette_batch (3)` both unpack to the same folder name but diverged in 17 shared files (schema v1→v2, `sortOrder`/`barcodeValue`/`indication`/`storageNote`, `RepeatConfig`, nullable `DoseEvent.reminderId`, the `addTag()` transaction fix (R1), the 4 new screens, etc.) | ✅ Resolved — confirmed the edit-medicine-reminder branch is a strict forward evolution of the rename/palette branch (nothing in the older branch's versions of those 17 files was missing from the newer branch), so it was taken as the base. The older branch, `MedKeeper_scheduling_engine_batch` (pre-rename ancestor), and `Emivita_missing_resources_patch` were all fully superseded — nothing in any of them was absent from the base — and are retired from the working tree as of this pass. Only `Emivita_launcher_icon_patch`'s hdpi/mdpi foreground PNGs (§7 row above) contributed anything the base didn't already have. |  |
| R11 | `EmivitaDatabase` declares `exportSchema = true` (needed for Room migration testing) but no `app/schemas/` directory exists in any of the reconciled zips, and `app/build.gradle.kts` has no `room { schemaDirectory(...) }` block pointing Room's annotation processor anywhere | ✅ Resolved — the `ksp { arg("room.schemaLocation", ...) }` wiring turned out to already be in place (this row's premise was stale by the time it was actually checked); running a build with it confirmed `app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase/{2,3}.json` now export for real (no `1.json` — this source tree never compiled `EmivitaDatabase` at version 1, so there was never a schema for Room to have captured at that version). The part that was actually missing — real test coverage exercising the `Migration` objects, not just a baseline sitting unused — is closed by `MigrationFrom1To2Test`/`MigrationFrom2To3Test` (`app/src/test/java/.../core/data/db/`): both build the "before" table shape from raw SQL and run the real `Migrations.MIGRATION_1_2`/`MIGRATION_2_3` objects against it, asserting on the transformed data (colorArgb→hex incl. the negative-Int bitmask case, sortOrder backfill, denormalized `dose_events.medicineId`, the new nullable column). `MigrationTestHelper` (schema-JSON-diff-based, the more rigorous option for MIGRATION_2_3 specifically, since both its endpoints' schemas exist) was tried first and dropped — it hit a Room 2.8.4/Robolectric driver bug (`IllegalArgumentException: This driver is configured to open a database named 'X' but '<path>/X' was requested`, thrown before any migration logic runs) unrelated to this app's schema; the direct-fixture technique used instead is a real regression test either way, just not diffed against the exported JSON. Both tests run and pass under `./gradlew testDebugUnitTest`. The one thing this row's coverage structurally cannot address — an in-place upgrade of a database written by a real previous build — is tracked in §9 #1, and is no longer blocked: a signed versionCode 1 APK is archived at `dist/vc1/`. |  |
| R12 | `README.md` still described the pre-#1 (data-layer-only) batch — "not built yet: PRN, projection cache, History, low-stock/expiration alerts, backup/restore, biometric lock UI" — directly contradicting §9's ✅ marks on items 2/4/6/8/9/11, the exact stale-docs pattern R6 already named once (found during a routine "what's next by dependency" pass, not a code review) | ✅ Resolved — before rewriting, verified against the actual source rather than trusting either doc: every claimed-✅ §9 item (1–16) really is implemented with working logic, not a stub, and #13 (Health Connect) is genuinely absent. `README.md` regenerated to match. Same pass also removed a harmless but stale leftover: `app/proguard-rules.pro` still `-keep`/`-dontwarn`'d `androidx.health.connect.client.**`, a class no longer on the classpath since #13 was dropped |  |
| R19 | §8's closing paragraph still read "Not built — literal placeholder text, no data-entry UI exists at all: `TodayScreen`, `MedicinesScreen`, `HistoryScreen`" — flatly false since #1 shipped, and the third recurrence of the pattern R6 and R12 already named (found while advising on next steps, not by a doc review). It survived the reconciliation pass *and* R12's README rewrite because R12 fixed the copy in `README.md` and left the copy in §8 | ✅ Resolved — and treated as a structural defect rather than another stale paragraph: the recurrence is caused by §8 and §9 both recording build status, so every batch has two places to update and reliably updates one. §8 is now explicitly not a status ledger (§9's checkmarks are), keeping only the per-screen detail §9's one-line entries cannot carry. Same pass corrected §9 #1's "no v1-era APK left to install-then-upgrade from" and `README.md`'s "this zip is not a buildable Gradle project" and "no archived v1 APK" claims, all three falsified by this session's work |  |
| R13 | §5/§9 #6 called for a Vico "pie + stacked bar" adherence chart, but the pinned `com.patrykandpatrick.vico:compose-m3:2.1.0` dependency has no pie chart at all — Vico 2.x's cartesian module ships line/column/candlestick layers only; the pie layer existed in Vico 1.x and was dropped in the 2.x rewrite (found by extracting and inspecting the resolved `.aar`'s `classes.jar` directly, not assumed from the library's reputation) | ✅ Resolved — built the one shape the pinned version actually supports and the one that fits the data better anyway: a stacked daily bar (taken/skipped/missed per day, `ColumnCartesianLayer` + `MergeMode.stacked()`) reading as a real trend over the visible window, not a single-window aggregate a pie would give. Matches T5's own "History's data calls for a trend-over-time register" finding more directly than a pie would have. `AdherenceChart.kt` (`feature/history/`), wired into `HistoryScreen.kt` between the adherence-summary card and the entry list, hidden when every day in the window has zero taken/skipped/missed. Colors: Taken=`primary`, Skipped=`tertiary`, Missed=`error` (a fired-and-unresolved dose is the one outcome worse than a deliberate Skip, so it's the one that gets the app's reserved alarm-adjacent color) — all resolved through `MaterialTheme.colorScheme`, so the chart repalettes correctly across all 8 palettes/both theme modes with zero chart-specific color code. `app/build.gradle.kts`'s stale "pie + stacked bar" comment corrected to describe what's actually available and actually built. |  |
| R18 | User requested a dose-measurement label next to every dose amount, on the same device-testing session — assessed via `architecture-designer` before implementing (user explicitly asked for a skill-backed assessment) rather than guessed at, since the obvious shortcut (reuse the existing `Medicine.stockInfo.unit`) turned out to already be overloaded elsewhere as a stock-tracking-enabled flag (confirmed by reading `DoseEventRepository`/`LowStockExpirationAlertEvaluator`/`OccurrenceProjectionCache`'s actual conditionals, not assumed) | ✅ Resolved — new, separate `Medicine.doseUnit` field (§3), `MIGRATION_3_4`, threaded through 6 entry points and 4 display points (§3's own entry lists them). Full test suite + `assembleDebug` pass; device-verified end-to-end on the OnePlus 6T (Add Medicine's Measurement field, a reminder form's suffix hint, the Log dose dialog's suffix hint, and the resulting text on Today/History List/History Table). |  |
| R17 | Launcher-icon foreground artwork (clock + two capsules + "zzz" sketch, `ic_launcher_foreground.png`, all 5 densities) extended to ~67% of the adaptive-icon canvas's half-width from center — past the standard 61% (66dp/108dp) adaptive-icon safe-zone guideline, and visibly clipped by `core-splashscreen`'s own circular icon mask (used on this minSdk-30 device since it's below the real platform SplashScreen API at 31+) — confirmed by screenshotting the actual splash on the OnePlus 6T mid-launch, not assumed; user flagged the visible clipping | ✅ Resolved — content rescaled/recentered (measured via the PNG's own alpha channel, not eyeballed) to ~50% of canvas half-width, a safety margin below even the 61% guideline, then all 5 density PNGs regenerated by downscaling the corrected xxxhdpi source (not independently shrinking each existing density, to avoid compounding per-density rounding). No shape swap attempted — confirmed there's no supported `windowSplashScreenIconShape`-equivalent attribute; `core-splashscreen`'s compat path hardcodes a circular mask, so the fix is designing the art to fit it, the same discipline adaptive icons already require for launcher-mask compatibility. Device-verified: relaunched on the OnePlus 6T, clock/both bells/the zzz mark/both capsules all now fully visible with clear margin, confirmed via screenshot. |  |
| R16 | `com.kizitonwose.calendar:compose`'s current latest (2.10.1) is compiled with a Kotlin compiler whose metadata this project's pinned Kotlin 2.1.0 plugin (root `build.gradle.kts`) can't read — `kspDebugKotlin` failed with "Module was compiled with an incompatible version of Kotlin... binary version... 2.3.0, expected... 2.1.0," confirmed by actually trying 2.10.1 against this build, not assumed from a changelog | ✅ Resolved — pinned to `2.7.0` instead (§9 #6, this batch), the newest release confirmed to compile clean against this project's Kotlin 2.1.0; `compileDebugKotlin`, the full `testDebugUnitTest` suite, and `assembleDebug` all pass. Revisit the pin once the project's own Kotlin version moves (§1's toolchain table), not before |  |
| R15 | §4's "Deliberately not built yet" list (projection cache, the five §3 gap-resolution fields, `CyclicActivePause`/`LinkedFollowing`/weekend-mode + both cascade-reschedule trigger points) was still describing all of it as unbuilt, directly contradicting §9 #4/#14's own ✅ marks and the actual source (`OccurrenceProjectionCache.kt`, `SchedulingEngine.kt`, `AddReminderScreen.kt`'s 4-card picker) — the same stale-docs pattern R6/R12 already named, found during this session's "what's next by dependency" pass | ✅ Resolved — §4 rewritten to state plainly that list is stale and point at the real implementation; only the two genuinely-still-open items (`AlarmActivity` visual design, boot-reschedule on-device confirmation) kept as open |  |
| R14 | First full audit pass (`android-app-auditor` + `code-reviewer` + `code-verification-core`) — a systematic security/permission/manifest/build/logic sweep of the whole app, not a diff review | ✅ **Mostly resolved**, a few items left for follow-up (taken up by R20/R21). Two criticals: `POST_NOTIFICATIONS` was gated everywhere and requested nowhere, so on a fresh Android 13+ install no reminder would ever show (§7.1 rule 1); and `AppLockGate` defaulted `lockEnabled` to `false` while DataStore loaded, rendering unlocked content for the first frames of a cold start — now a nullable tri-state with a blank loading frame. Also: quiet-hours windows spanning midnight never triggered, unguarded `applicationContext` casts in all four receivers, and passphrase handling moved off `String` onto wipeable `CharArray`. Blow-by-blow in git | **R20, R21** — two items overturned |
| R20 | `res/xml/data_extraction_rules.xml` was deleted as a dead template — true for cloud backup, false for device-to-device transfer, which `allowBackup="false"` does not disable on all OEMs | ✅ **Resolved.** Never a data leak: the SQLCipher passphrase is wrapped by a non-exportable `AndroidKeyStore` key, so a D2D transfer lands ciphertext the new device can never decrypt — an unopenable database, not exposed data. A real rules file now excludes every scope from both `<device-transfer>` and `<cloud-backup>` | **R22** — scope bounded |
| R21 | Second full audit pass (`android-app-auditor` registry + Android Lint + a line-by-line read of ~12,700 lines) against the reconciled tree | ✅ **Resolved**, two items left as decisions. The critical was structurally identical to R14's: `SCHEDULE_EXACT_ALARM` was declared and read but never requested, so from Android 14 every reminder, nag and snooze silently took the inexact path on a fresh install (§7.1 rule 1). Also fixed: `Locale.getDefault()` read inside a composable so History's calendar never followed a locale change; whole-database serialization, AES-256 zip encryption and SAF copies running on the main thread; and a sweep of dead symbols whose KDoc described call sites that never existed. Method note worth more than any single fix — a permission claim is only checkable against the merged manifest (§7.1 rule 2) |  |
| R22 | `SIGN-001` — the release-signing comment understated its own consequence. It read "anyone could build an APK that installs over this one", which reads as a nuisance | ✅ Documented, deliberately not "fixed". AndroidKeyStore aliases are scoped to (package name, signing identity) and survive an in-place upgrade. The debug keystore is committed and its password is the published Android default, so anyone holding this repository can build an APK Android accepts as an *upgrade* — with no signature-mismatch prompt, since an identical signer is the entire point of sharing the identity — which inherits the data directory **and** the `emivita_db_wrap_key`/`emivita_autosync_wrap_key` aliases, and can therefore read out the SQLCipher key and the auto-sync backup password in plaintext. So the at-rest design is in practice only as strong as control of `keystores/my-shared-debug.keystore`: it still holds against the threat it was built for (a lost device reading the raw `.db`), and does not hold against installing a build someone else produced. This also bounds R20: `data_extraction_rules.xml`'s "arrives as ciphertext the new install can never open" is true for a device-to-device transfer and false for an upgrade install under this identity. The configuration is unchanged, because for the current threat model — the owner's own installs on the owner's own devices — the existing trade is still the right one. `app/build.gradle.kts` now states the real scope and lists the two fixes in preference order (a separate release keystore read from a gitignored `keystore.properties`; or `setUserAuthenticationRequired(true)` on the auto-sync wrap key) for whenever this reaches a second person. Related: `.gitignore` now ignores `*.keystore` by extension and re-admits the debug key by name, so a future `keystores/prod.keystore` cannot be committed by being differently named. |  |
| R23 | Third full audit pass against the reconciled tree — 22 parallel readers over every file, 6 cross-cutting sweeps, a documentation-versus-code fleet, every finding put to an adversarial verifier. 264 agents, 250 raw findings, 177 surviving. Not a reading-only pass: `assembleDebug`, `lintDebug` and `testDebugUnitTest` were actually run | ✅ **Resolved**, four items left as decisions (R25, R27, R28, R29) and one structural gap named (R24). **The most user-visible defect ever recorded here: editing a reminder silently did nothing.** All four edit paths called `updateConfig` then `updateAdvancedFields` with the same pre-edit `Reminder`; `@Update` writes every column, so the second call restored the first's `configJson`. Changing a time or pattern ran a success path and kept the old schedule. Collapsed into one write (§7.1 rule 3). Also: three form paths committed configs the scheduler then threw on permanently (`"0".toIntOrNull()` is `0`, not null, so the fallback never fired); a preference write using async `apply` could brick the database on a crash; and a lint run surfaced a backup-rules version hole R20's own reasoning had denied | **R33** — one of its own fixes applied to half its subject |
| R24 | R23's named-but-not-closed structural gap: 12 tests, all Room, none touching `SchedulingEngine`/`ReminderScheduling`'s ~650 lines of pure date math — the most testable code in the app and, by two audits' evidence, where the real bugs live | ✅ **Resolved.** 59 new tests, run in `Europe/Rome` rather than UTC because a UTC-only suite structurally cannot catch an offset bug (§7.1 rule 6). The suite found a defect on its first run: R23 had fixed one of three fallible patterns and left the siblings — the guard is now symmetric across all three (§7.1 rule 4) |  |
| R25 | R23's decision item (a): `Reminder.parentReminderId`'s FK declares `ON DELETE SET_NULL`, §3 specified `ON DELETE CASCADE` | ✅ **Resolved — SET_NULL kept, §3 corrected.** The deciding fact was that *both* sides justified themselves with a premise untrue of the shipped code: §3 wanted CASCADE because it matched "how the chain is nested in the UI", and `MedicineDetailScreen` renders a flat list where a following dose is indistinguishable from any other row. Cascading would delete rows the user never selected from a list that cannot show them, in a medication app, with unbounded blast radius. The real defect underneath was that an orphaned child kept its row, kept `enabled = true`, and never fired again *silently* — `delete()` now reads children first (§7.1 rule 7) |  |
| R26 | The gap R24 carried forward: no CI and no lint gate, so a green build was a snapshot someone remembered to take rather than something the repository enforced. `app/build.gradle.kts` had no `lint { }` block at all | ✅ **Resolved.** Lint is now a gate rather than a report, wired in the build file and in CI, and the README's command block says so (§7.1 rule 14). R23 had proved the cost concretely: its `DataExtractionRules` finding had sat unread in a lint report through an entire audit cycle |  |
| R27 | R23's decision item (b): `switchStatus()` writes an absolute recorded stock quantity, "documented as deliberate in four places" | ✅ **Resolved — now a relative delta.** Not a trade-off, a defect, in exactly R25's shape: the documentation was consistent across every site and rested on a premise that does not survive checking (§7.1 rule 7). It now computes what was genuinely consumed rather than inventing units that never existed. `setStock` keeps exactly one live caller and its KDoc warns that `switchStatus` must not become a second |  |
| R28 | R23's decision item (c): Today's 2-day window is the only `requestWindow` registrant and therefore shrinks the shared projection the stock consumers read | ✅ **Resolved — the constant is now a floor.** `effectiveWindowDays()` read `maxOrNull() ?: DEFAULT_WINDOW_DAYS`, making the default an empty-map fallback rather than a floor, so from the moment Today composed, every other reader got Today's 2 days. Third of three where the documentation encoded the misunderstanding (§7.1 rule 7). A permanent assertion on `computedAtEpochMillis` now guards against the vacuous-pass mode, because a suite that never exercises the code under test cancels the gate R26 put up (§7.1 rule 5) |  |
| R29 | R23's last open decision item (d): `DoseGroupRepository` is constructed nowhere and its five methods are unreachable | ✅ **Resolved — deleted.** Changes no behaviour; it changes the code from "half-built feature waiting to be wired" to "deliberately not built", which is the honest description and the one §9's grouping design should be read against. Four now-orphaned `DoseGroupDao` methods left in place deliberately, inert either way |  |
| R30 | Coherence pass over purpose-vs-implementation (this batch). `SchedulingEngine.nextOccurrenceFor` treated `activeFromEpochDay` and `activeUntilEpochDay` as one symmetric clamp — candidate outside the range, return null — but the two bounds are not symmetric in time. Past `activeUntil` there genuinely is no next occurrence; before `activeFrom` there is one, just later. Nothing in this app re-arms a reminder on the passage of time alone (`rescheduleAll()` runs at boot and `MY_PACKAGE_REPLACED`; `OccurrenceProjectionCache` recomputes on data changes, never on a clock), so "no next occurrence" was permanent: a reminder whose range opened even one day out had its alarm cancelled by `scheduleOrCancel` the moment it was saved, and projected zero occurrences into Today, History and both widgets. That contradicted §4's own claim that this clamp is the whole mechanism for courses and tapers — "several ordinary FixedTime reminders with adjacent, non-overlapping ranges" — since only the segment already in range could ever fire. `SchedulingEngineTest` pinned the defect rather than the rule (`a candidate before the active range opens is clamped away`), which is why three audit passes did not reach it | ✅ **Resolved this pass.** The type-specific calculators are split out as `computeBase`, and an activeFrom miss re-runs it from the start of the day the range opens instead of returning null; `activeUntil` is unchanged. Guarded against re-running when the opening is not actually later than the floor already asked for, which is both the termination condition and the correct answer for `LinkedFollowing` (anchored to its parent, not to `notBefore`). The old test is rewritten to assert deferral, plus one that a deferred range still respects `activeUntil` and one that `occurrencesFor` now projects a course starting inside the window |  |
| R31 | Same pass. `nextOccurrenceFor`'s KDoc promises "next time [reminder] should fire at/after `notBeforeEpochMillis`", and every branch honoured it except `LinkedFollowing`, which returned `parentLastEvent + delayMinutes` unconditionally. The anchor is the parent's *last* event, so that expression goes stale on its own: once `delayMinutes` has elapsed it is an instant in the past. Two consequences, both live. `RealSchedulingCoordinator` armed an alarm at a past instant — `setExactAndAllowWhileIdle` fires those immediately — on every reschedule after the child's own dose was answered. And the shared projection carried the stale slot until the parent fired again, which is the actual reason `NextRemindersWidget` has to filter `epochMillis >= now`; Today only escaped it via `buildTimeline`'s materialized-key dedupe. Untested in either direction: every `LinkedFollowing` case in `SchedulingEngineTest` passed a `notBefore` *earlier* than the parent's event, which is the one arrangement production never produces | ✅ **Resolved this pass.** A `takeIf { it >= notBeforeEpochMillis }` in that branch. No catch-up is lost that the other types offer — `FixedTime` rolls a missed slot to its next matching day and both interval calculators step forward onto a future grid instant, so "no replay of a past slot" is now uniform rather than one type's accident. Two tests added: a passed slot yields null, and a slot exactly on the floor still counts as due |  |
| R32 | Same pass. `lockRemindersUntilTaken` (§3) is enforced in three places — `ReminderNotificationBuilder` omits the Skip action, `ReminderActionReceiver` rejects a stale one, `AlarmActivity` does both — and `Medicine.remindersLockedUntilTaken`'s comment lists exactly those three as if the list were exhaustive. `TodayScreen` is not among them and shipped an unconditional Skip chip on every PENDING entry. Today is the app's start destination and the surface a user actually reaches for, so the safety gate was bypassable in one tap on the front page while being enforced everywhere a user is less likely to be. Not a missed call site so much as a miscounted one: the comment enumerating the enforcement points is what made the omission invisible | ✅ **Resolved this pass.** `TodayScreen` takes `SettingsRepository`, collects `lockRemindersUntilTakenByDefault` as state (composition cannot call the suspend `isLockedUntilTaken`) and applies the per-medicine override at each row, giving the same resolution. The Skip chip is omitted rather than disabled, matching the notification builder and `AlarmActivity`. `Medicine`'s comment now names Today and says why it resolves inline. History's `DoseEditDialog` is deliberately *not* gated: correcting a past record is a different act from dismissing a live dose |  |
| R33 | Same pass, and a correction to **R23**. R23 found `require(n >= 1)` throwing from inside the projection cache's recompute loop and replaced it with a null return in `matchingDateOnOrAfter`'s `EveryNDays` and `CyclicActivePause` branches — then gated `intervalMinutes` in `AddReminderScreen` instead for `RepeatingInterval` and `DailyWindow`, leaving `require(config.intervalMinutes >= 1)` standing in both calculators. One file, two opposite answers to the same question, and the half that was fixed carries the argument that settles the other half: `CyclicActivePause`'s own guard comment says a UI gate is not enough because `BackupRepository.restoreSnapshot` writes reminder rows back with no config revalidation. The blast radius is worse than R23 recorded: `OccurrenceProjectionCache`'s recompute runs inside a `for (trigger)` loop with no handler, so a throw did not skip one reminder — it cancelled the loop for the life of the process (the `SupervisorJob` keeps siblings alive, it does not restart the child), silently freezing Today, History and both widgets and stopping every low-stock and expiration alert; and `rescheduleAll()` walks every enabled reminder at boot, so a throw left each one after the bad row with no alarm at all | ✅ **Resolved this pass.** Both `require`s are now null returns, matching the two branches R23 already converted and `windowSlotOnDay`'s existing end-before-start policy one level down. Both loops are made per-item resilient as a backstop rather than a fix — `recompute()` and `scheduleOrCancel()` each wrapped in `runCatching` with a logged failure — on the principle that the single point every projection consumer depends on, and the boot path that re-arms every alarm, are the two places not to rely on "nothing throws here" holding. `testOptions.unitTests.isReturnDefaultValues = true` added so those `android.util.Log` calls do not themselves throw in the non-Robolectric scheduling tests. The two `assertThrows` tests now assert null |  |
| R34 | Same pass. History's headline figure and the chart directly under it ranked the same two outcomes in opposite orders. `AdherenceSummary` and `adherenceSubtitle` (the PDF report's subtitle) both computed `taken / (taken + skipped)`, counting a deliberate Skip against the user and dropping PENDING — a dose that fired and was never answered — entirely; `AdherenceChart` renders PENDING as its own `missed` series and its doc comment ranks it explicitly as "worse than a deliberate Skip", which is why it is the one outcome that earns the reserved `error` colour. Ten taken and twenty missed therefore rendered as **"100% adherence"** immediately above a chart that was two-thirds red, and the PDF a user might hand to a clinician carried the same sentence. `AdherenceSummary`'s `if (total == 0) return` compounded it: a window containing nothing *but* missed doses hid the summary card altogether. Not a locked decision like weekend mode — undocumented drift between two functions written against the same rows | ✅ **Resolved this pass.** One `adherenceCounts` helper both call sites read, carrying `missed` alongside `taken`/`skipped`; the summary and the PDF subtitle both print it. The headline names its own denominator — "100% of answered doses taken", not "100% adherence" — and the card renders whenever there is anything to report. The **denominator itself is deliberately unchanged**: whether a missed dose belongs in it is a product decision about what this app means by adherence, not a defect to patch, and is left open rather than settled quietly here |  |
| R35 | Full `android-app-auditor` registry pass over the reconciled tree, run alongside the coherence pass above (0 CRITICAL, 0 HIGH; §1's five non-negotiables verified as actually holding, including transitively — the whole resolved `releaseRuntimeClasspath` was scanned, not just the `dependencies` block, and `ACCESS_NETWORK_STATE`'s `tools:node="remove"` was confirmed against the *merged* manifest rather than the source one). The one finding worth acting on is a congruence gap this file's own least-privilege posture makes conspicuous: every component the app declares is `exported="false"` bar the LAUNCHER activity, and `androidx.compose.ui.tooling.PreviewActivity` arrived `exported="true"` with no `android:permission`. It reaches the merged manifest twice — from the correctly-scoped `debugImplementation` on `ui-tooling`, and again transitively as an *ordinary* dependency of `com.kizitonwose.calendar:compose`, which is how it got onto `releaseRuntimeClasspath` (confirmed by `dependencyInsight`). Release was never the exposure: `PreviewActivity.onCreate` checks `FLAG_DEBUGGABLE` and finishes when unset, so there it was dead weight. Debug is, and debug is installed on the same device by design — that is what the shared signing identity in `buildTypes.release` exists for. Past that guard it reads two intent string extras and reflectively loads and renders the class they name, inside the process holding the unlocked SQLCipher handle and both Keystore aliases, startable by any app on the device | ✅ **Resolved this pass.** `tools:node="remove"` on the activity in `AndroidManifest.xml`, removing it from **both** variants, plus an `exclude(group = "androidx.compose.ui", module = "ui-tooling")` on the calendar dependency so the artifact itself stays off the release classpath. **Verified the way it was found:** `:app:processReleaseMainManifest` and `processDebugMainManifest` re-run, `PreviewActivity` now 0 occurrences in each merged manifest. The cost is Android Studio's "Deploy Preview to Device"; the IDE preview pane, `@Preview` itself and the rest of `ui-tooling` are untouched. The manifest comment records how to trade it back (move the node to `src/release/AndroidManifest.xml`) so a future reader re-accepts the exposure knowingly rather than by omission |  |
| R36 | Weekend mode applied to every reminder whenever the global toggle was on, with no per-reminder opt-out — §3, §4 and §5 all recorded this as a faithful port and §5 specifically as a decision *not* to invent an override "medTimer doesn't have". Re-checked against the reference: correct as a description of medTimer (`SchedulingFactory.create` returns `WeekendModeSchedulingDecorator(scheduler, …)` for every branch of its `when`, unconditionally), and wrong as a design for this app. One switch cannot distinguish a supplement that is happy to wait until the window closes from a drug that is not, so buying a Saturday lie-in on the first silently delayed every dose of the second. Faithfulness to the reference had been treated as the tie-breaker rather than as one input | ✅ **Resolved this pass** at the user's direction. Split: the global preference keeps *what the weekend is* (enabled, days, window — it describes the user's week, and duplicating it per reminder would let two reminders disagree about when Saturday morning ends), and a new `Reminder.weekendModeOptIn: Boolean = false` owns *whether this reminder is shifted*. Both must be on. Cross-cutting like `autoResolve`, so it sits on the row beside it rather than in any one type's `configJson`, and rides the same Advanced section of the four dose-producing forms. **DB v4 → v5**, `MIGRATION_4_5`, `ALTER TABLE reminders ADD COLUMN weekendModeOptIn INTEGER NOT NULL DEFAULT 0`. The backfill is 0, not 1: 1 would preserve the observed behaviour of anyone who had the old global toggle on, but weekend mode *delays a dose*, and carrying a blanket setting forward as though each reminder had individually consented is the exact thing this change exists to stop. Opting back in is one switch per reminder; a dose delayed because a migration assumed consent is not. The gate is checked before the global read, so a reminder that has not opted in costs zero Flow reads. §3, §4 and §5 all corrected — §5's row now records this as the one place faithfulness was the wrong call. Four tests: the opt-in path, the not-opted-in path against identical settings, opted-in-but-global-off, and the zero-read property; plus `MigrationFrom4To5Test` pinning the 0 backfill specifically |  |
| R37 | Drift audit against medTimer. **`LowStockAlert`/`ExpirationAlert` are not in Emivita's scheduling chain at all** — `supportedTypes` excludes both, and the evaluator's one call site runs only on a Room change, never on a clock. medTimer drives them from a clock | ✅ **Resolved** for the three inherently time-triggered cases (low-stock DAILY_WHEN_BELOW, both expiration modes), which now get real alarms. `supportedTypes` still excludes them from projection so an alert never appears on Today's timeline as a zero-amount entry, nor is subtracted from the stock run-out walk |  |
| R38 | CI had never run. Every workflow run this repository ever had — both on `master` and on the R30–R37 branch — failed at its first step with `./gradlew: Permission denied` (exit 126): the wrapper is committed as mode 100644 and the workflow invokes it as `./gradlew`, so nothing downstream of that line executed. The lint gate R26 put up and the test suite R23 added had therefore never once been enforced remotely, while `README.md` stated that CI "runs these same three tasks on push and pull request" and §7 rows quoted green runs that were only ever local | ✅ **Resolved.** `git update-index --chmod=+x gradlew`, recorded in the index rather than on disk because this repo is developed on Windows with `core.filemode=false`, where the filesystem bit is not what git stores. **First green CI run in the repository's history** followed. Checked the next blockers rather than spending round trips discovering them: `local.properties` is untracked, `lint-baseline.xml`'s 33 entries all use relative forward-slash paths and so match on Linux, and the new `5.json` schema is committed |  |
| R39 | First on-device verification pass (OnePlus 9 Pro LE2123, OxygenOS / Android 14, arm64-v8a), closing items the `android-app-auditor` had listed as NOT CHECKABLE without hardware. **Confirmed working:** the Room v4→v5 migration against a real SQLCipher database — the runtime schema validation the JVM migration tests structurally cannot reach, and the exact thing R36's `@ColumnInfo(defaultValue = "0")` existed for; the SQLCipher native path (`loadLibrary`, raw-key BLOB, `abiFilters`), proven by the database opening at all; the whole alarm chain end to end (reminder → engine → AlarmScheduler → AlarmManager → receiver → DoseEvent materialised → notification on `reminders_standard` with Take/Snooze/Skip), with Take dismissing the notification and the daily chain advancing to the next day; `USE_EXACT_ALARM` on API 34, with `dumpsys alarm` reporting `exactAllowReason=policy_permission` and no `AlarmScheduler` inexact-fallback warning, which settles the manifest's central argument on the device generation it was written about; **and BootRescheduleReceiver** — after a real reboot, with the app never opened, the pre-reboot alarm was present again and the process had been started by the boot broadcast, closing the one item the manifest said it "could not verify by reading code alone". **The finding:** a reminder scheduled for 19:50 arrived at about 19:55. `setExactAndAllowWhileIdle` with `USE_EXACT_ALARM` held is the strongest scheduling call the platform offers and it was already being made; the OS carried a four-minute tolerance window on that firing and a **one-hour** window on the next day's, applied a `battery_saver` policy delta, and OxygenOS's own freezer had suspended the process. Nothing in the app was wrong — but nothing in the app said anything either, and an app whose entire purpose is timely reminders was arriving late silently | ✅ **Resolved** for the part the app controls: a `BatteryOptimizationCard` in Notifications & Alerts, modelled on the existing `FullScreenAlarmAccessCard` (same ON_RESUME re-read, since special-access screens deliver no activity result), shown only when the app is not exempt. **No new permission**: `isIgnoringBatteryOptimizations` is a plain query and `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` opens the system list, so `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — which AndroidManifest.xml removed on least-privilege grounds — stays removed and §1's posture is untouched; the user takes the last step in Settings. The manifest's boot-receiver comment is rewritten from "verify this on your device" to the verified result. Not reproduced: data preservation across the migration (the database held no medicines, so this proves *schema* validation only) and biometric lock (no biometrics enrolled — the app degrades honestly, showing a disabled row that says so) |  |
| R40 | Found on device: the error card renders **teal** in an amber palette. `Color.kt` computed `error` by rotating the accent hue +158°, so Golden Hour's amber landed near 198°. Blast radius wider than one card — `AdherenceChart` paints its `missed` series with the same role | ❓ **Open — reported, not fixed** at the time; §6 and Appendix A were locked and this was a design decision rather than a defect to patch quietly. See §7.1 rule 10 | **R42, R44** — the error hue and the unassigned surface roles respectively; nothing in this row is still open, and this Status was never updated |
| R41 | The project was two apps, not one. `app/build.gradle.kts`'s `debug` block carried `applicationIdSuffix = ".debug"`, so `com.hazia.emivita` and `com.hazia.emivita.debug` were unrelated packages to Android — two data directories, two SQLCipher databases, two sets of AndroidKeyStore aliases, two sets of alarms — and with no `resValue` overriding `app_name`, both installed as "Emivita", indistinguishable in the launcher and the widget picker. Two written claims depended on that not being so. `README.md` said the shared debug keystore let builds "install over each other": false, since a shared signer is necessary and not sufficient when the applicationIds differ. And `dist/vc1/MANIFEST.md` exists, in its own words, to be "an installed predecessor to upgrade *from*" so that `MIGRATION_4_5` can be run against "a database written by a real previous build" — unreachable, because the archive is `com.hazia.emivita` and the build anyone develops with was suffixed. That file knew, and papered over it with a closing note that the two "install alongside" each other and that the baseline data "must be created in *this* app, not the debug one". The cost was not theoretical: R39's migration check ran against a leftover `.debug` database and never touched the archive, and read as a pass either way | ✅ **Resolved this pass**, at the user's direction that this is one project and one build. The suffix is removed; the `debug` block now carries the reasoning for its absence, since "no suffix" is the unusual choice and an empty block would invite one back. **Verified by doing the thing that was impossible:** the archived vc1 APK installed and launched (versionCode 1, non-debuggable — the real artifact), then a debug build carrying schema 5 installed over it with `adb install -r`. No signature prompt, `libsqlcipher.so` loaded from `arm64-v8a`, no `IllegalStateException` from Room's schema validation, app alive, and `pm list packages` showing exactly one `com.hazia.emivita`. Both documents corrected: the README now credits the shared applicationId *and* the shared signer and states the trade (no side-by-side installs), and MANIFEST.md's alongside-note is replaced by the recorded result. Considered and rejected: cutting a fresh dist baseline under the unified identity, because that file forbids it in terms that are right — "its value is entirely that it is the artifact a real database was created by" |  |
| R42 | §6's semantic state-token rule computed `error` by rotating the accent hue, which R40 reported. Across the eight palettes it produced teal, cyan, magenta, blue — and green in four — for the role that paints a missed dose. The spec had already recorded the rule failing once, against Neutral's fully-desaturated accent, and it was never revisited | ✅ **Resolved.** The rotation is dropped for `error` and kept for the three accent roles where it belongs; `error` became one fixed red shared by all eight. Two independent failures of one rule is the rule being wrong, not the palettes (§7.1 rule 10) | **R56** — the fixed shared red is replaced by a per-palette one, and more importantly `error` no longer paints any of the ordinary states R42 left it painting |
| R43 | Two §9 entries still described work that later rows had overturned or completed | ❓ **Open — corrected, deliberately not built.** Neither entry is promoted: §10 says a batch does not start until the relevant section is confirmed | **R48** — both halves acted on; the corrections this row asked for were finally made there |
| R44 | R42's own "Not done" note, taken up. `surfaceVariant`, the whole `surfaceContainer*` ladder, `surfaceBright`/`surfaceDim`, `outlineVariant`, `scrim` and the `inverse*` family were never assigned, so `lightColorScheme`/`darkColorScheme` supplied Material's baseline for them. Theme.kt's own doc comment recorded that reliance as deliberate — "darkColorScheme and lightColorScheme differ in their defaults for the slots this app doesn't override" — and it was; the consequence was not. M3's `NavigationBar` draws from `surfaceContainer`, so the bottom navigation bar, present on every screen, rendered baseline **lavender** under all eight audited palettes, as did every dropdown and dialog surface. R42 left this as wanting "a design decision, not sixteen invented hexes", which was the right instinct about inventing hexes and the wrong conclusion about what was available | ✅ **Resolved this pass.** Derived, not invented: a `withDerivedSurfaces` extension blends `surface` toward `onSurface` in small steps (3/6/9/12%) for the container ladder, with `outlineVariant` a hairline between `outline` and `surface`, the `inverse*` pair taken straight from the audited surface/text relationship turned over, and `surfaceTint` from `primary`. Every input is an audited Appendix A token and every output sits on the line between two of them, so no container can leave its palette's hue — this is the same "computed here (not spec'd directly)" move Color.kt already makes for `onPrimary`/`onSecondary`/`onTertiary`/`onError`, not a new liberty. The blend direction needs no light/dark branch: `onSurface` is dark in light mode and light in dark mode, so blending toward it darkens or lightens exactly as M3 tonal elevation does in each. Verified on device — the navigation bar now renders a warm sand drawn from Golden Hour's own surface. Theme.kt's doc comment is corrected rather than left endorsing the defaults |  |
| R45 | R43's tapering half, settled. That row recorded §4 and §9's parking lot endorsing two incompatible mechanisms at once — §4: "a taper schedule is just several ordinary FixedTime reminders with adjacent, non-overlapping ranges — no new ReminderConfig type needed, this clamp is the whole mechanism"; the parking lot: it needs "a new `Tapering` `ReminderConfig` variant (a list of `{dateRange, dose}` steps)". Left open there because it was a decision rather than a defect | ✅ **Resolved in §4's favour, with evidence rather than assertion.** Two tests in `SchedulingEngineTest` make the claim executable: a three-step 10 → 7.5 → 5 taper built as three FixedTime reminders with adjacent ranges projects exactly nine doses at the right levels on the right days, and a separate test pins the handover, where the mechanism's real failure mode lives — ranges are inclusive both ends, so a step ending 06-03 and the next starting 06-04 must yield exactly one dose on each, an off-by-one either way being a missed dose or a doubled one. **Neither test could have passed before R30**: the activeFrom clamp used to answer null for a range that had not opened yet, so every step after the first projected nothing and a taper delivered its opening dose forever. That is why the parking lot's "needs a new variant" read as true when it was written — the mechanism §4 described genuinely did not work. It does now. The parking-lot entry is corrected to say so and stays unpromoted, on its own still-valid grounds that a single-row taper is a convenience, not a capability: the multi-reminder form is manual to enter and easy to leave a gap in, which is a real UX argument for the variant and not an argument that tapering is unsupported |  |
| R46 | **A "Missed" dose could never be corrected.** All four surfaces gated editing on `status == TAKEN || status == SKIPPED`, so a dose that was taken but never tapped stayed wrong in History and in the PDF export permanently — in an app whose §1 purpose is an adherence record and whose export exists to be handed to a clinician | ✅ **Resolved.** Editing gates on `!= REFILLED` instead. Lock-until-taken is deliberately not gated: that lock stops a *live* dose being dismissed, and correcting a past record is not a dismissal |  |
| R47 | Spec-versus-code audit over the whole tree. Two findings share a shape: **a ledger row marked resolved because the *mechanism* shipped, while the call sites that were the actual defect were never migrated.** R46 had reached one of History's three surfaces — the List still gated on the old allow-list, and Calendar passed a correctly-gated callback into a row that was not clickable at all, making its fix dead code | ✅ **Resolved, with the test infrastructure to stop it recurring** (§7.1 rule 8). The remaining call sites migrated, and `PaletteContrastTest` was added so a token audit is checked against the `ColorScheme` the app actually builds rather than against a table. CI has no device, so an instrumented suite would never have run — which is how R46 shipped half-done; the build file's closing comment advised the opposite and is corrected |  |
| R48 | A tracker entry read "**Blocked**, not just unscoped" long after the work had landed, and the tapering entry R45 overturned still described work R45 had proved unnecessary | ✅ **Resolved.** Medical ID shipped — a screen under Settings and a one-page PDF card. Its two boundaries kept exactly: deliberately not a lock-screen widget, because Android's own Emergency Information is readable without unlocking and this app cannot beat that. Onboarding had been creating the profile silently as "Me", justified because "a profile's name is never displayed anywhere" — a card headed with the user's name displays it, so Name is now the screen's first field |  |
| R49 | The palettes were rebuilt from `design-lexicon.md` on the user's instruction, ignoring the shipped colours entirely, and the rebuild kept producing accents with **no visible relationship to their palette** — Golden Hour's amber drawing a magenta accent, Forest Floor's moss drawing a pink one. Four accents sat at no named harmonic interval at all (offsets 92°, 133°, 43°, 163°, each 12–13° from any template), and saturation was pinned near 46% for six of eight palettes whether the palette's own roles were 22% (Sage Clinical) or 71% (Cobalt Porcelain). The cause was not tuning. The accent search **maximised perceptual distance from the palette**, carried an explicit rule excluding the palette's own hue band, and ran a global scheduler forcing the eight accents apart — so each accent was a function of what the *other* palettes had taken rather than of its own. An objective shaped that way cannot produce coherence at any parameter setting | ✅ **Resolved.** Objective inverted: harmony is maximised, distinguishability is a floor. Hue is `base + a named interval` from the lexicon's own vocabulary (Mossland pulls its accent "~50° left into warm clay"; Terracotta & Slate is "near-complementary"), saturation inherits the palette's own envelope, and the global scheduler is deleted — with each accent derived from its own base, the eight differ as a consequence of their palettes rather than by imposition. designkit's pairwise-harmony score moved from **0.441 to 0.72–0.99** |  |
| R50 | **Every fitness number this project has quoted from designkit was computed with the constraint set switched off.** `dk status` reported `optimizer engine not found — local-search fallback`: `optimization-engines` was never reachable, so `--optimize` ran a 4-restart hill climb that scores only the weighted components and never evaluates `constraints`. That is why a Golden Hour whose accent had drifted to a red at hue 1° returned fitness 0.902 — nothing was checking, and nothing was zeroing | ✅ **Resolved.** designkit's discovery walks up for a sibling `engine/` directory and scans `~/.claude/plugins`, but this machine's plugins live under `…/rpm/`, which it never enumerates. Setting `OPTIMIZATION_ENGINES_HOME` to the optimization-engines plugin's `engine/` directory flips it to `mode: portfolio` (DE/CHA/CMA). The first real run failed immediately where the fallback had been silent — see R51. **This is an environment fix, not a code one:** it has to be set wherever designkit is invoked, or the fallback silently returns |  |
| R51 | With the portfolio actually running, **6 of 16 seed/mode combinations were infeasible** — all six on one constraint, `color.accent_distinct` ("accent hue must sit at least 20 degrees from primary"). The hue-spread column is the whole diagnosis: blocked seeds spread 0.1–2.8°, feasible ones 13–54°. Five of the eight source entries are monochromatic or two-colour *by their own rule* — Cozy Cocoa "monochromatic, H≈20–30°", Ming "rigorously two-color, nothing else", Navy Scholastic and Clinical Teal both "monochromatic" — so designkit's colour domain, which assumes a hue-distinct accent, structurally cannot refine them. Forcing a template did not help: when the *seed* violates a constraint, fitness is 0 everywhere nearby and the search has no gradient to climb | ✅ **Resolved at the user's direction** that the palettes need not stay monochromatic. Route switched from refine (`--from`) to generative (`--mood`, seeded from each entry's own language) with `primary.hue` anchored to the entry's measured band, so identity survives while the template is free. All 16 combinations now feasible with zero violations. Recorded because the constraint was right and the seed was wrong — a monochromatic source is not a defect to patch around |  |
| R52 | Two further defects in the method, both invisible in the fitness numbers. **(a)** The role envelopes were left at the domain's defaults, of which `accent.sat [70, 95]` is the one that mattered: designkit's colour domain is built for brand palettes where the accent is *meant* to pop, the exact inverse of this app's eye-comfort brief, and the `muted` mood cue does not reach it. **(b)** Templates were swept and the best **fitness** taken — but fitness is not comparable across templates. A perfect triadic and a perfect analogous both score ~1.0 on `harmony_template_match`, so "highest fitness" systematically selects the widely-spaced ones. That is why Golden Hour kept returning amber + mint + purple at fitness 0.970: formally harmonious, visually incoherent | ✅ **Resolved.** (a) Envelopes retuned through `dk spec`, which exists for exactly this — accent and primary saturation into a calm band, and `surface.light`/`text.light` pulled off 96/5 so nothing sits near pure white or black. Cobalt Porcelain keeps a higher ceiling because the Ming entry is deliberately S72 to separate it from Scholastic and Clinical; honouring the entry beats flattening every palette alike. (b) The template is a **decision, not a search dimension** — the sweep is restricted to the coherent families (analogous, monochromatic, near-complementary). That this is right rather than merely preferred is confirmed by the engine then rediscovering the lexicon's own answer unprompted: Golden Hour came back as amber with a slate partner, which is Cozy's documented *Terracotta & Slate* pair |  |
| R53 | Coherence and distinguishability are in direct opposition here: an analogous palette is coherent *because* its hues are close, and close hues yield low ΔE. On the Appendix D candidates, `attention` vs `primary` fell under the ΔE 10 categorical floor in three registers. 183 of 184 checks passed | ❓ **Open, and deliberately not tuned away** at the time — the judgement was that a coherent palette with an iconographic state channel beats a discordant one carrying state chromatically. It named its own overturning condition, which R54 then met (§7.1 rule 12) | **R56** — the Appendix D candidates this dispute was measured on never shipped; the register changed and the six that did ship clear the floor with room |
| R54 | R53's named condition, tested: `AdherenceChart` paints taken/skipped/missed as stacked bars read through a legend, so hue carries the state with no icon or label beside it. Measured across all eight palettes, both modes, and normal/deuteranopic/protanopic vision, the failing pair is always **taken vs missed** — ΔE 5.6 to 7.9 in four of sixteen registers | ❓ **Open at the time — the palettes were not blocked, the chart was.** Two ways out named: give the chart a second channel, or reserve a hue for `missed`. The first was recommended and R55 built it, because it fixes the channel rather than one palette's arithmetic | **R56** — R55 built the channel fix this row asked for, and R56 then replaced the palettes whose ΔE figures the row was gating on |
| R55 | R54's recommended fix, built. `AdherenceChart` was the one surface conveying state by hue alone | ✅ **Resolved.** Three channels now, any one of which identifies a series. **Texture:** each series draws through a tiled `Fill` — solid, diagonal, cross-hatch — via `ShaderProvider.bitmap`. **Position:** the stack order is fixed and stated in the chart rather than left implicit, because a 6dp bar is too narrow for texture alone. **Colour** stays, redundant; marks are the series' own colour blended toward `surface`, so texture stays inside the palette. The legend shows the real tiles instead of dots — a dot teaches the colour and nothing else, which is what left the chart relying on hue. **Two things worth recording from building it.** The first hatch put its strokes where they only clip a tile's corners, so solid and diagonal came out **0.007 apart** on luminance — indistinguishable, from code that read as correct and compiled clean, caught by the test and not by review. And the test itself first failed against working code: Robolectric's legacy graphics shadow implements `drawColor` but not `drawLine`, needing `@GraphicsMode(NATIVE)`. Six tests pin the channel and every assertion discards colour and measures luminance only (§7.1 rule 5). Suite 152 → 158 |  |
| R56 | **`error` was doing four jobs and three of them were not errors** — the missed-dose series (`AdherenceChart.kt:100`), the past-due day dot (`HistoryCalendarView.kt:233`; `historyStatusLabel` renders `PENDING` as "Missed", so this is the *same state* as the chart series, not a merely-due dose), and a note reporting that an **optional** barcode-scanner app is not installed. A fourth: `tertiary`, a chromatic accent, painted *skipped*, so a deliberate non-event shouted as loudly as a taken dose. The shipped red was mechanically fine — ΔE 34–43 from the dark surfaces — so no contrast audit could have caught it. **Underneath: the eight palettes answered to no register decision at all**, being built from lexicon entries picked for variety rather than from one decision about what kind of app this is. It is patient-facing, which is the **Health / Consumer Wellbeing** register (§7.1 rules 11, 13) | ✅ **Resolved.** Six palettes replace the eight (Appendix A), all in one register, and two roles split out of `error`: **`attention`** (missed dose, low stock, approaching expiry — warm clay, never red) and **`neutralFill`** (skipped, and other deliberate non-events — near-achromatic). `error` now means a genuine failure only. Both travel on `LocalEmivitaColors` as `EmivitaTheme.colors.*`, because all 22 of Material3's slots were spoken for and borrowing one whose name means something else is the overload this row undoes. **Chosen by search:** an `optimization-engines` run, fully computable spec, zero model judgements — fitness **0.9265**, beating the runner-up assignment by 0.15. **Two corrections during it changed the answer.** (a) The `legibility` scorer mapped 1.0× the floor to 0.41, so a passing choice scored as near-failure and the search fled to `tertiary`, where Sage's olive and brown collapse to **ΔE 2.82** under deuteranopia — a floor scored as failure is worse than no floor. (b) Holding colours at a reference pinned `attention`, whose whole value is being tunable, against fixed rivals; once every candidate was actually tuned, separation went **0.045 → 0.974** and the ranking inverted. Worst case is now ΔE 15.7 across all twelve registers including simulated CVD. R55's texture channel stays load-bearing — colour is now a second channel that works, not a first channel that has to. Suite 158, unchanged |  |
| R57 | **I reported a green build three times while `compileDebugKotlin` was failing.** `./gradlew test 2>&1 \| tail` reports *tail's* exit status, and `test-results/*.xml` persists across runs, so parsing it returned an earlier session's counts. The regression the false green hid: regenerating `Color.kt` wholesale dropped **`NeutralMedicineAccent`**, a palette-independent fallback used at 14 call sites. A quieter one alongside it: Python's text-mode write on Windows converts a file to CRLF, so a three-line edit read as a 990-line diff | ✅ **Resolved** (§7.1 rule 9, and §10's build-verification agreement). `NeutralMedicineAccent` restored verbatim, with a comment saying why it outlives any palette rebuild: it belongs to no palette, so no palette can retire it. Line endings normalised. Verified green after the fix: `GRADLE_EXIT=0`, 158 tests / 0 failures from a cleared results directory |  |
| R58 | **Fourth full audit pass — and the one finding that mattered is a shape the previous three could not see: a guard that degrades *safely* but *silently*.** Lanes chosen by an `optimization-engines` run over a fully computable spec (zero model turns): `android-app-auditor` + `code-reviewer` + `code-verification-core` + `claude-automation-recommender`, scope "changed since R35", fitness 0.859. What the search deselected is as much the point as what it kept: `coderabbit` scored **feasibility 0** (CLI not installed, requires an auth login and sends diffs to an external API — a user action, not mine); `code-modernization` scored **fit 0.15** (this tree is Kotlin 2.1 / compileSdk 36 / Compose / KSP, there is no legacy to uplift); the official `/code-review` scored **novelty 0.15** (HEAD merged, tree clean, so the diff it would read is empty); `code-simplifier` was excluded as the only *mutating* lane in a request for an audit. The component that changed the ranking was **marginal_value = fit × novelty**: `android-app-auditor` is a perfect premise match and yet worth little here — three prior full passes (R14/R21/R35, the last 0 CRITICAL / 0 HIGH) against only **82 lines** of manifest and Gradle changed since — while `code-reviewer` covers a surface where **44 of 86 source files** changed, +2520/-998. Scoring how well a tool fits without scoring how much has moved since it last ran would have ranked the two backwards. | ✅ **Resolved.** **HIGH — a denied notification permission was invisible in every surface of the app.** `EmivitaApp.kt:129` asks for POST_NOTIFICATIONS exactly once and never again (correct: PERM-023/024 say respect the decision, do not pressure it), and `postIfPermitted` then drops each notification with a `Log.w`. Nothing in the tree called `areNotificationsEnabled()`. So a user who declined once, or who switched notifications off in system Settings a month later, had an app with a populated Today screen and alarms genuinely armed in `dumpsys alarm` that would never post a reminder again. What makes it a defect rather than a choice is that the app had **already solved this twice, ten lines apart**: `FullScreenAlarmAccessCard` and `BatteryOptimizationCard` are passive status cards for the two *weaker* silent failures, and the strongest one had none. Fixed as a third card in the same block, first of the three because it is the only one meaning nothing arrives at all. It also covers a mode no permission check can see — a tier at `IMPORTANCE_NONE`, so an Alarm-tier medicine "that must not be skipped" posts to a channel the user blocked. Only `NONE` counts: a tier merely quietened is a preference, and warning about it would be the nagging PERM-024 names. `ReminderNotificationChannels.blockedTiers()` walks `NotificationPriority.entries` rather than a hand-written id list, so a new tier is covered by construction. Decision logic split into pure functions over plain values (`NotificationDelivery.kt`) for the same reason `EmergencyCardExporter.buildLines` is split from its PDF writer — 6 JVM tests, no Robolectric. **MEDIUM — `ProjectionState` could not say whether it was complete.** When `COMPUTE_BUDGET_MILLIS` stops a pass, `OccurrenceProjectionCache` publishes what it has, which is deliberate; being *indistinguishable from a whole answer* was not. A medicine absent from `stockRunOutDates` meant "will not run out in the window" **and** "was never reached" — one value standing for two facts, which is R56's `error` overload in another costume. Now carries `truncated`, and logs how many of how many medicines it visited. **Two stale claims, both the R47/R56 class.** `ReminderScheduling.kt`'s KDoc still said weekend mode applies "unconditionally, no per-reminder opt-out" — untrue since R36 gated it on `Reminder.weekendModeOptIn`, so one file answered the same question two opposite ways. `AndroidManifest.xml` justified removing `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with "nothing checks isIgnoringBatteryOptimizations" — untrue since R39's `BatteryOptimizationCard`. **The removal is still correct**, and that is precisely why the stale reason survived: nobody re-reads the justification for a decision that is right. Both corrected to the reason that survives. **A finding I raised and then withdrew, recorded because the mistake is instructive.** I reported that weekend mode silently *drops* doses from the projection, with a measurement behind it: a 2-hourly reminder across an 08:00–20:00 Saturday window yields 7 occurrences where an ordinary day yields 13. The measurement was real; the interpretation was wrong. `ReminderAlarmReceiver.kt:145` reschedules from `scheduledEpochMillis + 60_000L` — the *fired* time, which is the pushed one — so the device's own alarm chain performs exactly the walk `occurrencesFor` performs. The six intermediate doses are never armed and never materialize a DoseEvent on a real phone either. **The projection is a faithful simulation, not a lossy one.** What survives is a documentation item, now in `applyWeekendMode`'s KDoc: on a sub-daily reminder weekend mode does not delay a dose, it *skips* every dose inside the window and resumes after, which "delays a dose" (§9, and the R36 migration's backfill argument) describes only for FixedTime. The general lesson is rule 9's twin: **a number measured correctly still has to be interpreted against what the system does, not against what you expected it to do.** **Automation, since two of the four lanes kept pointing at process rather than code.** `.gitattributes` added — R57 recorded CRLF turning a three-line edit into a 990-line diff, and commit b4f897a is the *cleanup* for that with no *prevention*; `core.autocrlf=false` was set on one machine and travels with nobody. Keystore, APK and archive types declared `binary` explicitly rather than left to `text=auto`'s guess, since a wrong guess there rewrites bytes inside the shared signing identity. Verified to renormalize zero existing files. `CLAUDE.md` added, against §10's "one file" rule and worth naming as such: the rule bars a second document competing for findings and rationale, and this carries neither — only the commands from §10 that an agent will not find 185 KB into SPEC.md, and a line saying SPEC.md wins wherever they disagree. Verified: `EXIT=0`, BUILD SUCCESSFUL, **166 tests / 0 failures** (158 → 160 → 166) from a cleared results directory, `lintDebug` green |  |
| R59 | **Device pass for §9 #19 Stages A and B, and the four defects the owner found by using the phone that no test and no en-XA sweep had reached.** Scope chosen by an `optimization-engines` run over a fully computable spec (zero model turns; fitness 0.869 after two scorer corrections, both disclosed: `talkback` had been coupled to the ledger's effort and `owner_fit` gave no credit for spending the owner's minutes on the two checks only a human can make). Selected: en-XA by agent screenshots (`cmd locale set-app-locales`, `screencap`, Read the PNGs), ar-XB in, the real accessibility tree via `uiautomator dump` per screen, TalkBack by the owner's ear, the R58 notification states made in system Settings, the OLED/texture/splash checks by the owner's eye, a ledger row. Deselected with reasons: the Espresso accessibility gate (`espresso-accessibility` is not in the offline cache; a follow-up, not a failure), a re-run of the §9 #20 suite (code unchanged since 5/5 — later re-run anyway once the alarm channel changed), the `dist/vc1` upgrade path. | ✅ **Resolved**, owner checklist partly open. **What the automated half found.** en-XA rendered **seven** plain-English strings the sink-based gate structurally cannot see (commits 88562ca, 68d0def): the three theme-mode labels derived from `enum.name`, a formatter's fourth argument, the R58 card's title and body, and the `"dose"` unit fallback — every one a literal assigned before it reached a sink, exactly the blind spot the gate's own KDoc names. Its glyph traps are worth recording so the next sweep does not re-learn them: en-XA maps b→ɓ, f→ƒ, v→V and x→Cyrillic х, so a naive "is this accented?" match reported the R58 card as absent when the screenshot showed it present in plain English — the false NONE was mine, the leftover was real. `uiautomator dump` confirmed the nodes the ten Robolectric tests assert exist in Android's own tree with the same roles, states and descriptions (dose rows `checkable`/`stateDescription`, calendar cells with per-status counts, the biometric row as one `Switch`, radio rows as one node each); `onClickLabel` is not surfaced in a dump, so that half stays Robolectric-only. ar-XB: no clipped or overlapping text on the four tabs at 30% expansion; RTL mirrors correctly. R58 states: revoking `POST_NOTIFICATIONS` produced the "can't reach you" card, re-granting removed it, on the same screen without restart (captures `50_`–`53_`). **What the owner found — four defects, each a shape the tests could not hold.** (1) *The Medicines header was a different, taller component* than the other three tabs: a Scaffold/TopAppBar where the others use a plain Row. Replaced with the same header; the first fix still sat 34 px low because an `IconButton`'s 48 dp minimum target stretched the row, so the button now floats in a Box and the list padding matches; measured in the dump at 144/144 header and 281/281 first-card top against Today, not eyeballed. (2) *No medicine could be given a priority.* `Medicine.notificationPriority` existed from the first schema and picked the channel every reminder posts to, and no screen ever set it — every medicine was STANDARD, so the High and Alarm tiers, `AlarmActivity`, and the `FullScreenAlarmAccessCard` that explains it were **unreachable from the UI**. A section in the medicine form now, one selectable row per tier using the same name and description resources as the Notifications & Alerts screen and the channels themselves. Found by a person expecting an alarm and getting a chime; no test asserts that a column is *settable*. (3) *The Due row squeezed the medicine name to three characters* beside two labelled chips — "Ser / tral / ine". Chips moved to their own line. The chips only render for a PENDING dose and Robolectric asserts semantics, not pixels. (4) *The Alarm tier did not ring and was not full screen.* Diffed against medTimer's `ReminderAlarmActivity`: this port had `setShowWhenLocked`/`setTurnScreenOn` and stopped. medTimer hides the system bars, loops the alarm ringtone through a `MediaPlayer` on `USAGE_ALARM` (the stream that rings through vibrate mode and, by default, DND) and vibrates 500/500 until answered; the port had none of it. And `dumpsys notification` showed the "Alarm" channel had been created with the defaults — `mSound=notification_sound usage=USAGE_NOTIFICATION vibration=false` — a notification channel wearing an alarm's name. A channel's sound is fixed the moment it is first created, so the fix is a new id `reminders_alarm_v2` with the alarm tone on `USAGE_ALARM` and vibration, the legacy channel deleted (`mDeleted=true` confirmed), `CATEGORY_ALARM` set, and the `VIBRATE` permission the manifest never had. `theAlarmTierChannelIsActuallyAnAlarm` now pins the three channel properties on the device — nothing on the JVM can see what a channel was created with, which is precisely why this shipped. Not a defect: on Android 14 a full-screen intent becomes a heads-up banner while the screen is on and unlocked; the activity launches only when the phone is off or locked, and medTimer is subject to the same rule. **Still open from the owner's checklist:** hearing the Alarm tier ring; the TalkBack walk (Today row, calendar, biometric switch, radio rows, palette cards); the six palettes at low brightness in daylight; the R55 chart texture at 6 dp after a taken dose; the splash flash; blocking the Alarm channel alone to see the "one tier is blocked" card. Flagged, not fixed: `MedicineDetailScreen` still uses the tall Scaffold header the Medicines tab just lost; battery optimisation is on for Emivita on the test phone (the R39 card is showing there, correctly); Glance widget semantics. **Lesson, rule 18 below:** the four owner findings were all *reachability* and *layout* — a settable column nobody could set, a component nobody compared, a row nobody rendered at phone width, a channel nobody inspected — and every one was invisible to a suite that asserts behaviour on nodes it constructs itself. Verified: JVM `EXIT=0`, **178 tests / 0 failures** (166 → 178) from a cleared results directory, `lintDebug` green; device `NotificationDeliveryDeviceTest` 3/0/0 on LE2123 |  |
| R60 | **Code-level diff against medTimer v1.24.6 — 285 feature rows, every one cited on both sides.** §5 was a category-level reuse map, and R59 showed what that granularity hides: a row marked "ported" with the half that rings missing. Method chosen by an `optimization-engines` run over a fully computable spec (15,552 points, 3,888 feasible, enumerated exhaustively — the engine's own search reached 0.898, the exact optimum is 0.907): compare by feature row then diff paired functions; evidence by citation *plus* medTimer's own scheduler tests transliterated onto Emivita's engine; six Opus 5 subagents by area with ≥2 claims per area re-read against source by me; the ranked as-is list with per-function diffs; parity tests and the eight gate-blind literals landed in the repo; recorded here; MIT notice at first import; adoption the owner's call. Two tie-cluster calls (top four within 0.005) decided by argument and disclosed: no `THIRD_PARTY_NOTICES.md` until something is actually copied (a notice for a dependency the tree does not have is the R47/R56 stale-claim class), and the five literals included despite losing by 0.006 on a two-minute overrun of a 360-minute budget constant. Deselected: porting `SchedulingSimulatorTest` (budget-bound; +0.003 evidence for −0.016 effort — a follow-up), `BackupFieldParityTest` (an *adoption* candidate, E-16), inline execution (2× wall-clock for a risk delta spot-verification covers). **Spot-verification: 17 claims across the six areas re-read against source, 0 wrong.** | ✅ **Resolved** for the diff, the three scheduling defects and the eight strings; the adoption list is with the owner (§9 #21). **Tally** (Appendix E): 285 rows — 92 inspiration, 73 equal, 51 Emivita-better, 42 medTimer-better, 23 n/a, **4 import-as-is** (`shouldBeSilent` chime suppression B-06, `getMedicineName` masking B-39, `CalendarHelper` run-out calendar intent C-20, `ChartSeriesColors` D-26 — every other candidate is typed on medTimer's entities, Hilt, or `PreferencesDataSource`). **§5 corrected in one large way** (§5.1): medTimer's UI is no longer "Fragments, XML — not portable"; its medicine list, Statistics screen, `SortableTable` and theme are Compose, and the medicine list has the drag-reorder Emivita has a `sortOrder` column for and no UI. **The instrument that made "is Emivita an improvement" a count rather than an opinion:** thirty of medTimer's `schedulertests` cases ported by meaning (medTimer derives "next" from event history, Emivita from `notBefore` plus an anchor). Run first against the unfixed code: **21 parity, 4 documented divergences asserted as Emivita's behaviour with their Appendix E row, 4 failures — each for the reason the medTimer test names**, plus one regression guard for the fix. **HIGH — an ON_RESOLVED interval or window was re-armed while a dose was still unanswered.** `SchedulingEngine` anchored on `getLastResolved` (which skips PENDING) and never asked whether a dose was outstanding, so `rescheduleAll()` at boot, or any edit of the reminder, armed a second alarm at `previousResolution + k·interval` and materialised a second PENDING dose beside the first. medTimer's `IntervalScheduling` returns null in exactly this state, with the comment "otherwise the notification loop would fire continuously"; its ported test failed here with the second slot armed. Fixed on the alarm path; the projection walk instead assumes the outstanding dose resolves at its slot, as medTimer's simulator does, so the run-out date does not jump while a dose sits unanswered. **HIGH — a late-delivered alarm replayed every missed slot.** `ReminderAlarmReceiver` rescheduled from the fired slot + 1 min with no floor and `AlarmScheduler` arms a past instant, which AlarmManager fires at once: a clock set forward three days chained every missed dose as a notification. medTimer bounds catch-up to today (`raisedToday`, `adjustToToday`). Floor at now in `RealSchedulingCoordinator.scheduleOrCancel`, the one point every caller passes. **MEDIUM — a ONCE expiration alert never re-armed for a new pack.** Nothing cleared `lastAlertNotifiedEpochDay` when a later expiry was entered; the recorded day now blocks only inside the current lead window. **Two documentation defects:** `ReminderConfig` justified the cyclic pattern's before-anchor behaviour as "matching medTimer's own documented behavior" — medTimer's `StandardScheduling` does the opposite and its test pins it; kept as a deliberate divergence, justification corrected. §5 called the stock sentinel "adopted verbatim" — same type and default, but medTimer writes `stockBefore` at raise time and never reads the pair for undo, Emivita writes it at resolution and undo depends on it (C-08). **Eight gate-blind literals** (Stage A part 4): the notification body itself, the alarm placeholder, the stock widget subtitle, the stock screen's run-out line, two save errors, the wrong-password message — every one assigned to a `val` or state first, none rendered by R59's sweep because each needs an error path, a cold widget, or a run-out. **Verified defects left for the owner, all S-sized, none fixed here because each changes behaviour beyond what a diff was asked for** (Appendix E, per-area Findings): Standard/High reminder and both alert notifications have no content intent — tapping them does nothing (B); snooze and nag alarm slots are keyed per reminder, so two unanswered doses of one reminder share a slot and the second snooze cancels the first (B); no `rescheduleAll()` on app launch, so after a user force-stop reminders stay dead until reboot or an edit (B); the engine's zone is captured once in a lazy singleton and nothing handles `TIMEZONE_CHANGED`/`TIME_SET` (B); a dose reminder cannot be deleted, paused or disabled from any screen — `ReminderRepository.delete()` and `setEnabled()` have no caller outside the alert toggles (C/D); correcting a Missed dose to Taken with a different amount decrements stock by the *old* amount because `resolve()` runs before `editDoseAmount()` (C); "Refill now" on a medicine with no saved unit inserts a REFILLED row and changes nothing (C); `lockRemindersUntilTakenByDefault` is persisted, read, and settable from nowhere (D); backup restore uses the default `Json`, so a file from a newer Emivita fails on the first unknown key and `schemaVersion` is neither written nor checked (E); Medical ID edits never trigger auto-sync — `ProfileRepository.update` is the one mutation without `notifyChanged()` (E); a null SAF stream on history export writes nothing and says nothing, the shape R23 fixed for backup (E); the History table sorts by scheduled time while displaying resolved time (D); the CSV row pairs the scheduled date with the resolved time, a timestamp that never happened (E); hard-coded `HH:mm`/`M/d` patterns and a locale-blind `formatDecimal` that Stage C will expose (D/E/F); the production date and `Medicine.notes` are columns no screen can set (C). **Lesson, rule 19 below.** Verified: `EXIT=0`, BUILD SUCCESSFUL, **208 tests / 0 failures** (178 → 208) from a cleared results directory, `lintDebug` green; `origin/master` 0/0 after push |  |

---

## 8. Current implementation status

**Built and functional:** encrypted Room DB + full repository layer,
scheduling engine end-to-end (§4), notification posting, boot recovery,
Settings *home* screen (real UI, grouped list, matches confirmed mockup —
every detail destination has real content: Data & Backup, Notifications &
Alerts, Appearance and Medical ID as routed screens, Alarms & Snooze and
Weekend Mode as bottom sheets, biometric lock inline — see below), app shell (bottom-nav Scaffold, type-safe `NavHost`, theming
works end-to-end), Stock & Expiration screen (§6/§9 #1), cascade-delete
medicine flow (§9 #1), the full Add/Edit Reminder Advanced-fields UI
(auto-resolve, active date range, repeat-until-answered/nagging — §3/§9
#1), and medTimer-derived nagging alarm mechanics end-to-end (§9 #1).
**Also completed this batch (§9 items 0/0b):** project identity is now
`com.hazia.emivita` / "Emivita" throughout (was `com.personal.medkeeper` /
"MedKeeper" — see R4/R5/R9 in §7), and the theming *mechanism* carries a
real palette set in `colorSchemeFor()` rather than the
Clinical/Cozy/Neutral/Playful one §6 replaced (see T3/T4 in §7).

**Current palette set: the 6 of Appendix A** (Daylight, Sage, Honeycomb,
Linen, Mallow, Periwinkle), wired in `colorSchemeFor()`. They replaced the
8 that item 0b shipped (Golden Hour → Velvet Ribbon), which are gone from
the codebase — see §7 R56 for why the register changed and what it cost.
Two roles beyond Material3's ColorScheme travel on `LocalEmivitaColors` and
read as `EmivitaTheme.colors.attention` / `.neutralFill`; every one of M3's
22 slots was already spoken for, and borrowing one whose name means
something else is the overload R56 exists to undo.

**Notifications & Alerts** (`NotificationsAlertsScreen`): lists the 3 real
notification channels (Standard/High/Alarm) with plain-language
descriptions and a button deep-linking to Android's own
`ACTION_APP_NOTIFICATION_SETTINGS` for per-channel sound/vibration — a
deliberate scope boundary, not a stub: Android doesn't let an app override
a user's own per-channel choice once the channel exists, so there's
nothing further for in-app UI to do here.

**Appearance** (`AppearanceScreen`): palette grid (all 6, §6) + theme-mode
picker (Light/Dark/System), `SettingsRepository`-backed. This is the *only*
place either is ever chosen: the onboarding screen this paragraph used to
compare against was deleted outright (§9 #1's reversal), so a fresh install
takes `SettingsRepository.palette`'s default with no prompt. That default is
no longer arbitrary — R56 made it `DAYLIGHT` on purpose (§6) — so this
screen now gives a *chosen* default a revisit path rather than papering over
an accidental one. The grid chunks two per row, so six fills three rows
evenly where eight filled four.

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

0. **✅ Rename batch — shipped.** Applied §1's identity decision across the
   tree: `com.hazia.emivita` / "Emivita", replacing `com.personal.medkeeper` /
   "MedKeeper" (§7 R4/R5/R9). Fixed alongside, since the build file was already
   open: `security-crypto` was pinned to a `1.1.0` that corresponds to no
   published artifact, repinned to `1.1.0-alpha07`.

0b. **✅ Palette replacement batch — shipped.**
    ⚠️ **Superseded by §7 R56:** the 8 palettes this item shipped were
    themselves replaced by 6, on a register decision this item never made.
    Appendix A is current; this entry is the record that the *first* full
    replacement happened. It swapped the 4 original palette objects
    (Clinical/Cozy/Neutral/Playful) for 8, resolved `secondary`/`tertiary`/
    `error` across all of them in `colorSchemeFor()` (§7 T3), and wired
    `onSurfaceVariant` to the audited mutedText token (T2).

1. **✅ Add/Edit Medicine + Add/Edit Reminder UI, Today/Medicines on real
   data — shipped.** Blocked everything else; nothing past it was testable
   without it. Built against §3's full schema even though the form exposed the
   3 core types first. Everything this item once carried as a caveat has since
   closed: `autoResolve` and the `activeFrom`/`activeUntil` clamp are wired
   (`ReminderAlarmReceiver`, `SchedulingEngine.nextOccurrenceFor()`);
   `repeatOnUnanswered` nagging is built on medTimer's secondary-alarm slot
   mechanism rather than guessed at as a clamp; edit shipped as the immediate
   follow-up to add, reusing the same field-level form bodies rather than a
   second form. **Two boundaries still hold:** "Scan to find" is a lookup
   against `Medicine.barcodeValue` in the on-device DB, never an online product
   database, so it stays inside §1's no-`INTERNET` constraint; and scanning is
   a delegated `Intent` to an installed scanner app, so there is no camera
   permission and no new dependency. Detail in §7 R14/R21/R23 and in git.

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
4. **✅ Shared occurrence-projection cache** (§2's locked decision; Emivita's
   equivalent of medTimer's `FutureRemindersRepository`, verified against that
   source rather than assumed). A singleton projecting reminder occurrences
   over a requested window and computing each medicine's stock run-out date in
   the same pass. **Three properties are load-bearing and should survive any
   rewrite:** recomputes are debounced through a conflated channel with a
   1-second delay, so rapid edits collapse into one pass rather than one each;
   the computation carries a hard time budget so a pathological case returns
   partial results instead of blocking; and `DEFAULT_WINDOW_DAYS` is a **floor**
   rather than an empty-map fallback — §7 R28 is what happens when it is not.

5. **✅ Today screen** (full timeline UI from §6) — consumes the
   projection cache.
6. **✅ History/Calendar screen** — future entries from the projection cache,
   past entries from a direct DB query (medTimer's own split, ADR-0002; History
   reads only the direct-query side, Today owns the forward-looking half).
   Shipped: list with adherence summary grouped by day, CSV/PDF export (#10),
   the Vico adherence chart (§7 R13, channel-hardened by R55), plus Calendar
   (month grid with day dots, tap-a-day detail reusing `HistoryEntryRow`) and
   Table (column-sort toggling both active column and direction).

7. **✅ Widgets** (Glance, not RemoteViews — see §5) — reads the
   projection cache rather than running its own simulation. Two widgets:
   Next Doses and Stock Levels, each with a one-tap action (Take / Refill).
8. **✅ `LowStockAlert`/`ExpirationAlert` triggering** — **revised by §7
   R37.** Both are ordinary scheduled reminders: in `SchedulingEngine
   .supportedTypes`, each config carrying an `hour`/`minute`, armed through
   `AlarmScheduler` like a dose, with `ReminderAlarmReceiver` routing firings to
   `handleAlertFire`, which re-checks the condition against live data before
   notifying. The sentence this item originally got wrong is kept because it is
   worth being able to find: it claimed the data-change-driven evaluator was
   sufficient, which is *true only for medicines that have scheduled doses* — a
   box in a cupboard produces no database writes, so its expiry alert fired only
   at boot or when the app happened to be opened, and that is exactly the
   population these alerts matter most for.

9. **✅ SAF backup/restore + auto-sync folder (password-protected ZIP)** —
   medTimer's `BackupManager`/`FileHelper` pattern for the SAF plumbing (§5);
   the password layer is original, because medTimer's database is not encrypted
   and Emivita's is (§2). Two needs — manual disaster-recovery export/restore,
   and an unattended auto-sync to a chosen folder — reconciled into **one
   writer with two triggers**, per §2's locked decision, rather than two
   subsystems. No periodic job: this item and #4 both use debounce plus
   explicit triggers, and a stale manifest comment advertising "periodic
   auto-backup" was removed under item 0.

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
    (§3/§4). All built. The R8 scoping call held: `CyclicActivePause` is a 4th
    `RepeatPattern` case inside the existing Fixed Time card, not a new
    top-level picker card — so the picker is 4 cards, not the 6 an earlier
    draft anticipated, because the two alert types came out of it (§7 R15).
    That closes this item's old "or nested under an Advanced entry point — not
    yet decided" question by what shipped.

15. **✅ Cheap fixes** — R1 (transaction-wrapped `addTag`), R2 (dead
    `decrementStock` wrapper, deleted rather than kept), R3 (real coverage for
    `resolve()`/tag-dedupe, against an in-memory Room instance rather than
    mocked DAOs, since `withTransaction` is an inline extension MockK cannot
    intercept), `asOnSurfaceSafe`, launcher icon, placeholder copy. The
    `EncryptedSharedPreferences` deprecation was resolved rather than deferred:
    `PassphraseProvider` now wraps the passphrase with a direct `AndroidKeyStore`
    AES/GCM key, so there is no deprecated dependency left to migrate.

17. **✅ Compose UI tests — shipped (§7 R47).** Numbered here rather than
    left implicit because §8 is explicit that this list is the ledger, and
    because the project had none: `app/src/androidTest` did not exist, so
    every UI claim in this file was verified by hand on a phone, and §7
    R46's own note records that verification being abandoned when the
    device turned out to be shared mid-session. R47 found the half R46
    could not verify was the half that was wrong.
    They live in the **unit** source set under Robolectric, deliberately
    not in `androidTest`: CI runs `assembleDebug`/`testDebugUnitTest`/
    `lintDebug` with no device attached, so an instrumented suite would
    never have executed, and a suite that cannot run is worse than none
    because this ledger starts crediting it. The trade to know: Robolectric
    draws to a shadow canvas, so assertions about semantics, click handling
    and text are sound and anything about real pixels, animation timing or
    fonts is not — every UI test here is written against semantics only.
    Suite: 110 → 152.

18. **✅ Medical ID + emergency card — shipped (§7 R48).** Promoted out of
    the parking lot below; see that entry and R48 for scope and for the
    two boundaries it keeps.

19. **⏳ Internationalisation + accessibility — Stages A ✅ and B ✅ complete, C open.**
    The first item on this roadmap that is not a feature. §1 caps what this
    app can become: single user, single device, no `INTERNET`, no Play Store,
    so the medTimer capabilities Emivita lacks most visibly (multi-user,
    sync) are *excluded by constraint*, not unbuilt. With items 0–18 shipped,
    what separates this from the apps it takes inspiration from is **reach —
    who can use it — not capability.**

    **Measured surface, not estimated.** 159 user-visible string literals
    across 32 files (the union of Compose text, notification and channel
    text, and PDF text — a Compose-only count misses channel names, which
    Android shows in its own settings UI). 3 strings are in `strings.xml`
    today. **89 of the 159 are interpolated**, and that is the real cost: an
    interpolated string is not a find-and-replace, it becomes a positional
    format argument so a translator can reorder the sentence. ~170
    interactive elements, **0 `semantics {}` blocks**, and 9 real
    `contentDescription`s against 14 nulls.

    **Why accessibility is the sharper half.** R55 gave the adherence chart a
    *texture* channel because colour alone must not carry state (§7.1
    rules 10–13), and six palettes were re-derived around that rule. TalkBack
    currently receives **neither** channel. Having solved colour-vision
    deficiency by measurement and left screen readers at zero is the largest
    inconsistency left in the codebase, in an app whose users skew toward the
    people most likely to need one.

    **Neither half is visible to the build.** Lint's `HardcodedText` does not
    fire on Compose, which is how 159 strings passed a gate configured with
    `warningsAsErrors = true`; there is no accessibility check in the
    pipeline at all. §7.1 rule 14's shape exactly.

    **Scope chosen by an `optimization-engines` run** over measured constants
    (zero model turns), f=0.902: extract everything · pseudolocale plus one
    real locale · labels **and state** · gate both halves · include the
    non-Compose text. Two *hard constraints* did the structural work, and
    both restate rules this repo already holds: a source-scan gate requires
    **complete** extraction, because a gate failing on the part deliberately
    skipped must be baselined on day one — which `app/build.gradle.kts`'s own
    lint comment calls the habit that turns a baseline into a way of never
    reading lint again; and for the same reason it requires the non-Compose
    text too, since a scan with carve-outs is a baseline wearing a different
    hat. So "extract all 159, include the PDF and notification text, gate it"
    is one indivisible unit and everything else is genuinely optional.

    **Staged, because it does not fit one sitting.**
    **A ✅ (two commits, 2026-09-08 and 2026-09-12):** `isPseudoLocalesEnabled` on
    debug, every user-visible literal extracted, the non-Compose text
    included, the source-scan gate added and verified to bite. Reaches no new
    user; makes the next translation hours rather than a rewrite, and makes
    an un-extracted string *visible on the screen* rather than a claim.
    **How Stage A actually went, because the numbers moved four times.**
    159 → 235 → ~300 → **323 strings and 3 plurals**, and the code never
    changed between counts: the detection method was the limit each time. A
    line-based scan missed 27% by not seeing multi-line `Text(` calls. A
    sink-based scan then missed ~70 that reach a sink only after being
    assigned to a `val`, returned from a `when`, or carried in a data class —
    the four bottom-nav labels, every Settings row, every status label, the
    form titles. A prose-shaped regex missed "Reminders & Alarms" for the
    ampersand. Each pass was honest about its own scope and wrong about the
    total, which is §7.1 rule 9 in a different costume: a detector that
    reports nothing has proven only that *it* found nothing.

    **What needed a decision rather than an edit, and what was decided.**
    (a) `historyStatusLabel` fed both the CSV and the PDF, which now disagree
    about translation: split into `doseStatusLabelRes()` (a `@StringRes`, one
    function serving the History list, the table, the chart legend, the Today
    timeline and the PDF, where there had been two String copies) and
    `csvStatusLabel()` (English on purpose — a CSV is data for a spreadsheet,
    and a column value that changes with the phone's language breaks whatever
    reads it). (b) `MedPalette.displayName()` is pinned by
    `PaletteSpecConsistencyTest` against Appendix A's headings: **stays
    English**, documented at the declaration — the names are names in the
    sense a typeface has one, and resourcing them would break the one gate
    that keeps this document's tables honest. (c) `EmergencyCardExporter.buildLines`
    is deliberately pure so the whole card can be asserted line by line: it now
    takes a `CardLabels` with an `ENGLISH` default the test uses and a
    `from(Resources)` production uses, the same shape as (a). (d) `"Me"` is a
    sentinel that is written to the database and later recognised by
    comparison; translating it would make a profile created under one language
    unrecognisable under another. Stays. (e) Day-of-week names come from
    `java.time`'s `getDisplayName`, which is already localised, not from
    resources. (f) `MedicineIconOption`'s first argument is `Medicine.iconKey`,
    persisted: never translated, excluded from the gate by name.

    **Moving text into XML put it under checks Compose literals escape, and
    they fired immediately.** `TypographyDashes` ×9, `TypographyEllipsis`,
    `PluralsCandidate` ×2, and then Compose's own `LocalContextGetResourceValueCall`
    ×10 and `NonObservableLocale` ×2 once strings were resolved in callbacks.
    All fixed, none baselined: real em and en dashes, `history_counts_summary`,
    `history_last_days` and `card_and_more` are `<plurals>`, callbacks resolve
    through `LocalResources.current` (configuration-aware, which a captured
    `LocalContext` is not), and day names read `Locale.current` rather than
    `Locale.getDefault()`. Every one of these was invisible while the strings
    were inline. That is this item's argument, made concrete by lint itself.

    **The gate, `HardcodedUiTextGateTest`.** Same shape as
    `PaletteSpecConsistencyTest`: walks up to the repo root, reads
    `app/src/main/java`, asserts. Sink-based — a literal handed directly to
    `Text()`, `contentDescription`, a notification builder, a channel, `drawText`,
    the biometric prompt, the app's own text-taking composables, an enum's
    display argument, or a `title`/`label`/`saveLabel`/`helper` named argument
    in the UI layers — because a prose-shaped rule would have needed an
    allowlist for (b), (c) and `csvStatusLabel`, and a gate with an allowlist
    is a lint baseline wearing a different hat. Its first run caught
    `CardLabels.ENGLISH`'s `title =`; the fix was to scope the named-argument
    rule to the feature/ui/navigation packages where it is true, not to exempt
    a file. **Its blind spot is stated in its KDoc rather than hidden:** a
    literal assigned to a `val` before a sink. The complement is en-XA on the
    device, where anything the gate misses renders plain English amid accented
    text. Verified to bite on a real mutation (a `Text("Sneaked back in")`
    planted in `TodayScreen.kt` failed the build naming file, line and rule),
    not only on its own built-in samples. Suite 166 → 168.

    **B ✅ (2026-09-12):** semantics on the four surfaces that carried
    meaning non-textually, roles on every clickable, and the two structural
    anti-patterns fixed, gated by ten Compose semantics tests.
    **How Stage B actually went.** Measured before touching anything: **0**
    `semantics{}`, **0** `stateDescription`, **0** `Role`, 29 bare `.clickable`s,
    and 14 `contentDescription = null` — every one of which turned out to be
    *correct* (a decorative icon beside its own text label; nulling those is
    what the guidelines ask for). The fourteen nulls the first scan counted as
    a defect were not one. The defects were structural and the scan could not
    see them at all.

    **The four surfaces.** The Today dose row and the History entry row were a
    clickable Surface with no role, their status text-only: now one merged node
    each with `Role.Button`, an `onClickLabel` ("Edit dose"), and the status as
    `stateDescription` — the channel TalkBack treats *as state*, which is to a
    screen reader what R55's texture channel is to colour-blind eyes. The
    Take/Skip chips keep their own nodes (a test pins that: on the one screen
    where a wrong action is a wrong dose, "Take, button" must be reachable on
    its own). The calendar day cell was the purest instance of the R55 argument
    left in the app — three coloured dots, no text, TalkBack told the day
    number and nothing else — and now says "Tuesday 9 June, 2 Taken, 1 Missed"
    or "…, no doses", with `Role.Button` and `selected`. The adherence chart
    draws to a canvas the screen reader cannot see; its host now carries the
    window's totals through the same `<plurals>` the summary card uses.

    **Two anti-patterns, nine and two instances.** `RadioButton(onClick)` beside
    `Text(Modifier.clickable)` — two nodes per option, a radio with no label
    and a text with no role, in the reminder forms and the dose-edit dialog —
    became one `selectable` row with `Role.RadioButton` and the RadioButton
    made display-only (`onClick = null`). A clickable Row beside a live
    `Switch` — two nodes, both toggling, the row with no state — on the Settings
    biometric row and the Weekend Mode row became one `toggleable` row with
    `Role.Switch`. Found by reading, not by the scan: neither shape has a
    missing attribute to count. Also: the "Advanced" expander's `▲`/`▼` glyph,
    which TalkBack read as "black up-pointing triangle", is an icon with no
    description under a row that says Expanded or Collapsed; the tag chip's
    remove icon says which tag; the colour swatch, a sole-content clickable
    that announced as nothing, says "Pick a color"; the icon and palette
    pickers carry `selected`. After: **5** `semantics{}`, **3**
    `stateDescription`, **25** roles, **13** `selectable`/`toggleable`, and no
    Compose clickable without a role.

    **Compose lint fired once more**, `UnusedResources` on the "Remove" string
    the tag-specific label superseded. Removed rather than kept.

    **Gated, and the gate verified to bite.** `TimelineEntryRowSemanticsTest`,
    `CalendarDayCellSemanticsTest`, `AdherenceChartSemanticsTest`, and two new
    cases in `HistoryEntryRowTest`; `DoseEditDialogTest`'s existing
    `isSelectable()` assertions passed unchanged through the radio-row refactor,
    which is the cheapest possible proof the semantics moved rather than broke.
    Removing `stateDescription` from the Today row fails three of its four tests
    (the fourth, that a locked dose offers no Skip node, does not depend on it —
    correctly). Suite 168 → **178**.

    **Deferred, as planned:** custom accessibility actions (Take/Skip/Snooze from
    a dose row without reaching the chips). The on-device suite (§9 #20) now
    exists, so the reason for deferring — no way to verify a wrong action on
    hardware — is weaker than it was; revisit after a TalkBack pass on the
    device. **Not covered:** the two Glance widgets, whose `GlanceModifier.clickable`
    is a different API with its own semantics model; their text content is
    read as-is, and a `GlanceModifier.semantics` pass is a small follow-up.

    **C**: one real translation; the language is a decision for the owner,
    not derivable from the code.

    **Deliberately deferred: custom accessibility actions** (Take/Skip/Snooze
    reachable from a dose row). Highest-risk edit in the pass, on the one
    surface where a wrong action is a wrong dose, and there is no
    `androidTest/` in this project to catch it — every test here is JVM or
    Robolectric. Revisit with, or after, on-device test automation.

    **Two gates, both cheap because the infrastructure already exists.** The
    source scan is the shape `PaletteSpecConsistencyTest` already uses (walk
    up to the repo root, read files, assert) — no custom lint module, no new
    Gradle module. The semantics gate is content-only: the Compose test suite
    already makes 25 semantics assertions. `isPseudoLocalesEnabled = true`
    was **verified to configure on this AGP** (8.13.2, `./gradlew help`
    returned 0 with it applied) rather than assumed from the property name.

    **Stage A part 4 (R60).** Eight more literals, found by the code-level
    diff rather than by any sweep: every one assigned to a `val` or state
    before reaching a sink, and every one behind a path the R59 sweep did
    not render — an error branch, a cold widget, a stock screen with a
    run-out. 343 → 352 strings. The gate's blind spot is now documented
    three times over; a `val`-aware detector is the follow-up if a fifth
    part ever appears.

20. **✅ On-device test suite — shipped.** `app/src/androidTest/` exists
    for the first time: three classes against the *real* `AppContainer` of
    the *real* `EmivitaApplication` — the same SQLCipher database, the same
    AlarmManager, the same NotificationManager a user's install has. This is
    the layer the JVM suite structurally cannot reach: Robolectric runs the
    migration and repository tests against a JVM SQLite, so nothing in the
    166 JVM tests ever loads `libsqlcipher.so`, wraps a key through
    AndroidKeyStore, arms a real PendingIntent, or posts to a real
    notification manager. §7 R39 proved all of that by hand, once, on a
    OnePlus 9 Pro. This makes that pass repeatable.

    **What each proves.** `SqlCipherRoundTripTest`: a row written through the
    real path reads back, *and* the first sixteen bytes of `emivita.db` on disk
    are not the plaintext SQLite magic — a database that round-trips could
    still be plaintext if the SQLCipher factory were silently dropped from the
    builder, and no repository call can stand in for reading the file.
    `AlarmChainTest`: reminder row → engine → `AlarmScheduler` → AlarmManager →
    `ReminderAlarmReceiver` → `DoseEvent` materialised → notification active,
    through `ReminderRepository.create` so the path under test is the one a
    user's tap takes; it waits generously and names battery optimisation in
    its timeout message, because R39 measured a five-minute deferral under
    battery saver and that is a real result, not a broken chain.
    `NotificationDeliveryDeviceTest`: all three channels exist,
    `blockedTiers()` agrees with what NotificationManager reports, and R58's
    card is wired to live system state — asserting *consistency*, not a fixed
    answer, because the developer's own phone may legitimately have a tier
    blocked and a test demanding "all enabled" would fail on the exact state
    the card exists to describe.

    **It runs on a physical ARM device only, never in CI.** `abiFilters`
    ships no x86 native libraries by design, so no emulator on the CI runner
    can install it. `./gradlew connectedDebugAndroidTest` with the phone
    attached is the whole invocation; CI's `verify` job runs the JVM triad
    and cannot run this. Enabling an emulator job would mean allowing x86_64
    in debug, which is a decision, not a fix — left to the owner.

    **Device results, LE2123 / Android 14 (the R39 device), 2026-09-12.**
    First run, fresh install: 4 of 5 passed, and the fifth
    (`databaseFileOnDiskIsNotPlaintextSqlite`) failed on `file.isFile` — it
    had run before anything opened the lazily-constructed database, so the
    encryption assertion never executed. Fixed to open the database first.
    Second run of that class: **2 / 0 / 0**, the header assertion executed
    and passed. Across the two runs every test has passed on hardware; the
    alarm chain end to end is the one that matters most and it passed first
    time.

    **Two test-authoring lessons, both §7.1 rule 5.** (a) A first draft
    compared the file header against `"SQLite format 3 "` with a trailing
    *space*; the real magic ends in a NUL. No file starts with the draft's
    bytes, so that assertion could never have failed on a plaintext database
    — it would have shipped as a green check that checks nothing. Caught
    before the run; the test now sanity-asserts the magic's own length. (b) A
    lazily-opened resource means a test can run before the thing it inspects
    exists, and fail on the precondition rather than on the property — which
    reads as a failure of the property to anyone not reading the message.
    Both are the same shape as R57: a signal that says something other than
    what it appears to say.

21. **⏳ medTimer code-level diff — done; adoption is the owner's pick.**
    §7 R60 and Appendix E. What is decided: the three scheduling defects and
    the eight strings are fixed; thirty ported tests stay as the parity
    instrument; the four `import-as-is` candidates and the MIT notice they
    require wait for the first actual copy. What is not decided, and is
    presented to the owner ranked with S/M/L in Appendix E.4: the verified
    defects R60 lists (all S), reminder management (pause/delete/duplicate/
    sort/reorder), notification polish (content intents, chime suppression,
    hidden names, swipe action, ringtone picker, combined notifications),
    history editing (reschedule, re-raise, soft-delete, notes, instructions,
    history kept on medicine delete), and the Stage C prerequisites
    (locale-aware time and decimal formatting, decimal-comma input). Each
    becomes its own numbered item when picked; none is built silently.

### Not yet scoped — parking lot ❓

Ideas surfaced in review but deliberately not resolved into the
dependency-ordered items above — captured so they aren't lost, not yet
designed, sized, or prioritized. To be discussed more in depth before any
of them become a real item. (`Medicine.indication`/`storageNote` were
promoted out of this list into §3/#1 — stress-testing found them to be
non-events, nothing left to discuss.)

- ~~**Doctor & pharmacy contact info**~~ — ✅ **promoted and built**
  (§7 R48), so this is no longer a parking-lot item; the paragraph stays
  only because two of its judgements survived into the built feature and
  a third turned out to be wrong. What shipped: seven nullable `Profile`
  columns (blood type, allergies, doctor name/phone/address, pharmacy
  name/phone) behind `MIGRATION_5_6`, a **Medical ID** screen under
  Settings, and a one-page PDF card (`EmergencyCardExporter`).
  The two judgements kept exactly: it is **deliberately not a lock-screen
  widget**, because Android's own Emergency Information covers that case
  better than any third-party app can (readable without unlocking the
  phone at all) while being unable to produce the thing Emivita can — a
  page to hand a new doctor, a card for a wallet; and every field is
  **informational text only, never algorithmically checked against
  anything**, which the screen states in as many words and a test pins.
  The entry lists "pharmacy phone" alone; a number printed with nothing
  naming whose it is cannot be used by the person holding the card, so
  the name ships with it.
  **This entry's "Blocked, not just unscoped" was already stale when R43
  read it, and stayed stale afterwards.** The blocker was "no
  current/active profile concept anywhere", and R43 found that question
  answered in the tree — `EmivitaApp` resolves it in exactly one place,
  `LocalCurrentProfileId provides profiles!!.first().id` — and said this
  entry "is corrected to say so". It was not, until R48. The
  export-scoping worry the blocker raised (two profiles' identities on
  one card) cannot arise in a single-profile app at all.
  One thing nobody anticipated, recorded here because it changes a
  decision elsewhere in this file: an emergency card prints the profile's
  **name**, and `EmivitaApp`'s silent-creation comment justifies the fixed
  `"Me"` on the grounds that a profile's name is never displayed. That
  premise now stops holding on exactly one screen — see §7 R48 for how
  Medical ID holds up its end of it.
- **Tapering schedules** (step-down dosing, e.g. 10mg → 7.5mg → 5mg over a
  set timeline) — real, distinct need (dedicated apps exist for just
  this). Smaller blast radius than first assessed: `DoseEvent.doseAmount`
  is already snapshotted per-event at materialization time (for an
  unrelated reason — so editing a reminder's dose doesn't retroactively
  rewrite history), which is exactly the shape tapering needs.
  **Corrected (§7 R45, applied here by §7 R48).** This entry used to say
  the work "is contained to a new `Tapering` `ReminderConfig` variant (a
  list of `{dateRange, dose}` steps)". That contradicted §4's flat claim
  that a taper is just several ordinary Fixed Time reminders with
  adjacent, non-overlapping active ranges and needs no new config type,
  and the two sections stood endorsing incompatible mechanisms. §4 is
  right, and R45 settled it with two executable tests rather than an
  assertion: a three-step 10 → 7.5 → 5 taper projects exactly nine doses
  at the right levels on the right days, and a second test pins the
  handover, where the real failure mode lives — ranges are inclusive at
  both ends, so an off-by-one at a step boundary is a missed dose or a
  doubled one. The variant reading was *true when it was written*: before
  §7 R30, the activeFrom clamp answered null for a range that had not
  opened yet, so every step after the first projected nothing and a taper
  delivered its opening dose forever. R45 said this entry "is corrected
  to say so"; it was not, until R48.
  Still not promoted, on grounds that survive the correction: a
  single-row taper is a convenience, not a capability. The multi-reminder
  form is manual to enter and easy to leave a gap in, which is a real UX
  argument for the variant and not an argument that tapering is
  unsupported. Revisit if that changes.

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
- **Verify a build against the build, not against an artefact of it.** Never
  put the build on the left of a pipe when its exit code is what you are
  checking — `./gradlew … | tail` reports *tail's* status, and so does the
  `$?` after it. Redirect to a log, read the real code, and grep it for `^e:`
  and `BUILD `. Clear `app/build/test-results` before quoting test counts,
  because that directory persists across runs and will happily report the last
  green one; print the newest result file's mtime beside the numbers. This
  cost three consecutive false greens over a failing compile (§7 R57), and the
  general form is §7.1 rule 9.
- **One file.** Findings, audits, reviews and design rationale go in this
  file — §7's ledger for the finding, an appendix for the reasoning. No new
  standalone Emivita `.md` alongside it, and nothing that matters left
  outside the repo where it cannot be committed (see "Where everything
  went" at the top for what that habit cost).

---

## Appendix A — Palette token reference (full tables)

Referenced from §6's Palettes bullet. Every hex here is computed, not
eyeballed — contrast ratios are WCAG relative-luminance calculations from
`designkit`, and perceptual distances are CIEDE2000 with deuteranopia and
protanopia simulated rather than assumed. Nothing in this appendix is an
estimate.

**This table replaced the eight-palette set (Golden Hour → Velvet Ribbon)
on 2026-09-06 — see §7 R56.** The six below are what `Color.kt` carries.

**Role legend:**

- **bg** — page-level background
- **surface** — elevated card/container background
- **outline** — border/divider color, solved to clear **3:1 against both
  bg and surface** (WCAG 1.4.11) — the fix for T1 (§7)
- **primary** — brand/CTA color, and the *dose taken* state
- **secondary** — supporting role, and the *dose due* state
- **tertiary** — the temperature counterweight: a cool tone in the warm
  palettes, a warm one in the cool ones, so no palette reads monotone
- **attention** — *a missed dose, low stock, an approaching expiry.* Warm
  clay or amber, deliberately **not red**: a hazard light rather than a
  fire alarm. New in R56
- **neutralFill** — *a skipped dose and other deliberate non-events.*
  Near-achromatic on purpose. New in R56
- **error** — **genuine failures only**: form validation, a failed backup,
  a destructive confirmation. R56 narrowed this from the four jobs it had
  accumulated, three of which were not errors
- **text** — primary reading color, always ≥8.5:1 against surface (AAA)
- **muted** — the real, audited `mutedText` token at ≥4.5:1 against
  surface — the fix for T2 (§7)

Every `on*` colour is computed rather than tabled: whichever of pure black
or white clears more contrast against its base. Two tests hold this appendix
to the code, which is the failure mode §7 R47 was opened to close:
`PaletteContrastTest` asserts every ratio against the `ColorScheme` the app
actually builds, and `PaletteSpecConsistencyTest` asserts all 132 hexes in the
tables below against that same resolved scheme — so editing one without the
other is a red build rather than a discrepancy someone notices later. The same
test pins `res/values/colors.xml`'s `splash_background` to the default
palette's light background, a coupling nothing else enforces.

**`docs/palette-mockup.html`** renders all six on the app's real screens, in
both themes, and is generated from these same values.

**Register.** All six are Health / Consumer Wellbeing — patient-facing,
not practitioner-facing. That register's governing rule is that **red is
reserved for genuine emergencies** and must never be an accent, a brand
colour, or the marker of an ordinary event; and that grounds stay warm,
because the clinical blue-grey that reassures a professional reads as
institutional to someone managing their own medication. Both cool
palettes (Mallow, Periwinkle) keep a warm ground for exactly that reason.

**OLED note:** every dark `bg` stays above 5% lightness rather than
reaching for black — on the OLED panels most phones ship, `#000000`
switches the pixel off, so content smears as it scrolls and light text
haloes around its own edges.

**Astigmatism/eye-comfort note** (reasoned extension, not in any consulted
skill file): no pure `#000000`/`#FFFFFF` anywhere, and peak dark-mode
text/surface contrast kept near 15:1 rather than pushed to extremes. Pair
this in implementation with no glow/blur box-shadows on bright dark-mode
elements, and regular/medium body-text weight rather than thin.

**How these values were chosen.** The two new roles, and the token
assignment behind them, are the winner of an `optimization-engines` run —
a fully computable spec, zero model judgements — over four measured
components: register_calm 1.000, state_separation 0.988, legibility
0.809, error_reserve 0.852, total fitness **0.9265**, beating the
runner-up assignment by 0.15. Saturation was **pinned** to the register's
band and only lightness searched: lightness is the axis colour-vision
deficiency spares, chroma is the axis that reads as alarm, so optimising
the one that is actually free beats letting a search trade away the
constraint that mattered. Two corrections during that run are recorded in
§7 R56 because both changed the answer. The clay hue offset (+4° off each
palette's own anchor) is shared across all six deliberately — tuned per
palette it drifted to wine and gold, and the six stopped looking like one
family.


### 1 · Daylight — "the quiet default"

Health / Consumer Wellbeing -> **Daylight**. Taken verbatim from the register's own verified pair. Bone paper and a green that reads as calm rather than clinical.

| Role | Light | Dark |
|---|---|---|
| bg | `#FBF9F5` | `#121412` |
| surface | `#F1EEE5` | `#1C201D` |
| outline | `#847E70` | `#787D75` |
| primary | `#2C6050` | `#7CBAA2` |
| secondary | `#4F7365` | `#9AB4A9` |
| tertiary | `#7E5E3C` | `#CDA97E` |
| attention | `#B97B34` | `#CA8C44` |
| neutralFill | `#A69C89` | `#8C7F69` |
| error | `#A32F29` | `#E8837B` |
| text | `#22211D` | `#E9E7E1` |
| muted | `#5E5A51` | `#9A978F` |

Checks — Light: text/surface 13.9:1 · muted/surface 5.9:1 · outline/surface 3.5:1 · outline/bg 3.8:1 · on-attention/attention 5.9:1 · chart triple ΔE 17.1 worst case incl. CVD  
Dark: text/surface 13.3:1 · muted/surface 5.7:1 · outline/surface 3.9:1 · outline/bg 4.4:1 · on-attention/attention 7.3:1 · chart triple ΔE 15.7 worst case incl. CVD · bg lightness 7.5%

### 2 · Sage — "softest of the six"

Health / Consumer Wellbeing, hue-anchored to Naturecore's herbal band. A windowsill herb pot. The one palette whose primary is an olive, which is also why its chart triple needed the widest lightness ladder -- olive and clay are the pair deuteranopia collapses hardest.

| Role | Light | Dark |
|---|---|---|
| bg | `#F6F6F0` | `#12140F` |
| surface | `#EBEDE2` | `#1C1F18` |
| outline | `#7C856A` | `#767C6B` |
| primary | `#4F6733` | `#A3BE7B` |
| secondary | `#6B7D4A` | `#AFBC94` |
| tertiary | `#6E583F` | `#C6A987` |
| attention | `#C69762` | `#A4743C` |
| neutralFill | `#999692` | `#C9C7C5` |
| error | `#A32F29` | `#E8837B` |
| text | `#23261C` | `#E7E9DF` |
| muted | `#585E4C` | `#95998A` |

Checks — Light: text/surface 13.0:1 · muted/surface 5.7:1 · outline/surface 3.3:1 · outline/bg 3.6:1 · on-attention/attention 8.0:1 · chart triple ΔE 17.4 worst case incl. CVD  
Dark: text/surface 13.6:1 · muted/surface 5.7:1 · outline/surface 3.9:1 · outline/bg 4.3:1 · on-attention/attention 5.1:1 · chart triple ΔE 16.0 worst case incl. CVD · bg lightness 6.9%

### 3 · Honeycomb — "warm without glare"

Health / Consumer Wellbeing, hue-anchored to Cozy's amber band. A cool green tertiary is the temperature counterweight; without it the palette goes sticky.

| Role | Light | Dark |
|---|---|---|
| bg | `#FDF8EE` | `#15120B` |
| surface | `#F6EFDE` | `#201C13` |
| outline | `#8C7B62` | `#867D64` |
| primary | `#7E5A1B` | `#D8B26A` |
| secondary | `#9C7B36` | `#BFA57C` |
| tertiary | `#3F5A4E` | `#86B39F` |
| attention | `#B28875` | `#906350` |
| neutralFill | `#696763` | `#918F8B` |
| error | `#A32F29` | `#E8837B` |
| text | `#2A2318` | `#F0E9DA` |
| muted | `#655B49` | `#9D9480` |

Checks — Light: text/surface 13.5:1 · muted/surface 5.8:1 · outline/surface 3.6:1 · outline/bg 3.9:1 · on-attention/attention 6.7:1 · chart triple ΔE 18.2 worst case incl. CVD  
Dark: text/surface 14.0:1 · muted/surface 5.6:1 · outline/surface 4.1:1 · outline/bg 4.6:1 · on-attention/attention 5.1:1 · chart triple ΔE 17.0 worst case incl. CVD · bg lightness 6.3%

### 4 · Linen — "warmest of the six"

Health / Consumer Wellbeing -> **Linen**. The register's second verified pair. Its `error` is the only one pushed to oxblood, because a terracotta primary and an ordinary red error are otherwise too close to tell apart.

| Role | Light | Dark |
|---|---|---|
| bg | `#F8F5F1` | `#191614` |
| surface | `#EFE8E0` | `#231E1A` |
| outline | `#877364` | `#857569` |
| primary | `#8E4826` | `#D9906A` |
| secondary | `#A05F3C` | `#C29B80` |
| tertiary | `#3E5B5B` | `#86B0AC` |
| attention | `#C47464` | `#A24F3E` |
| neutralFill | `#696763` | `#878480` |
| error | `#8E1F38` | `#EE7E93` |
| text | `#26221E` | `#EAE5DF` |
| muted | `#645A4F` | `#9E948A` |

Checks — Light: text/surface 13.0:1 · muted/surface 5.5:1 · outline/surface 3.7:1 · outline/bg 4.1:1 · on-attention/attention 6.0:1 · chart triple ΔE 16.7 worst case incl. CVD  
Dark: text/surface 13.2:1 · muted/surface 5.5:1 · outline/surface 3.7:1 · outline/bg 4.1:1 · on-attention/attention 5.6:1 · chart triple ΔE 17.9 worst case incl. CVD · bg lightness 8.8%

### 5 · Mallow — "soft, not sweet"

Health / Consumer Wellbeing, hue-anchored to Coquette/Girly's muted register. Dried plum. The only palette whose brand colour is a true violet-red.

| Role | Light | Dark |
|---|---|---|
| bg | `#FAF6F8` | `#161215` |
| surface | `#F2EAEF` | `#201A1F` |
| outline | `#87737F` | `#7E6F79` |
| primary | `#7A3F63` | `#C595B4` |
| secondary | `#8E5C7C` | `#B79FB0` |
| tertiary | `#6E5A32` | `#C3B07A` |
| attention | `#7C5A2E` | `#BC8B4A` |
| neutralFill | `#948C80` | `#898173` |
| error | `#A32F29` | `#E8837B` |
| text | `#251E23` | `#EDE5EA` |
| muted | `#615560` | `#9A8D98` |

Checks — Light: text/surface 13.8:1 · muted/surface 6.0:1 · outline/surface 3.7:1 · outline/bg 4.1:1 · on-attention/attention 6.3:1 · chart triple ΔE 21.3 worst case incl. CVD  
Dark: text/surface 13.8:1 · muted/surface 5.4:1 · outline/surface 3.6:1 · outline/bg 3.9:1 · on-attention/attention 6.9:1 · chart triple ΔE 16.5 worst case incl. CVD · bg lightness 7.8%

### 6 · Periwinkle — "the one cool option"

Health / Consumer Wellbeing, hue-anchored to Scholastic's indigo, warmed. A tan tertiary and a warm ground are what stop a cool primary reading institutional -- the register's own stated failure mode.

| Role | Light | Dark |
|---|---|---|
| bg | `#F8F7FB` | `#131319` |
| surface | `#ECEBF3` | `#1D1D25` |
| outline | `#77758C` | `#73728A` |
| primary | `#4E4590` | `#A3A0DC` |
| secondary | `#6A6296` | `#A8A5C2` |
| tertiary | `#7A5A3E` | `#C9A57E` |
| attention | `#B98952` | `#A57742` |
| neutralFill | `#B4AFA6` | `#BCB7AF` |
| error | `#A32F29` | `#E8837B` |
| text | `#1F1E2A` | `#E6E5EE` |
| muted | `#565469` | `#918FA0` |

Checks — Light: text/surface 13.9:1 · muted/surface 6.2:1 · outline/surface 3.8:1 · outline/bg 4.2:1 · on-attention/attention 6.8:1 · chart triple ΔE 18.7 worst case incl. CVD  
Dark: text/surface 13.4:1 · muted/surface 5.3:1 · outline/surface 3.6:1 · outline/bg 4.0:1 · on-attention/attention 5.3:1 · chart triple ΔE 23.6 worst case incl. CVD · bg lightness 8.6%

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

## Appendix D — Candidate palettes (§7 R49–R54) — ⛔ superseded, kept as record

⛔ **These never shipped, and never will.** They were candidates to replace
the *eight*; §7 R56 replaced the eight with six on a different basis
entirely — the Health/Consumer Wellbeing register — so this set was
overtaken rather than rejected on its merits. **Appendix A is the shipped
truth.** Kept here because R49–R54's reasoning is still worth reading: it
is where the accent objective was inverted (harmony maximised,
distinguishability made a floor), and where R53 first recorded that
coherence and distinguishability are in direct opposition in this app.

⚠️ **Name collision, read before comparing.** These tables carry a role
called `attention`, and it is **not** the `attention` in Appendix A. Here it
is a per-palette chromatic accent (Golden Hour's is `#63883A`, a green).
There it is a semantic role meaning *a missed dose, low stock, an
approaching expiry* — warm clay, deliberately never red. Same word, two
different jobs, and only the Appendix A one exists in `Color.kt`.

Ten roles per palette, light and dark, rebuilt from `design-lexicon.md` through the
optimizer portfolio. **Candidates, not shipped tokens** — at the time of writing
`Color.kt` still carried the then-current Appendix A set, and R54 gave the condition
that gated replacing it. They are inlined
here rather than kept in a `docs/palettes/` file for the reason Appendix A states about
itself: a second document holding hex values is how the last one went stale.

Method, in one line: `dk spec palette --mood <entry mood> --template <T> --mode <m>
--device mobile`, role envelopes retuned for the eye-comfort brief, `primary.hue`
anchored to the entry's measured hue band, run through the DE/CHA/CMA portfolio, best
of the coherent templates only. 183 of 184 verification checks pass; the exception is
Rose Petal dark's `attention`/`tertiary` pair at ΔE 9.0 (R53).

**The eight hex tables that stood here have been removed.** They were the
full ten-role light/dark values for candidate palettes that were overtaken
before they shipped, so nothing reads them and nothing can: `Color.kt`
carries the six of Appendix A. What was worth keeping from R49–R54 is the
*method* and the two findings it produced, both stated above and in §7 —
the hex values were the one part that could only ever go stale. They are in
git history if a specific value is ever wanted.

Method, for the record: `dk spec palette --mood <entry mood> --template <T>
--mode <m> --device mobile`, role envelopes retuned for the eye-comfort
brief, `primary.hue` anchored to the entry's measured hue band, run through
the DE/CHA/CMA portfolio, best of the coherent templates only. 183 of 184
verification checks passed; the exception was Rose Petal dark's
`attention`/`tertiary` pair at ΔE 9.0 (R53).

---

## Appendix E — medTimer code-level diff (§7 R60)

medTimer v1.24.6 (MIT; source as unpacked 2026-07-19 at
`Downloads/medTimer-main/`) against Emivita at commit `93bcd41` (2026-09-13).
Method and its selection are in R60. Six areas, one Opus 5 subagent each,
every claim carrying `file:line` on both sides or the word ABSENT with the
grep terms tried; 17 claims re-read against source by the main session, 0
wrong. Column meanings: `relation` ∈ absent-in-emivita · absent-in-medtimer
· parity · divergent · excluded-by-design; `verdict` ∈ import-as-is ·
inspiration · emivita-better · medtimer-better · equal · n/a; `cost` S (< 1 h,
one file) · M (half a day, maybe a migration) · L (a feature).

**Tally — 285 rows:** 92 inspiration · 73 equal · 51 Emivita-better · 42
medTimer-better · 23 n/a · 4 import-as-is. Relations: 89 divergent · 80
absent-in-emivita · 44 parity · 13 absent-in-medtimer · 10 excluded-by-design.

### E.1 Import-as-is candidates

Copyable with import/package edits only. Each needs the MIT copyright notice
to travel with it (`THIRD_PARTY_NOTICES.md`, created at the first copy — not
before, R60).

| row | what | medTimer source | Emivita destination | cost |
|---|---|---|---|---|
| B-06 | `shouldBeSilent()` — suppress the chime when < 10 s since the last notification | `feature/reminders/.../notificationFactory/NotificationFactory.kt:85-95` | `core/notification/` beside `postIfPermitted`; `builder.setSilent(shouldBeSilent())` | S |
| B-39 | `getMedicineName(hide)` — first letter + `*` when names are hidden | `core/common/.../helpers/MedicineHelper.kt:22-32` | `core/format/DoseFormatting.kt` as `maskedMedicineName(name, hide)`; §9 #11's "hide names" | S |
| C-20 | `createCalendarEventIntent(title, date)` — add the run-out date to the calendar | `core/common/.../helpers/CalendarHelper.kt:1-19` | new `core/util/CalendarIntents.kt`; button beside the run-out line in `StockExpirationScreen` | S |
| D-26 | `ChartSeriesColors` — custom medicine colour wins, else palette | `feature/ui/.../statistics/charts/ChartSeriesColors.kt:12-40` | `feature/history/AdherenceChart.kt` if per-medicine series are ever drawn | S |

### E.2 Parity tests — 30 medTimer cases against Emivita's engine

`app/src/test/java/com/hazia/emivita/core/scheduling/medtimer/` — run first
against the unfixed code. **21 parity** (same answer for the same history).
**4 documented divergences**, asserted as Emivita's behaviour with the row
that decided them: cyclic pattern before its anchor (A-09, Emivita periodic,
medTimer nothing); windowed reminder created inside today's window (A-06,
Emivita fires the remaining slots); overnight daily window (A-20, Emivita
returns null — medTimer better, cost M, owner's call); grandchild linked
reminder (A-23, one level per event). **4 failures, all defects, all fixed**
(R60): outstanding ON_RESOLVED interval and window re-armed (two tests), a
late alarm replaying missed slots, a ONCE expiration alert that never
re-armed. Plus one regression guard: an outstanding dose does not blank the
projection. Not ported (deselected, budget-bound): `SchedulingSimulatorTest`'s
eleven cases against `OccurrenceProjectionCache`; the seed list per method is
at the end of E.5.A.

### E.3 Verified defects

Fixed in this pass: the three scheduling defects above; the eight gate-blind
literals (Stage A part 4). Open, all verified against source, all S unless
marked, listed in R60 and in each area's *Findings* below: B F-1..F-5,
C F-1..F-7, D F1..F5, E F1..F8, F 1..3.

### E.4 Ranked adoption list (for the owner — §9 #21)

Ranked by bearing on §1's purpose (an adherence record that reaches a
clinician, and reminders that arrive) over cost. Nothing here is adopted
until picked.

1. **The verified defects, as one batch (all S).** Content intents on
   every notification (B F-1); `rescheduleAll()` on app launch (B F-3); live
   zone + `TIMEZONE_CHANGED`/`TIME_SET` receiver (B F-4, B-22); per-event
   snooze/nag slots (B F-2, M); amount-before-resolve in the dose dialog
   (C F-1); refill gated on a saved unit (C F-2); lenient backup `Json` with
   `schemaVersion` written and checked (E F1); profile edits sync (E F2);
   export null-stream message (E F3); table sort by displayed value (D F4);
   CSV with two full timestamps (E F8).
2. **Reminder management (S–M).** Pause/enable and delete a dose reminder
   from its edit screen (C F-7/D F1 — today the only path is deleting the
   medicine); the `lockRemindersUntilTakenByDefault` settings row (D F2);
   duplicate medicine with/without reminders (C-28, planned in §6);
   activate/deactivate all (C-29); sort menu (C-27); drag-reorder (C-26,
   D-01/02, M — hand-rolled `LazyColumn` drag, `reorderable` is not cached).
3. **Stage C prerequisites (S each).** Locale-aware time patterns on Today,
   History, the widget and the CSV (D F3, E F7); locale-aware
   `formatDecimal` (F 2); decimal-comma input (E F6); `generateLocaleConfig`
   + `localeFilters` (F-31).
4. **Notification polish.** `shouldBeSilent` (B-06, as-is); hidden medicine
   names (B-39, as-is, §9 #11); swipe-to-dismiss records a status (B-11, S,
   which status is a product call); boot re-raise of unanswered notifications
   (B-20, M); alarm ringtone picker and silent-mode switches (B-28/B-30,
   M/S); combine same-minute notifications (B-05, M); custom snooze (B-14,
   M); per-reminder importance (B-38, M); DND override (B-25/26, M — needs
   `ACCESS_NOTIFICATION_POLICY`, a §1 posture decision).
5. **History as a record (M).** Reschedule a pending dose to a chosen time
   (C-10); re-raise (C-11); soft-delete a mistaken event (C-12 — today a
   wrong PRN entry cannot be removed and lowers adherence); event notes
   (C-36); per-reminder instructions shown in the notification (C-35);
   history kept when a medicine is deleted (C-41 — the cascade contradicts
   the export-to-clinician goal); variable amount at take time (C-13);
   backdated PRN doses (C-15, S).
6. **Today and History reach (M–L).** Day navigation and status filters
   (D-15..17); history range selector 1..30 days (D-24, S); per-medicine
   calendar with simulated future doses (D-31..34); table text filter
   (D-28/29); persisted view mode (D F5, S); stock/expiry indicators on the
   medicine card (C-22/23, S); calendar entry for the run-out date (C-20,
   as-is).
7. **Engine edges (M).** Overnight daily windows (A-20); linked children in
   the projection (A-24); per-reminder compute budget check (A-41, S);
   progressive projection emission (A-45, S); `SchedulingSimulatorTest` port.
8. **Quality gates (S–L).** Backup entity-coverage guard test (E-16, S);
   backup fuzz test (E-17, S); JVM tests for the receivers and
   `AlarmScheduler` slot arithmetic (B-47, F 3, M); CodeQL / Scorecard /
   dependency-review workflows (F-19..21, S each, network); emulator CI job
   (F-18, L — arm-only ABIs); layout-bounds Compose tests (F-14, S).
9. **Excluded, and why it stays excluded.** Location snooze and GMS (§5,
   §1); intro/onboarding (§9 #1 reversal, D-49); Health Connect (#13);
   adaptive rail/tablet layouts (D-35/36, L, artifacts not cached); Hilt
   (§2); XML `RemoteViews` big notifications (B-41).

### E.5 The matrix, by area

### E.5.A — Scheduling and simulation

#### Area A — scheduling + simulation

Path shorthand: medTimer `S/` = `feature/reminders/api/src/main/java/com/futsch1/medtimer/feature/reminders/api/scheduling/`; medTimer `T/` = `app/src/test/java/com/futsch1/medtimer/schedulertests/`; Emivita paths are under `app/src/main/java/com/hazia/emivita/` (`RS` = `core/scheduling/ReminderScheduling.kt`, `SE` = `core/scheduling/SchedulingEngine.kt`, `OPC` = `core/scheduling/OccurrenceProjectionCache.kt`); Emivita tests `ET/` = `app/src/test/java/com/hazia/emivita/core/scheduling/`.

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| A-01 | Reminder-type encoding | `core/domain/.../model/Reminder.kt:36-44` (`reminderType` derived from sentinel fields: `linkedReminderId!=0`, `intervalStart!=EPOCH`, `windowedInterval`, `outOfStockReminderType!=OFF`…) | `core/data/config/ReminderConfig.kt:12` (`enum ReminderType`), `:65-139` (sealed `ReminderConfig`), `core/data/entity/Reminder.kt:50-51` (`type` + `configJson`) | divergent | emivita-better | medTimer needs 27 columns with EPOCH/0 sentinels and a priority-ordered `when` to know what a row is; Emivita stores the discriminator (`type`) plus one typed JSON blob, so `SchedulingEngine.computeBase` `SE:188` is an exhaustive `when` over a sealed type. | - | none |
| A-02 | Per-type strategy dispatch | `S/SchedulingFactory.kt:18-47` (`when(reminder.reminderType)` → 6 `Scheduling` classes, REFILL errors) | `SE:187-299` (`computeBase` `when(decodeReminderConfig(...))`) | parity | equal | Both dispatch on type to one calculator each; medTimer wraps the result in `WeekendModeSchedulingDecorator` at `:47`, Emivita applies weekend last at `SE:173-177`. | - | none |
| A-03 | "Next alarm" aggregation | `S/ReminderScheduler.kt:10-31` (`schedule()` returns every active reminder's next instant sorted by timestamp `:28`; `ScheduleNextReminderNotificationProcessor.kt:34-58` arms only the earliest in slot 0) | `core/scheduling/RealSchedulingCoordinator.kt:69-83` (`rescheduleAll` arms one alarm per reminder via `scheduleOrCancel` `:96-107`) | divergent | n/a | medTimer computes a global sorted list and re-runs the whole scheduler after each fire; Emivita never needs the list because each reminder owns an AlarmManager slot (`AlarmScheduler.schedule` `:63-71`). Architectural, not a gap. | - | none |
| A-04 | Inactive/disabled reminder skipped | `S/ReminderScheduler.kt:15` (`if (!reminder.active) continue`) | `SE:110` (`if (!reminder.enabled) return null`) | parity | equal | Same gate at the same layer; Emivita test `ET/SchedulingEngineTest.kt:177`. | - | none |
| A-05 | Daily time-based next slot | `S/StandardScheduling.kt:55-59` (`canScheduleEveryDay`: today allowed only if created before slot and not raised today), `:111-118` (`localDateToReminderInstant`) | `RS:100-115` (`nextFixedTimeOccurrence`: slot on `matchingDateOnOrAfter` day, roll forward once if `candidate.isBefore(notBefore)` `:108-113`) | parity | equal | medTimer may return today's already-passed slot (possibleDays[0]) which `AlarmProcessor.setNextReminderAlarm` `feature/reminders/.../AlarmProcessor.kt:38-44` raises immediately as a catch-up; Emivita's `notBefore` floor `RS:108` rolls a passed slot to tomorrow and never materializes it (no "missed" row for a slot the phone was off for). | - | none |
| A-06 | `createdTime` guard | `S/StandardScheduling.kt:107-109` (`reminderBeforeCreation()` blocks today's slot if created after it); `S/WindowedIntervalScheduling.kt:53-61` (`getStartInstant`: if today's window start ≤ createdTime → tomorrow's window start) | FixedTime: no createdAt read (`Reminder.createdAtEpochMillis` used only at `SE:197` as ON_RESOLVED fallback); DailyWindow: `RS:179-197` grid from `windowStart` stepped to `notBefore` | divergent | emivita-better | For FixedTime the `notBefore=now` floor gives the same outcome. For a windowed reminder created at 10:00 with window 08–20/4 h: medTimer waits until tomorrow 08:00 (`:56-60`), Emivita fires today at 12:00 (`RS:190-197`). | - | none |
| A-07 | "already raised today/tomorrow" guard | `S/StandardScheduling.kt:16-17,50-52,57-58` (`raisedToday`/`raisedTomorrow` via `isRaisedOn` `S/Scheduling.kt:46-53`) | ABSENT in engine (searched: `isRaisedOn`, `raisedToday`, `PENDING` in `SE`); dedup is `DoseEventRepository.materializeIfAbsent` `core/data/repository/DoseEventRepository.kt:59-72` + re-show branch `ReminderAlarmReceiver.kt:116-122` | divergent | equal | medTimer skips a whole day that already has an event (any time); Emivita only dedups the exact `(reminderId, scheduledEpochMillis)` slot and re-posts only if still PENDING `:118`. Same result for normal flow; differs only when a user edits an event onto a different time of a future day. | - | none |
| A-08 | Weekday selection | `S/StandardScheduling.kt:61-72` (`clearPossibleDaysByWeekday`; empty `days` = every day `:62-64`) | `RS:52-60` (`SpecificDaysOfWeek`, scans 7 days; empty set → null `:53-55`) | parity | equal | Same Mon=1..Sun=7 mapping (`RS:58`); Emivita's empty-set→null is deliberate (§7 R23 "degrade, don't throw") whereas medTimer treats empty as unrestricted. | - | none |
| A-09 | Cyclic active/pause semantics before cycle start | `S/StandardScheduling.kt:40-53` (`abs(dayInCycle % cycleLength) < consecutiveDays && dayInCycle >= 0` `:46`) | `RS:73-93` + `core/data/config/ReminderConfig.kt:55-59` (`isActiveOn` floorMod, periodic both directions) | divergent | n/a | medTimer's `dayInCycle >= 0` (`:46`) makes days before `cycleStartDay` impossible (pinned by `T/ReminderSchedulerUnitTest.kt:265-301`); Emivita's `ReminderConfig.kt:39-41` comment claims bidirectional periodicity "matching medTimer's own documented behavior" but medTimer's code does not do that (pinned opposite at `ET/ReminderSchedulingTest.kt:174`). Owner's pick; the comment is wrong either way. | S | none |
| A-10 | Combining weekday filter with a cycle | `S/StandardScheduling.kt:24-32` (cycle, weekday, period, day-of-month applied as successive masks; test `T/ReminderSchedulerDaysUnitTest.kt:41-62` Mondays ∧ every 6 days) | ABSENT (searched: `RepeatPattern` is one-of `ReminderConfig.kt:31-48`; `matchingDateOnOrAfter` `RS:50` takes a single pattern) | absent-in-emivita | inspiration | medTimer AND-combines four day masks over a 31-day bitmap; Emivita's `RepeatPattern` is a sealed single choice, so "Mon/Wed/Fri but only in active cycle weeks" cannot be expressed. | M | none |
| A-11 | Every-N-days | expressed as `consecutiveDays=1, pauseDays=N-1` (`T/TestHelper.kt:50`), cycle anchored at `cycleStartDay` `S/StandardScheduling.kt:41` | `RS:61-72` (`EveryNDays`: before anchor → anchor `:65-66`; else snap to grid `:68-70`) | parity | equal | Same arithmetic; Emivita has a dedicated pattern so the UI doesn't have to derive pause days. | - | none |
| A-12 | Cycle counter "(n/m)" string | `S/CyclesHelper.kt:9-20` (`getCycleCountString`, `dayInCycle % cycleLength + 1`, uses `LocalDate.now()`) | ABSENT (searched: `cycleCount`, `dayWithinCycle`, `isActiveOn` callers outside `RS`/`ReminderConfig`) | absent-in-emivita | inspiration | 12 lines of pure math but keyed on medTimer's `pauseDays/consecutiveDays/cycleStartDay` fields and `@SuppressLint`; would be rewritten against `RepeatPattern.CyclicActivePause` with an injected date. | S | none |
| A-13 | Days-of-month selection | `S/StandardScheduling.kt:86-95` (`clearPossibleDaysByActiveDayOfMonth`, `activeDaysOfMonth: List<Int>`; test `T/ReminderSchedulerActiveDayOfMonthUnitTest.kt:17-49`, simulator `T/SchedulingSimulatorTest.kt:477-500`) | ABSENT (searched: `dayOfMonth`, `DayOfMonth`, `daysOfMonth` — only `HistoryCalendarView.kt:236` cell label) | absent-in-emivita | inspiration | No `RepeatPattern` case for "1st and 15th"; would be a 5th sealed case (JSON, no migration) + form section + calculator branch. medTimer's version is also horizon-limited (see A-15). | M | none |
| A-14 | Active period (start/end dates) | `S/StandardScheduling.kt:74-84` (`periodStart/periodEnd`, `LocalDate.EPOCH` = unbounded); `S/IntervalScheduling.kt:59-75` (`adjustToPeriod`: interval before start → same time-of-day on `periodStart` `:64-66`; after end → null `:69-71`) | `core/data/entity/Reminder.kt:63-64` (`activeFromEpochDay/activeUntilEpochDay`, null = unbounded); `SE:140-154` (defer by re-running `computeBase` from `opensAt` `:142-151`; end → null `:154`) | parity | equal | Same inclusive-day semantics; Emivita's activeFrom re-run applies to every type (a RepeatingInterval lands on its first grid slot ≥ midnight of activeFrom), medTimer keeps the interval's time-of-day. medTimer tests interval/windowed clamps (`T/ReminderSchedulerActivePeriodUnitTest.kt:61-127`), Emivita only FixedTime (`ET/SchedulingEngineTest.kt:302-357`). | - | none |
| A-15 | Look-ahead horizon (Q7) | `S/Scheduling.kt:69-77` (`getNextNotRemindedDay` loops `start..31`), `S/StandardScheduling.kt:20` (`BooleanArray(31)`) → null if no possible day within 31 days | `RS:56` (7-day scan), `:68-70` (arithmetic), `:90-92` (scans one full `cycleLength`), `:229-236` (arithmetic), `:166,362` (DailyWindow 3-day lookahead) | divergent | emivita-better | medTimer returns null for a 1-active/40-pause cycle during its pause, or for day-31 on Feb 1 (next real date 58 days out), and only re-evaluates when *some* alarm fires; Emivita's cycle scan is bounded by `cycleLength` not a constant, so a long cycle always yields a date and gets its own exact alarm. | - | none |
| A-16 | Continuous interval basic chain | `S/IntervalScheduling.kt:20-40` (no event → `intervalStart`; else last event's `remindedTimestamp` or `processedTimestamp` + `time.seconds`) | `RS:133-162` (`nextRepeatingIntervalOccurrence`: fixed grid from anchor, `stepsElapsed` ceil `:158-160`); `SE:192-200` (ON_REMINDED anchor = `config.anchorEpochMillis`, ON_RESOLVED = `getLastResolved().resolvedAtEpochMillis ?: createdAt`) | divergent | emivita-better | medTimer chains off the actual event instant, so a 5-min-late notification shifts every later slot; Emivita ON_REMINDED stays on the anchored grid (`ET/ReminderSchedulingTest.kt:244`). ON_RESOLVED matches medTimer's "from processed" for the first step. medTimer must reset `intervalStart` on re-activation (`core/common/.../helpers/ReminderHelper.kt:30`); a grid anchor needs no reset. | - | none |
| A-17 | Phone off for days: burst vs resume (Q1) | `S/IntervalScheduling.kt:42-57` (`adjustToToday`: if next < start of today, jump to first interval *today*, `ceil(deltaMinutes/interval)` `:51-53`) + `AlarmProcessor.kt:38-44` raises any past instant immediately and loops → burst of all of *today's* missed intervals only; Standard: one catch-up (A-05); Windowed: `S/WindowedIntervalScheduling.kt:80-81` restarts at today's window start | Boot path `BootRescheduleReceiver.kt:22-23,36` → `rescheduleAll()` with `now` `RealSchedulingCoordinator.kt:70,80` → `RS:158-160` grid instant ≥ now: **zero** catch-up for every type; late-delivery path `ReminderAlarmReceiver.kt:145` reschedules from `scheduledEpochMillis + 60_000L` with no `now` floor and `AlarmScheduler.kt:133/136` arms past instants → replays every missed slot across days | divergent | emivita-better | On reboot Emivita resumes cleanly (no burst) where medTimer fires today's missed intervals one after another; but if an alarm is delivered late or the clock jumps forward, Emivita replays *all* missed FixedTime/ON_REMINDED slots as immediate alarms (see Finding 1), which medTimer's `raisedToday` guard and today-only `adjustToToday` prevent. | S | none |
| A-18 | "interval starts from processed" blocks while a dose is unanswered | `S/IntervalScheduling.kt:32-35` and `S/WindowedIntervalScheduling.kt:67-70` (`processedTimestamp == EPOCH → null`; test `T/ReminderSchedulerIntervalUnitTest.kt:116-153`) | Fire path: `RS:337-343` `reschedulesImmediatelyOnFire()` false for ON_RESOLVED; but `SE:192-208` has no PENDING check and `DoseEventDao.kt:66-70` `getLastResolved` skips PENDING → anchors on the *previous* resolution | divergent | medtimer-better | `rescheduleAll()` (`RealSchedulingCoordinator.kt:79-82`) or any reminder edit (`:47-48`) arms a fresh alarm at `prevResolved + k·interval ≥ now` while the current dose is still PENDING, materializing a second PENDING dose; medTimer returns null until the outstanding event is processed. Finding 2. | S | none |
| A-19 | Windowed / daily-window interval | `S/WindowedIntervalScheduling.kt:16-105` (`Interval` start/end instants `:20-41`; chain continues within window `:85-94`, else next day's start `:97-98`) | `RS:349-369` + `windowSlotOnDay` `:168-198` (grid from `windowStart` or `lastResolved+interval` if inside window `:179-188`; step to `notBefore` `:190-196`; inclusive end `:197`) | parity | equal | Same slots for on-time events (medTimer `T/ReminderSchedulerWindowedIntervalUnitTest.kt:17-86` vs `ET/ReminderSchedulingTest.kt:297-338`); Emivita restarts the grid at `windowStart` each day rather than continuing from the last event, which is what medTimer's `interval.startInstant` branches `:89-91` also do when the last event was in the previous window. | - | none |
| A-20 | Overnight window (end < start) | `S/WindowedIntervalScheduling.kt:26-30` (`endInstant = calcInstant(end).plus(1, DAYS)`; tests `T/ReminderSchedulerWindowedIntervalUnitTest.kt:145-212`, `T/SchedulingSimulatorTest.kt:251-308`) | `RS:177` (`if (windowEnd.isBefore(windowStart)) return null`; pinned at `ET/ReminderSchedulingTest.kt:341`) | divergent | medtimer-better | A 16:40→03:20 window is a supported configuration in medTimer and a "misconfigured, skip this day" in Emivita. Porting is the `+1 day` on `windowEnd` plus an `isInInterval`-style check against yesterday's window for the after-midnight side. | M | none |
| A-21 | Long pause restart for windowed | `S/WindowedIntervalScheduling.kt:79-81` (`instant < todayStart − 1 day → interval.startInstant`) | `RS:190-196` (`notBefore` stepping) + 3-day lookahead `:362` | parity | equal | Both restart at the current day's window start after a gap; Emivita gets it for free from the `notBefore` floor. | - | none |
| A-22 | Linked ("following") reminder anchor + guard (Q3) | `S/LinkedScheduling.kt:13-28` (anchor = source event's `processedTimestamp` only; fires only if `source.processed > child.lastReminded` `:20-21`) | `SE:211-269` (ON_REMINDED anchor = parent's latest `scheduledEpochMillis` via `DoseEventDao.kt:79-80`; ON_RESOLVED = parent's `resolvedAtEpochMillis`; `.takeIf { it >= notBefore }` `:267`) | divergent | emivita-better | Emivita adds an ON_REMINDED child mode medTimer lacks and replaces the "once per source event" comparison with the `notBefore` floor + unique index; medTimer's guard `:20-21` is what prevents a child re-firing after its own event, which Emivita gets from `takeIf` (`ET/SchedulingEngineTest.kt:493`). | - | none |
| A-23 | Linked: max delay & chain depth (Q3) | Delay entered via a 24 h clock picker `feature/ui/.../medicine/LinkedReminderHandling.kt:58-60` → `ReminderTime(minutes, isDuration=true)` `core/domain/.../ReminderTime.kt:5` (0–1439 min); chain depth unbounded (`T/ReminderSchedulerLinkedUnitTest.kt:94-154` two hops; `LinkedReminderAlgorithms.kt:14-31` sums recursively) | `ReminderConfig.kt:136` (`delayMinutes // 1..1440`), gate `feature/reminders/AddReminderScreen.kt:1091`; depth unbounded (`:1034-1036` parent picker includes linked siblings); cascade one hop per fire `ReminderAlarmReceiver.kt:288-300` / `RealSchedulingCoordinator.kt:38-44` | parity | equal | Both cap at ~24 h (medTimer 23:59 max, Emivita 1440 inclusive) and allow arbitrary chains; Emivita's cascade is "one level per event" so hop N is armed only when hop N-1 fires/resolves. | - | none |
| A-24 | Linked reminders in the forward projection | `S/SchedulingSimulator.kt:102-122,145-159` (child scheduled against the *synthetic* parent event added at `:157`; `T/SchedulingSimulatorTest.kt:311-351` shows parent+child every day with stock) | `SE:325-332` (`occurrencesFor` breaks after one LinkedFollowing occurrence, anchored to the parent's *real* last DB event) → `OPC:228-244` stock walk sees at most one child dose | divergent | medtimer-better | A 08:00 parent + 30-min child projects 2 doses/day in medTimer; in Emivita the child contributes 0 or 1 dose to the 28-day window, so `stockRunOutDates` overstates days-remaining for any medicine with a following dose (pinned as intended at `ET/SchedulingEngineTest.kt:624`). | M | none |
| A-25 | Out-of-stock DAILY scheduling | `S/OutOfStockScheduling.kt:15-20` (`type == DAILY && amount <= threshold → getNextNotRemindedDay()`) | `RS:260-272` (`nextLowStockAlertOccurrence`: DAILY only `:267`, `stockQuantity <= threshold` `:268`, `lastNotifiedEpochDay+1` `:270`) | parity | equal | Same rule; Emivita replaces the 31-day event scan with the `lastAlertNotifiedEpochDay` column (`Reminder.kt:100`), documented at `RS:207-214`. ONCE/ALWAYS are event-driven on both sides. | - | none |
| A-26 | Expiration ONCE | `S/ExpirationDateScheduling.kt:31-41` (`scheduleOnce`: fire today if `firstRemindedDay <= today` and no event raised in `firstRemindedDay..today` `:33-36`) | `RS:301-304` (`if (lastNotifiedEpochDay != null) return null`) | divergent | medtimer-better | medTimer's window is relative to the *current* `expirationDate`, so entering a new pack's date re-arms it; Emivita's flag is never cleared (`LowStockExpirationAlertEvaluator.kt:84-88` resets only low stock; searched `updateLastAlertNotifiedEpochDay` callers), so a ONCE expiry alert is dead after its first firing forever. Finding 3. | S | none |
| A-27 | Expiration DAILY | `S/ExpirationDateScheduling.kt:24-25` (`max(firstRemindedDay, today)` then not-reminded-day scan) | `RS:305-308` (`maxOf(opensOn, lastNotified+1)`) | parity | equal | Same semantics incl. continuing past the expiry date; Emivita has no 31-day cap (A-15). Pinned `ET/SchedulingEngineTest.kt:247,258`. | - | none |
| A-28 | Alert time-of-day | `reminder.time` via `S/Scheduling.kt:63-67` (`localDateToReminderInstant`) | `ReminderConfig.kt:111-112,120-121` (`hour/minute`, default 09:00 `:25-26`) → `RS:220-243` (`nextAlertSlot`, roll once `:231-237`) | parity | equal | Ported per §7 R37 (`RS:202-206`); Emivita defaults the time so old JSON rows decode. | - | none |
| A-29 | Weekend-mode adjust maths (Q4) | `S/WeekendModeSchedulingDecorator.kt:15-30` (`currentSeconds in startSeconds until endSeconds` `:24` → `instant.plusSeconds(end − current)` `:25`; `weekendDays: Set<String>` of `dayOfWeek.value`) | `RS:407-442` (`applyWeekendMode`: `[start, end)` `:426`; push = `pushDate.atTime(weekendEnd).atZone(zone)` `:440`; overnight windows `:428-436`; `weekendDays: Set<Int>`) | divergent | emivita-better | medTimer's `until` range is empty when start > end so an overnight window never delays; Emivita handles it and picks the governing day (`ET/ReminderSchedulingTest.kt:392-424`). medTimer adds elapsed seconds (on a DST-change weekend the push lands at end±1 h wall-clock); Emivita pushes to the wall-clock end. | - | none |
| A-30 | Weekend-mode gate | `S/SchedulingFactory.kt:47` (every scheduler wrapped, no per-reminder switch) | `SE:173-177` (`reminder.weekendModeOptIn` checked before the global toggle; `Reminder.kt:93`) | excluded-by-design | n/a | EXCLUDED §5 row "WeekendModeSchedulingDecorator" / §7 R36: both switches must be on; pinned `ET/SchedulingEngineTest.kt:381,397,409`. | - | none |
| A-31 | Weekend-prefs read cost | `S/WeekendModeSchedulingDecorator.kt:36` (`dataSource.preferences.value`, synchronous StateFlow) | `SE:78-85` (`readWeekendMode()` suspend, read once per walk `SE:318`, `OPC:194`) | parity | equal | Equivalent; Emivita threads a snapshot through `occurrencesFor` because DataStore reads are cold flows (`ET/SchedulingEngineTest.kt:638`). | - | none |
| A-32 | Ordering: calculator → period clamp → weekend | period inside strategy (`S/StandardScheduling.kt:31`, `S/IntervalScheduling.kt:17`), weekend outermost (`S/SchedulingFactory.kt:47`) | `SE:113-177` (compute → activeFrom defer → activeUntil → weekend) | parity | equal | Identical order; Emivita pins the consequence that a weekend push can land past `activeUntil` (`ET/SchedulingEngineTest.kt:359`). | - | none |
| A-33 | Local time → instant / DST (Q6) | `S/Scheduling.kt:63-67` (`atTime().atZone(zone).toInstant()`); `S/IntervalScheduling.kt:47-48,64-66`, `S/WindowedIntervalScheduling.kt:37-40` (`ldt.toInstant(rules.getOffset(ldt))`); `core/common/.../helpers/TimeHelper.kt:80-82,94-110` (`isOnDay` day-bounds cache per zone) | `RS:105-107,175-176,230,415,440`, `SE:142,180-181` (all `atZone(zone)`) | parity | equal | Both resolve a spring-forward gap forward (`getOffset(LocalDateTime)` returns the pre-transition offset, same instant as `ZonedDateTime`); Emivita pins the gap (`ET/ReminderSchedulingTest.kt:221`) and the 24 h-interval DST shift (`:271`), medTimer pins three zones without DST (`T/ReminderSchedulerTimezoneUnitTest.kt:13-34`). | - | none |
| A-34 | Reschedule on TIME_SET / TIMEZONE_CHANGED | `app/src/main/java/com/futsch1/medtimer/TimeChangeReceiver.kt:26-30` (both actions → `commandBus.scheduleNextNotification()`) | ABSENT (`BootRescheduleReceiver.kt:22-23` handles only `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`; searched manifest for `TIMEZONE`, `TIME_SET`) | absent-in-emivita | inspiration | After a zone change every armed RTC alarm keeps its old instant, so an 08:00 reminder fires at the wrong wall-clock hour until the next fire/edit; medTimer re-bases immediately. Receiver is Hilt-injected, so rewrite against `AppContainer`. | S | none |
| A-35 | Clock/zone abstraction for tests | `core/common/src/main/java/com/futsch1/medtimer/core/common/time/TimeAccess.kt:7-13` + `SystemTimeAccess.kt:10-14` | `SE:38-39` (`zone`, `nowEpochMillis` constructor params), `OPC:112-113` | parity | equal | Same testability; medTimer's simulator swaps `localDate()` per simulated day (`S/SchedulingSimulator.kt:83-87`), Emivita passes `notBefore` instead. | - | none |
| A-36 | Event source fed to the scheduler | `core/database/.../ReminderEventRepositoryImpl.kt:44-58` (`getForScheduling`: last 2 events per active reminder; if all are in the future, all events) | `DoseEventDao.kt:66-70` (`getLastResolved`, non-PENDING by `resolvedAt DESC`), `:79-80` (`getMostRecentForReminder` by `scheduledEpochMillis DESC`) queried per call `SE:196,204,237-238` | divergent | equal | medTimer pre-fetches one list for the whole pass; Emivita issues one query per reminder per call (up to `maxOccurrences` per walk `SE:321-336`). medTimer's "all-future" fallback handles user-edited future events; Emivita's FixedTime ignores events entirely. | - | none |
| A-37 | Forward simulation core (Q5) | `S/SchedulingSimulator.kt:93-122` (day loop to 400 days `:66`, per medicine earliest-first, synthetic TAKEN event per hit `:177-187`, `ensureActive` `:98`) | `SE:309-338` (`occurrencesFor`: per reminder `+60 s` walk, `maxOccurrences=500`) + `OPC:200-245` | divergent | equal | medTimer models cross-reminder interaction (linked chains, "from processed" chains) by feeding synthetic events back in; Emivita walks each reminder independently (cheaper, but see A-24). | - | none |
| A-38 | Future-dated real events in the simulation | `S/SchedulingSimulator.kt:21-58` (`LastEventsPerReminder.advanceTo` only admits events once their day is reached `:28-36`; unprocessed treated as processed `:47-53`; test `T/SchedulingSimulatorTest.kt:440-474`) | ABSENT (projection never reads events for FixedTime; searched `OPC` for `doseEvent`) | absent-in-emivita | inspiration | A dose the user logged for tomorrow appears in medTimer's simulation instead of a second projected slot; in Emivita both the logged event and the projected occurrence exist (merge behaviour lives in Today, area C). | S | none |
| A-39 | Window request/release semantics | `feature/reminders/.../SimulatedRemindersRepository.kt:77-92` (`effectiveWindowDays = maxOrNull() ?: DEFAULT_SIMULATION_DAYS` `:91-92`; `DEFAULT=28` `SimulatedReminders.kt:27`; release does not re-simulate `:86-89`) | `OPC:160-183` (`maxOf(max request, MINIMUM_WINDOW_DAYS=28)` `:182-183`) | divergent | emivita-better | medTimer's default is a fallback: one consumer requesting 2 days would shrink everyone's window; Emivita's is a floor (§7 R28, `ET/OccurrenceProjectionCacheTest.kt:137`). | - | none |
| A-40 | Debounce | `SimulatedRemindersRepository.kt:46,58` (conflated channel + `delay(1000 ms)`) | `OPC:23,120,128` (`DEBOUNCE_MILLIS=1_000`, conflated) | parity | equal | Identical mechanism and constant. | - | none |
| A-41 | Compute budget | `SimulatedRemindersRepository.kt:128` (`< 2_000 ms` checked per simulated reminder in the consumer lambda) | `OPC:24,206-214` (`COMPUTE_BUDGET_MILLIS=2_000` checked once per *medicine*) | divergent | medtimer-better | A single medicine with many reminders can overrun Emivita's budget by up to `reminders × 500` walk steps before the check runs; medTimer checks after every produced reminder. One-line move of the check into the reminder loop. | S | none |
| A-42 | Truncation signalling | `SimulatedRemindersRepository.kt:65` (`_simulatedThrough.value = endDay` set unconditionally after `runSimulation`, even when the cap at `:128` stopped early) | `OPC:58,199-213` (`ProjectionState.truncated` set only on budget stop; §7 R58) | divergent | emivita-better | medTimer overstates coverage after a cut; Emivita tells consumers that an absence in `stockRunOutDates` may mean "not reached". | - | none |
| A-43 | Stock run-out map semantics | `SimulatedRemindersRepository.kt:107,124-126` (`LocalDate.MAX` = tracked but not within window, `null` = tracking off, date when `stockAfter == 0.0`) | `OPC:235-244` (entry only when `remaining <= 0.0`; absent = untracked or beyond window or truncated) | divergent | equal | medTimer encodes three states in the value; Emivita encodes one and expects consumers to check `unit != null` and `truncated`. | - | none |
| A-44 | Simulated stock decrement | `S/SchedulingSimulator.kt:161-175` (`MedicineHelper.parseAmount(reminder.amount)`, no decrement once stock is 0, `coerceAtLeast(0.0)`) | `OPC:236-243` (numeric `doseAmount`, walk breaks at first `<= 0`) | parity | equal | Emivita's `NextOccurrence.doseAmount` (`core/scheduling/NextOccurrence.kt:15-18`) removes medTimer's string-parsing step. | - | none |
| A-45 | Progressive emission | `SimulatedRemindersRepository.kt:117-122` (`_simulatedReminders.value = result` at each day boundary, final sorted emit `:136`) | `OPC:247-253` (single emit at end of pass) | divergent | medtimer-better | Long windows show partial results in medTimer while the pass runs; Emivita consumers wait up to 2 s. Minor. | S | none |
| A-46 | Alerts inside the projection | `S/SchedulingSimulator.kt:129-136` (factory includes OUT_OF_STOCK/EXPIRATION; `T/SchedulingSimulatorTest.kt:405-437` asserts a simulated out-of-stock entry) | `OPC:227` (`if (reminder.type !in doseProducingTypes) continue`; `SE:44-49`) | divergent | equal | Deliberate on Emivita's side ("an alert is not a dose", `OPC:220-226`); medTimer's overview shows future stock warnings as timeline rows. | - | none |
| A-47 | Recompute failure isolation | `SimulatedRemindersRepository.kt:57-73` (try/catch per run, idling resource) | `OPC:145-146` (`runCatching { recompute() }`) | parity | equal | Both keep the loop alive after one failing pass. | - | none |
| A-48 | Recompute triggers | `ScheduleNextReminderNotificationProcessor.kt:29` (`triggerCalculation()` on every reschedule) + `ReminderSchedulerService` on medicine DB change | `OPC:149-152` (`observeAllWithReminders().collect { invalidate() }`) + explicit `invalidate()` from `RealSchedulingCoordinator.kt:106` | parity | equal | Same two channels (reactive + explicit), same conflation. | - | none |

##### Spot-check list

1. **A-18 / Finding 2** — verify no other guard blocks re-arming an ON_RESOLVED interval while a dose is PENDING: read `RealSchedulingCoordinator.kt:47-48,79-82` and `SE:192-208`, then check whether `ReminderRepository`/`AlarmScheduler` filter on outstanding PENDING events before calling `schedule()`. If none, the boot path double-materializes.
2. **A-17 / Finding 1** — confirm the late-delivery replay is reachable: `ReminderAlarmReceiver.kt:145` passes `scheduledEpochMillis + 60_000L` (not `max(…, now)`), `AlarmScheduler.kt:133/136` arms whatever instant it gets. A verifier should try a clock jump of +3 days with an armed daily reminder and count notifications.
3. **A-09** — confirm medTimer's `StandardScheduling.kt:46` really excludes days before `cycleStartDay` in the shipped build (test `T/ReminderSchedulerUnitTest.kt:265-301` says yes) and whether medTimer's *documentation* says otherwise, since `ReminderConfig.kt:39-41` cites that documentation.

##### Findings

1. **Missed-slot replay on late delivery / clock jump** — `core/scheduling/ReminderAlarmReceiver.kt:145` reschedules from `scheduledEpochMillis + 60_000L` with no `now` floor, and `core/scheduling/AlarmScheduler.kt:133,136` arms a past instant (AlarmManager fires it immediately). For a daily FixedTime or ON_REMINDED interval whose alarm is delivered days late (system clock set forward, OEM alarm deferral), every missed slot materializes a DoseEvent and posts a notification in a chain. medTimer bounds this: `S/StandardScheduling.kt:16-17,57-58` (`raisedToday`) plus `S/IntervalScheduling.kt:42-57` (`adjustToToday` limits to today's first interval) and `TimeChangeReceiver.kt:26-30` re-bases on TIME_SET. Fix is `maxOf(scheduledEpochMillis + 60_000L, System.currentTimeMillis())` at `:145`.
2. **ON_RESOLVED interval/window re-armed while a dose is outstanding** — `core/scheduling/SchedulingEngine.kt:192-208` anchors on `DoseEventDao.kt:66-70` `getLastResolved` (excludes PENDING) with no check for an unresolved event, so `rescheduleAll()` (`RealSchedulingCoordinator.kt:79-82`) or any reminder edit (`:47-48`) arms a second alarm at `prevResolved + k·interval` and creates a second PENDING dose. medTimer returns null in this state (`S/IntervalScheduling.kt:32-35`, `S/WindowedIntervalScheduling.kt:67-70`; test `T/ReminderSchedulerIntervalUnitTest.kt:116-153` "otherwise the notification loop would fire continuously").
3. **ExpirationAlert ONCE never re-arms for a new pack** — `core/scheduling/ReminderScheduling.kt:301-304` returns null whenever `lastNotifiedEpochDay != null`; nothing clears the column when `StockInfo.expirationDateEpochDay` changes (`LowStockExpirationAlertEvaluator.kt:84-88` resets only the low-stock case; the only writer is `ReminderDao.kt:37`). medTimer's `S/ExpirationDateScheduling.kt:31-41` scans events relative to the *current* expiration date, so updating the date re-arms the alert.
4. **Documentation defect** — `core/data/config/ReminderConfig.kt:39-41` says `CyclicActivePause` is periodic before the anchor "matching medTimer's own documented behavior"; medTimer's code `S/StandardScheduling.kt:46` (`&& dayInCycle >= 0`) does the opposite and its test `T/ReminderSchedulerUnitTest.kt:265-301` pins it. Behaviour choice may stand; the justification is false.

##### As-is candidates

None. Every medTimer file in this area either depends on medTimer's `Reminder`/`ReminderEvent`/`Medicine` model (`Scheduling.kt`, all six strategies, `SchedulingSimulator.kt`, `CyclesHelper.kt`), on Hilt (`TimeChangeReceiver.kt`, `SimulatedRemindersRepository.kt`), or on `PreferencesDataSource` (`WeekendModeSchedulingDecorator.kt`). The two pure candidates (`TimeAccess.kt:7-13`, `TimeHelper.isOnDay` cache `:94-111`) duplicate what `SchedulingEngine.kt:38-39,180-181` already provides.

##### medTimer scheduler tests → Emivita equivalents (seed for porting)

`T/TestHelper.kt` is a helper, not a test. Emivita names below are backtick test names in `ET/ReminderSchedulingTest.kt` (RST) or `ET/SchedulingEngineTest.kt` (SET) unless stated; "partial" means the Emivita case pins only part of the medTimer behaviour.

**ReminderSchedulerUnitTest.kt**
- `scheduleEmptyLists` (:20) — no medicines/no reminders → empty; reminder created after today's slot → tomorrow. Emivita: RST `daily rolls to the next day once the slot has passed` (partial: via notBefore, not createdTime).
- `scheduleReminders` (:43) — two reminders on one medicine sorted by time; earlier reminder on a second medicine sorts first. Emivita: NONE (no aggregate list).
- `scheduleWithEvents` (:64) — an event raised today removes today's slot; same-time reminders; after all raised, roll to next day; day advance. Emivita: NONE (FixedTime ignores events).
- `scheduleSameDayReminder` (:131) — created at 08:20 with 08:00 slot → tomorrow 08:00. Emivita: RST `daily rolls to the next day once the slot has passed` (equivalent through the notBefore floor).
- `scheduleDifferentMedicineReminder` (:151) — two medicines same time, list order stable. Emivita: NONE.
- `scheduleReminderWithOneDayPause` (:173) — every-2-days: after raise on day 1, next is day 3. Emivita: RST `every n days snaps forward onto the grid`.
- `scheduleTwoDayReminderVsOneDay` (:199) — every-2-days already raised vs daily → daily first. Emivita: NONE.
- `scheduleReminderWithOneDayPauseVsThreeDaysPause` (:220) — two cycles interleaved across five days with events. Emivita: NONE.
- `scheduleCycleInFuture` (:265) — cycle start 4 days ahead → first on start day, nothing before it; event already on tomorrow skips it. Emivita: RST `every n days before the anchor returns the anchor date itself` (EveryNDays only); for CyclicActivePause Emivita pins the *opposite* (`cyclic pattern is periodic before the first cycle start`).
- `scheduleLongCycleInFuture` (:304) — 90-on/20-off cycle starting in 4 days. Emivita: RST `cyclic pattern skips the pause stretch` (partial).
- `reminderOverMidnight` (:338) — 23:45 reminder whose event was processed after midnight still counts for its reminded day → next is tomorrow 23:45. Emivita: NONE.
- `reminderTomorrow` (:356) — events today and tomorrow → day after tomorrow. Emivita: NONE.

**ReminderSchedulerActiveDayOfMonthUnitTest.kt**
- `scheduleDayOfMonth` (:17) — days {1,2,3}: Jan 2 → Jan 2, Jan 3 → Jan 3, Jan 11 → Feb 1. Emivita: NONE (feature absent, A-13).

**ReminderSchedulerActivePeriodUnitTest.kt**
- `scheduleInactive` (:15) — inactive reminder → nothing. Emivita: SET `a disabled reminder has no next occurrence`.
- `scheduleActive` (:30) — period day 3–4: before → first on day 4 (epochDay 3); on last day → that day; after → nothing. Emivita: SET `a range that has not opened yet defers to its first day rather than never firing`, `a candidate inside the active range survives the clamp`, `a candidate after the active range closes is clamped away`.
- `scheduleActiveInterval` (:61) — continuous interval clamped to period keeps its time-of-day on periodStart; after end → nothing. Emivita: NONE (clamp tests use FixedTime only).
- `scheduleActiveWindowedInterval` (:93) — windowed interval clamped to period; after end → nothing. Emivita: NONE.

**ReminderSchedulerDaysUnitTest.kt**
- `scheduleSkipWeekdays` (:15) — Fri+Sat excluded, asked Fri → Sun. Emivita: RST `specific days picks the next listed weekday`.
- `scheduleWeekdaysWithDaysBetweenReminders` (:41) — Mondays AND every 6 days → first coincidence (day 19). Emivita: NONE (A-10).

**ReminderSchedulerExpirationTest.kt**
- `scheduleExpirationReminderDisabled` (:15) — expirationDate EPOCH → nothing. Emivita: SET `an expiration alert on a medicine with no expiry date has nothing to arm`.
- `scheduleExpirationReminderOnce` (:35) — fires on `expiry − daysBefore` once; not before window; blocked after an event; re-arms when events are absent. Emivita: SET `a once expiration alert that already fired is never scheduled again` (partial and divergent: Emivita never re-arms, Finding 3).
- `scheduleExpirationReminderDaily` (:94) — first on window open; after event → next day; moving expiry moves window; daysBefore beyond today → today. Emivita: SET `an expiration alert waits until its lead window opens`, `a daily expiration alert keeps firing past the expiry date`, `an alert whose slot has passed today rolls to tomorrow`.

**ReminderSchedulerIntervalUnitTest.kt**
- `scheduleIntervalReminder` (:15) — no events → `intervalStart`; after event → reminded+interval; startsFromProcessed with unprocessed event → nothing; once processed → processed+interval. Emivita: RST `repeating interval before the anchor returns the anchor`; SET `an on-resolved interval anchors on the last resolved dose`, `an on-reminded interval ignores dose history and stays on the config anchor`; the "unprocessed → nothing" step: NONE (Finding 2).
- `scheduleIntervalReminderPause` (:66) — last event 3 days ago → first interval today (`adjustToToday`), not the stale chain. Emivita: NONE (nearest RST `repeating interval stays on the anchored grid when an alarm fires late`).
- `scheduleIntervalReminderNotTaken` (:91) — 23 h interval from processed at 01:00 day 2 → day 3 00:00 (crosses midnight, no adjust). Emivita: NONE.
- `intervalStartsFromProcessedDoesNotLoopOnUnprocessedEvent` (:116) — unprocessed event at the next slot blocks rescheduling. Emivita: NONE (behaviour differs, Finding 2).

**ReminderSchedulerLinkedUnitTest.kt**
- `sourceReminderNotRemindedYet` (:11) — child not scheduled until source has an event. Emivita: SET `a child whose parent has never fired has no next occurrence yet`.
- `scheduleLinkedReminder` (:32) — raised-not-processed source → child absent; processed → child at processed+delay; after child's own event → child absent until source processed again. Emivita: SET `an on-resolved child follows the last resolution of its parent`, `a child whose slot has already passed has no next occurrence` (partial: Emivita uses notBefore rather than "child's last event").
- `scheduleTwoLinkedReminders` (:94) — child-of-child fires at grandparent+delay1+delay2. Emivita: NONE.

**ReminderSchedulerOutOfStockTest.kt**
- `scheduleOutOfStockReminderNotDaily` (:11) — ONCE/ALWAYS never scheduled even below threshold. Emivita: SET `once and always low-stock modes stay event-driven, not scheduled`.
- `scheduleOutOfStockReminderDaily` (:40) — above threshold → nothing; below → today at time; after event → tomorrow. Emivita: SET `a low-stock alert above its threshold has nothing to arm`, `a daily low-stock alert below its threshold is scheduled`, `a daily low-stock alert already announced today waits for tomorrow`.

**ReminderSchedulerTimezoneUnitTest.kt**
- `scheduleWithEvents` (:13) — same reminder yields the correct instant in CET, America/New_York, Asia/Kolkata. Emivita: RST runs entirely in Europe/Rome (`:36`) plus `fixed time inside the spring-forward gap shifts to the first real instant` (partial: one zone, DST instead of offsets).

**ReminderSchedulerWeekendModeTest.kt**
- `weekendDaysEmpty` (:22) — mode on, no days → untouched. Emivita: RST `an empty weekend day set is a no-op regardless of the times`.
- `weekendMode` (:43) — Thu 00:16 untouched; Sat 00:16 → 10:00. Emivita: RST `weekend mode pushes an in-window occurrence to the window end`, `weekend mode leaves a non-weekend day untouched`.
- `weekendModeTimeRange` (:79) — range 04:00–12:00: 01:00 untouched, 08:00 → 12:00. Emivita: RST `weekend mode leaves an occurrence outside the window untouched`, `weekend mode treats the window start as inclusive and the end as exclusive`.

**ReminderSchedulerWindowedIntervalUnitTest.kt**
- `scheduleWindowedIntervalReminder` (:17) — 02:00–11:40/8 h: 02:00, then 10:00, then next day 02:00; startsFromProcessed unprocessed → nothing; processed → processed+8 h; beyond window → next day start. Emivita: RST `daily window steps onto the interval grid inside the window`, `daily window rolls to the next day once the window is exhausted`, `on-resolved daily window restarts the grid from the resolution`; the unprocessed step: NONE.
- `scheduleWindowedIntervalReminderPause` (:89) — last event 3 days ago → today's window start. Emivita: NONE (nearest RST `a resolution from a previous day does not leak into the grid for today`).
- `scheduleWindowedIntervalReminderCreated` (:117) — created after today's window start → tomorrow's start. Emivita: NONE (behaviour differs, A-06).
- `scheduleWindowedIntervalReminderNextDay` (:145) — overnight window 16:40→03:20 chains across midnight. Emivita: NONE (RST `daily window with the end before the start returns null instead of crashing` pins the opposite, A-20).
- `windowedIntervalStartsFromProcessedDoesNotLoopOnUnprocessedEvent` (:215) — unprocessed event at next slot blocks. Emivita: NONE.

**SchedulingSimulatorTest.kt**
- `standard` (:45) — two medicines interleave day by day; stockAfter 0.0 when untracked; consumer can stop. Emivita: SET `occurrencesFor walks every slot in the range` (single reminder; interleave NONE).
- `interval` (:106) — 10 h interval crosses midnight into day 2 at 06:00. Emivita: NONE.
- `intervalStartsFromProcessed` (:149) — simulator bases on processed event and treats a later unprocessed event as processed. Emivita: NONE.
- `windowedInterval` (:205) — 02:00, 10:00, next-day 02:00 in simulation. Emivita: NONE.
- `windowedIntervalReversed` (:251) — overnight window in simulation. Emivita: NONE.
- `linkedAndAmount` (:311) — parent+child every day with stock 12→11→9→8→6. Emivita: SET `occurrencesFor yields at most one linked-following occurrence` (opposite behaviour, A-24); stock side `ET/OccurrenceProjectionCacheTest.kt` `a stock run-out beyond the Today window is still projected`.
- `noReminders` (:354) — simulation runs to day 30 with nothing. Emivita: `ET/OccurrenceProjectionCacheTest.kt` `with no registrations the window is the floor` (partial).
- `dailyReminders` (:366) — windowed reminder with `intervalStart` in the past yields 02:00/10:00/next 02:00. Emivita: NONE.
- `outOfStock` (:405) — DAILY out-of-stock reminder appears as a simulated entry on the day stock hits threshold. Emivita: NONE (excluded by design, A-46).
- `twoFutureEventsNotDuplicated` (:440) — real events on days 2 and 3 suppress those days; simulation yields day 1 and day 4. Emivita: NONE (A-38).
- `singleDays` (:477) — days-of-month {5, 8}. Emivita: NONE (A-13).

### E.5.B — Alarms, notifications, channels, receivers, boot and time change

#### Area B — alarms, notifications, channels, receivers, boot/time-change, DND, full-screen alarm

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| B-01 | Exact vs inexact alarm decision | `feature/reminders/.../AlarmProcessor.kt:92-98,123-129` | `core/scheduling/AlarmScheduler.kt:58-59,124-138` | parity | emivita-better | Both branch `setExactAndAllowWhileIdle` / `setAndAllowWhileIdle` on `RTC_WAKEUP`. medTimer `canScheduleExactAlarms()` (:125) additionally requires the `exact_reminders` pref (default **false**, `UserPreferences.kt:67`), so exact is opt-in; Emivita `canScheduleExact()` (:58-59) is API-only and exact by default. | - | — |
| B-02 | Exact-alarm permission strategy | `app/src/main/AndroidManifest.xml:8-10` (`SCHEDULE_EXACT_ALARM`, uncapped); `feature/ui/.../NotificationSettingsFragment.kt:145-164,200-204` (dialog → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`; resets pref on resume if revoked) | `app/src/main/AndroidManifest.xml:20-22` (`SCHEDULE_EXACT_ALARM maxSdkVersion=32` + `USE_EXACT_ALARM`) | divergent | emivita-better | medTimer needs a user grant on API 31+ (dialog at `NotificationSettingsFragment.kt:147-151`); Emivita's `USE_EXACT_ALARM` (:22) is install-granted so `canScheduleExactAlarms()` is true with no prompt (device-verified, SPEC §7 R39). Play-policy-free because sideloaded (§1). | - | `USE_EXACT_ALARM` would be a Play-policy problem; Emivita is never on Play. |
| B-03 | Alarm request-code / slot scheme | `AlarmProcessor.kt:30-31,59,100-115`; `notificationData/PendingIntentFactory.kt:82-91` | `AlarmScheduler.kt:154-167` | divergent | equal | medTimer: slot 0 for next-scheduled (`:102`), per-**event** slot `reminderEventIds[0]` for snooze/repeat/show (`PendingIntentFactory.kt:87`). Emivita: per-**reminder** slots `reminderId`, `-reminderId`, `1_500_000_000+reminderId` (`:159-166`). Emivita collision risk: reminderId ≥ 647,483,648 wraps nag into regular range; a second unanswered dose of the *same* reminder shares the snooze/nag slot (see Findings). medTimer risk: event id 0 is reserved for "not yet created" (`AlarmProcessor.kt:64`). | - | — |
| B-04 | Single "next reminder" alarm vs one alarm per reminder | `ScheduleNextReminderNotificationProcessor.kt:28-60` (Mutex; loops until a future reminder is set; `cancelNextReminder` when none) | `RealSchedulingCoordinator.kt:69-83,96-107` (one alarm per enabled reminder) | divergent | equal | medTimer arms exactly one alarm for the earliest upcoming reminder and re-computes after every fire (`ReminderCommandBusImpl.kt:39-43`); Emivita arms N alarms, one per reminder (`scheduleOrCancel` :96-101). Emivita's design is what makes the per-reminder slot scheme (B-03) sufficient. | - | — |
| B-05 | Combine same-minute reminders into one notification | `ReminderNotificationData.kt:180-196` (`fromScheduledReminders`, same `epochSecond/60`); `ScheduleNextReminderNotificationProcessor.kt:52-55` (`combine_notifications` pref) | ABSENT (searched: `setGroup`, `combine`, `sameMinute`, `groupBy`) | absent-in-emivita | inspiration | medTimer groups reminders due in the same minute into one notification with one Taken/Skip acting on all event ids (`NotificationIntentBuilder.kt:29,55`); Emivita posts one notification per `doseEvent.id` (`ReminderNotificationBuilder.kt:47`) and never calls `setGroup`. Three 08:00 medicines → three notifications, three chimes. | M | Requires Emivita actions to carry a list of dose-event ids; `ReminderActionReceiver.kt:37` reads a single `EXTRA_DOSE_EVENT_ID`. |
| B-06 | Chime suppression for back-to-back notifications | `notificationFactory/NotificationFactory.kt:85-95` (`shouldBeSilent()`: `setSilent(true)` if < 10 s since last), `:48` | ABSENT (searched: `setSilent`, `setOnlyAlertOnce`) — `ReminderNotificationBuilder.kt:55` sets `setOnlyAlertOnce(false)` | absent-in-emivita | import-as-is | medTimer's companion `lastNotificationTime`/`shouldBeSilent()` (`NotificationFactory.kt:86-95`) is 10 lines of pure Kotlin (`kotlin.time.Clock`); Emivita has no equivalent, so B-05's three notifications each alert. | S | Uses `kotlin.time.Clock/Instant` (`@ExperimentalTime`); trivially replaceable by `System.currentTimeMillis()`. |
| B-07 | Notification id scheme | `Notifications.kt:47-48`; `core/datastore/.../PersistentDataDataSource.kt:83-87` (persisted monotonic counter) | `ReminderNotificationBuilder.kt:45-47` (`doseEvent.id.toInt()`); `AlertNotificationBuilder.kt:30-32,37,59` (`-(reminder.id)`) | divergent | equal | medTimer never reuses an id (counter survives reboot); Emivita derives ids from row ids: positive for doses, negative for alerts, so the two builders cannot collide and a nag/snooze re-post replaces the same notification (`ReminderAlarmReceiver.kt:277`). Emivita risk is only the `Long→Int` wrap the code itself documents (`:45-46`). | - | — |
| B-08 | Action PendingIntent request codes | `NotificationIntentBuilder.kt:56-60,71-76,96-101,111-116` (all use `notificationId` as request code; distinct by `Intent.action`) | `ReminderNotificationBuilder.kt:131-146` (`doseEventId*10 + salt` 1/2/3) | divergent | equal | Both produce distinct PendingIntents per action. medTimer relies on `Intent.filterEquals` seeing different actions (`WorkerIntents.kt:231-236`); Emivita additionally salts the request code (`:139`) so even identical actions could not merge. Emivita's `*10` overflows `Int` at doseEventId > 214,748,364 — documented as "practically safe" (`:45-46`). | - | — |
| B-09 | Full-screen-intent PendingIntent request code | `notificationFactory/ReminderNotificationFactory.kt:169-183` (request code **0**, `FLAG_UPDATE_CURRENT`) | `ReminderNotificationBuilder.kt:111-116` (request code `doseEventId.toInt()`) | divergent | emivita-better | medTimer shares one activity PendingIntent (code 0) across all alarm notifications; with two uncombined alarm notifications active, `FLAG_UPDATE_CURRENT` overwrites the first one's extras with the second's. Emivita keys the activity PendingIntent per dose event (`:113`), so each alarm opens its own dose. | - | — |
| B-10 | Notification actions: order and visibility rules | `ReminderNotificationFactory.kt:194-244` (order depends on `dismiss_notification_action`; skip hidden if `cannotBeSkipped`/`cannot_skip_reminders` via `NotificationIntentBuilder.kt:65-69`; location-snooze last) | `ReminderNotificationBuilder.kt:57-68` (Take, Snooze, then Skip only if `!isLockedUntilTaken`) | divergent | equal | Both hide Skip when the medicine/global lock says so (medTimer `:66-67` per-medicine `cannotBeSkipped` OR global pref; Emivita `SettingsRepository.kt:171-172` per-medicine nullable falling back to global). medTimer omits whichever action is bound to swipe-dismiss; Emivita has no swipe action at all (B-11), so it always shows all three. Emivita re-checks the lock at action time (`ReminderActionReceiver.kt:78-86`); medTimer does not re-check. | - | — |
| B-11 | Swipe-to-dismiss semantics (`deleteIntent`) | `ReminderNotificationFactory.kt:154`; `NotificationIntentBuilder.kt:119-133` (SKIP / SNOOZE / TAKE per `dismiss_notification_action`, default SKIP `UserPreferences.kt:74`) | ABSENT (searched: `setDeleteIntent`, `deleteIntent`) — `ReminderNotificationBuilder.kt:56` `setAutoCancel(false)` | absent-in-emivita | inspiration | In medTimer a swiped reminder is recorded (SKIPPED by default). In Emivita a swiped notification leaves the `DoseEvent` PENDING forever with nothing on screen; only a configured nag (`ReminderAlarmReceiver.kt:247-286`) brings it back. | S | One `setDeleteIntent` + a new action string in `ReminderActionReceiver`; "which status" is a product decision (medTimer makes it a 3-way pref). |
| B-12 | Tap-on-notification content intent | `NotificationFactory.kt:72-83`; `ReminderNotificationFactory.kt:146,152`; `OutOfStockNotificationFactory.kt:37,52`; `ExpirationDateNotificationFactory.kt:110,125` | STANDARD/HIGH: ABSENT (searched: `setContentIntent`) — only `ReminderNotificationBuilder.kt:128` for ALARM; `AlertNotificationBuilder.kt:39-45,61-67` none | absent-in-emivita | inspiration | medTimer opens `MainActivity` (`FLAG_ACTIVITY_CLEAR_TOP`) on tap for every notification kind; in Emivita tapping a Standard/High reminder, a low-stock alert, or an expiry alert does nothing. See Findings F-1. | S | — |
| B-13 | Snooze: fixed duration | `SnoozeProcessor.kt:253-260` (`Instant.now()+duration`, `setSecondaryAlarm`, cancel notification); pref list default 15 min (`UserPreferences.kt:71`, `snooze_settings.xml:6-9`) | `ReminderActionReceiver.kt:91-115`; `AlarmScheduler.kt:77-85`; default 10 min (`AlarmScheduler.kt:23`, `SettingsRepository.kt:75-76`); free-text minutes UI `SettingsDetailScreens.kt:108-152` | parity | equal | Same mechanism (secondary alarm at now+N, notification cancelled). Emivita additionally refuses to snooze a non-PENDING event (`:97-100`) and a PRN event (`:101-107`); medTimer's `setSecondaryAlarm` cancels the per-event alarm first (`AlarmProcessor.kt:63-67`). Emivita's snooze slot is per reminder (B-03). | - | — |
| B-14 | Snooze: custom duration ("Custom…" dialog) | `NotificationIntentBuilder.kt:79-103` (negative pref → activity intent); `WorkerIntents.kt:238-244`; `app/.../MainActivity.kt:334-355` (`TextInputDialogBuilder`) | ABSENT (searched: `CUSTOM_SNOOZE`, `snooze.*dialog`) | absent-in-emivita | inspiration | medTimer routes the Snooze action to `MainActivity` with `CUSTOM_SNOOZE_ACTIVITY` and prompts for minutes; Emivita reads one global `snoozeDurationMinutes` (`ReminderActionReceiver.kt:108`). | M | medTimer dialog is View/`MaterialAlertDialog`; Emivita would need a Compose dialog route in `MainActivity`. |
| B-15 | Snooze until home (location/geofence) | `SnoozeProcessor.kt:262-269`; `NotificationIntentBuilder.kt:105-117`; `ReminderNotificationFactory.kt:230-238`; `Autostart.kt:51-53` | EXCLUDED §5 ("not yet needed since that feature isn't planned"), §1 (no GMS); manifest comment `AndroidManifest.xml:74-81` | excluded-by-design | n/a | medTimer's full flavor uses `play-services-location` (`app/src/full/AndroidManifest.xml:5-10`); Emivita declares no location permission by design. | - | GMS-only in medTimer `full`; `foss` flavor no-ops it. |
| B-16 | Repeat / nag unanswered reminders | `RepeatProcessor.kt:286-295` (global prefs `repeat_reminders`=false, 3 reps, 10 min, `UserPreferences.kt:68-70`); `ReminderNotificationProcessor.kt:162-169`; `NotificationProcessor.kt:154-169` | `ReminderAlarmReceiver.kt:224-237,247-286`; `Reminder.kt:18-21,65` (`RepeatConfig` per reminder, defaults 30 min / 3 `AddReminderScreen.kt:439-440`) | divergent | emivita-better | medTimer: one global switch, `remainingRepeats` seeded per event and decremented (`:292-294`). Emivita: per-reminder opt-in, `repeatCount` incremented per nag, PENDING re-checked on each fire (`:268-272`), and `resolve()` cancels the nag (`DoseEventRepository.kt:142`). Emivita nag slot is per reminder (B-03 / F-2). | - | — |
| B-17 | Auto-taken / autoResolve reminders | `ReminderNotificationProcessor.kt:134-152` (`automaticallyTaken` → `setReminderEventStatus(TAKEN)`, no notification) | `ReminderAlarmReceiver.kt:125-131` (`autoResolve` → `resolve(TAKEN)`) | parity | equal | Both mark TAKEN silently at fire time; both still run stock handling through the normal path (`NotificationProcessor.kt:191`, `DoseEventRepository.kt:100-124`). | - | — |
| B-18 | Duplicate alarm delivery / already-processed guard | `ReminderNotification.kt:16-18` (`filterAlreadyProcessed`: only RAISED); `ReminderNotificationProcessor.kt:120-121` | `ReminderAlarmReceiver.kt:112-139` (re-show if existing event still PENDING) | parity | equal | Both refuse to re-notify a resolved event. Emivita re-posts on a duplicate delivery while PENDING (`:133-138`) and re-arms the nag (`:137`), which resets the nag countdown. | - | — |
| B-19 | Reboot / package-replace: re-arm alarms | `app/.../Autostart.kt:39-57` (`hasRestored` latch; reschedule via command bus); manifest `AndroidManifest.xml:62-71` (`exported=false`, permission `RECEIVE_BOOT_COMPLETED`) | `core/scheduling/BootRescheduleReceiver.kt:129-151` (`rescheduleAll`, per-reminder `runCatching` `RealSchedulingCoordinator.kt:79-82`); manifest `:179-186` (`exported=true`, device-verified R39) | parity | equal | Both re-arm on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. Emivita isolates per-reminder failures (`:80-81`); medTimer runs one `scheduleNextReminder()` (B-04). | - | — |
| B-20 | **Q1** Reboot: unanswered notification | `AutostartService.kt:86-109` (RAISED events of last 1 day, grouped by `remindedTimestamp`, re-broadcast `ShowReminderNotification` → `ShowReminderNotificationProcessor.kt:210-216` → `setSecondaryAlarm` fires immediately `AlarmProcessor.kt:69-77`) | ABSENT (searched: `PENDING` + `reschedule`, `restoreNotifications`, `re-raise`, `BootRescheduleReceiver`) | absent-in-emivita | inspiration | medTimer re-shows every still-RAISED notification (excluding location-snoozed ones, `:92,111-116`). Emivita's `rescheduleAll()` computes `nextOccurrenceFor(reminder, now)` (`RealSchedulingCoordinator.kt:70,97`) — strictly future — so a `DoseEvent` left PENDING before the reboot gets no notification, and pending snooze/nag alarms (`-id`, `1.5e9+id` slots) are never re-armed. The dose survives only as a "Due" row on Today. | M | Pure logic: `DoseEventDao` query for PENDING within N hours + `buildAndPost`. |
| B-21 | Force-stop detection | `app/.../MainActivity.kt:297-307` (`getHistoricalProcessExitReasons` → `REASON_USER_REQUESTED` → `restoreNotifications()`) | ABSENT (searched: `getHistoricalProcessExitReasons`, `REASON_USER_REQUESTED`, `ApplicationExitInfo`) | absent-in-emivita | inspiration | After a user force-stop Android drops all of the app's alarms and receivers until next launch. medTimer re-raises notifications at next launch; also medTimer starts `ReminderSchedulerService` on every resume (`:313-319`) which re-runs scheduling (`ReminderSchedulerService.kt:481-485`). Emivita's `MainActivity.kt:38-48` calls nothing scheduling-related, so after force-stop no alarm exists until the user edits a reminder, saves weekend mode (`SettingsDetailScreens.kt:249`), restores a backup (`BackupRepository.kt:75`), or reboots. See Findings F-3. | S | `rescheduleAll()` on `MainActivity` start (or process start) is one call. |
| B-22 | **Q2** `TIME_SET` / `TIMEZONE_CHANGED` | `app/.../TimeChangeReceiver.kt:142-148`; manifest `:72-80` | ABSENT (searched: `TIME_SET`, `TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, `TimeChange`) | absent-in-emivita | inspiration | medTimer reschedules on both and reads `ZoneId.systemDefault()` live (`SystemTimeAccess.kt:11`). Emivita arms absolute epoch millis (`AlarmScheduler.kt:133`) computed by `date.atTime(h,m).atZone(zone).toInstant()` (`ReminderScheduling.kt:107,114`) where `zone` is captured **once** at construction (`SchedulingEngine.kt:38`) of a lazy singleton (`AppContainer.kt:78-80`). After a +6 h zone change: the already-armed 08:00 alarm fires at 14:00 new local time, and every recomputation until process death still uses the old zone. After `TIME_SET` forward past an armed instant the alarm fires immediately; set back, it waits the extra time. | S | Receiver + `rescheduleAll()`; also `SchedulingEngine` must read `ZoneId.systemDefault()` per call (or the engine must be rebuilt) — otherwise the receiver reschedules with the stale zone. |
| B-23 | Scheduler service reacting to DB changes | `ReminderSchedulerService.kt:466-501` (`LifecycleService`, `START_STICKY`, collects `medicineRepository.getAllFlow()` → reschedule); started from `MainActivity.kt:313-319` | `SchedulingCoordinator.kt:182-218` + explicit repository call sites (`ReminderRepository.kt:84,127,156,166,172,190,228,231`; `MedicineRepository.kt:46,72`; `DoseEventRepository.kt:135,142,150`) | divergent | emivita-better | medTimer needs a sticky service and a Flow to notice edits because one alarm covers all reminders (B-04); Emivita calls the coordinator synchronously from each mutation, no service, no `FOREGROUND_SERVICE`/`WAKE_LOCK` (manifest `:69-70` removes them). | - | — |
| B-24 | Notification channels | `ReminderNotificationChannel.kt:183-210`; `ReminderNotificationChannelManager.kt:218-266` (ids `"3"`,`"4"`,`"out_of_stock"`; legacy `NOTIFICATION{n}` deleted; created lazily per notification `NotificationFactory.kt:31-35` and in `MainActivity.kt:116`) | `ReminderNotificationChannels.kt:169-269` (`reminders_standard`/`_high`/`_alarm_v2`; legacy `reminders_alarm` deleted `:267`; created once in `EmivitaApplication.kt:21`) | parity | emivita-better | Emivita's ALARM channel carries `DEFAULT_ALARM_ALERT_URI` on `USAGE_ALARM` + vibration `[0,500,500,500]` (`:254-262`) so a heads-up banner rings like an alarm; medTimer's HIGH channel is a plain `IMPORTANCE_HIGH` channel (`Medicine.kt:35`) and relies entirely on the full-screen activity for sound. medTimer has a dedicated out-of-stock channel; Emivita posts alerts to the medicine's tier channel (`AlertNotificationBuilder.kt:39,61`). | - | — |
| B-25 | **Q4** DND bypass on channel | `ReminderNotificationChannel.kt:194-196` (`setBypassDnd(true)` on every channel, every init) | ABSENT by explicit decision (`ReminderNotificationChannels.kt:163-167` comment) | absent-in-emivita | inspiration | `setBypassDnd` only takes effect if the app holds notification-policy access; medTimer requests it (B-26). Emivita relies on `CATEGORY_ALARM` (`ReminderNotificationBuilder.kt:121`) + `USAGE_ALARM` stream, which DND's default "allow alarms" rule honours — Standard/High tiers are silenced by DND. | S | Needs `ACCESS_NOTIFICATION_POLICY` in manifest and a consent screen (B-26); the `setBypassDnd(true)` line itself is trivial. |
| B-26 | **Q4** DND override + ringer un-mute | `NotificationSoundManager.kt:29-33,44-67,69-97` (if `override_dnd` && `isNotificationPolicyAccessGranted`: un-mute `STREAM_RING`, add `PRIORITY_CATEGORY_REMINDERS`, restore after 5 s); `NotificationSettingsFragment.kt:180-192,206-210` (→ `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`; switch reset if revoked); manifest `:19` | ABSENT (searched: `isNotificationPolicyAccessGranted`, `ACCESS_NOTIFICATION_POLICY`, `ringerMode`, `notificationPolicy`) | absent-in-emivita | inspiration | medTimer temporarily rewrites the system DND policy and ringer state around each `notify()` (`Notifications.kt:50-54`). Emivita touches neither `AudioManager` nor `NotificationManager.Policy`; the only DND-proof path is the ALARM tier's `USAGE_ALARM` stream (`AlarmActivity.kt:112-115`, channel `:254-260`). | M | `ACCESS_NOTIFICATION_POLICY` is a special-access grant; conflicts with Emivita §1 least-privilege posture — owner decision. |
| B-27 | **Q4** `USAGE_ALARM` audio stream | `alarm/ReminderAlarmActivity.kt:195-207` (MediaPlayer, `USAGE_ALARM`, attribution context `audioPlayback` `:190-194`, manifest `:26-28`) | `AlarmActivity.kt:112-123`; channel `ReminderNotificationChannels.kt:254-260` | parity | emivita-better | Both play on `USAGE_ALARM`. Emivita also puts the *channel* sound on `USAGE_ALARM` (medTimer's channels use the default notification stream), so the banner case rings as an alarm too (SPEC §7 R59). medTimer adds an attribution tag for the audio (`:191`); Emivita does not. | - | — |
| B-28 | **Q5** Alarm activity: ringtone source | `ReminderAlarmActivity.kt:195-207` (`alarm_ringtone` pref `UserPreferences.kt:86`, picker via `preferencex-ringtone`, fallback to `DEFAULT_ALARM_ALERT_URI` `:201-207`) | `AlarmActivity.kt:118-124` (`DEFAULT_ALARM_ALERT_URI` only; `runCatching` → silent if null) | divergent | medtimer-better | medTimer lets the user pick the tone and double-falls-back when the chosen URI cannot be opened; Emivita hardcodes the system default (the settings screen says so `SettingsDetailScreens.kt:103-106`). | M | medTimer picker is `preferencex-ringtone` (XML); Emivita would use `RingtoneManager.ACTION_RINGTONE_PICKER` from Compose. |
| B-29 | **Q5** Alarm activity: looping, vibration, lifecycle | `ReminderAlarmActivity.kt:168-180,209,216-250` (single-thread executor `:136`; start on `onResume`, pause on `onPause`, release on `onDestroy`; `createWaveform([500,500],0)` `:249`) | `AlarmActivity.kt:103-141` (`onStart`/`onStop`; `isLooping=true` `:121`; `createWaveform([500,500],0)` `:132`; `VibratorManager` on S+ `:126-131`) | parity | equal | Same pattern and same 500/500 waveform. medTimer serialises MediaPlayer state changes on one executor (`:134-136`); Emivita creates/stops synchronously on the main thread (`MediaPlayer.create` is blocking `:119`). medTimer `pause()`s and can resume; Emivita `stop()`+`release()` and rebuilds on next `onStart` (`:110-111`). | - | — |
| B-30 | **Q5** Alarm activity: silent-mode checks | `ReminderAlarmActivity.kt:256-270` (`no_alarm_sound_when_silent`, `no_vibration_when_silent` vs `audioManager.ringerMode`, both default false `UserPreferences.kt:87-88`) | ABSENT (searched: `ringerMode`, `RINGER_MODE_SILENT`, `AudioManager`) | absent-in-emivita | inspiration | medTimer optionally respects the phone's silent switch; Emivita always rings and vibrates (`AlarmActivity.kt:110-133`). Note `USAGE_ALARM` already rings through vibrate mode on both. | S | Two settings keys + one `AudioManager.ringerMode` check. |
| B-31 | **Q5** Alarm activity: system bars, lock screen, keyguard | `ReminderAlarmActivity.kt:152-157` (`setShowWhenLocked`, `setTurnScreenOn`, hide `systemBars()`); manifest `feature/reminders/.../AndroidManifest.xml:16-21` (`singleInstance`, `showOnLockScreen`, `turnScreenOn`) | `AlarmActivity.kt:82-87` (+ `requestDismissKeyguard` `:84`); manifest `:133-138` (`singleTask`, `excludeFromRecents`) | parity | equal | Identical calls; Emivita additionally requests keyguard dismissal so the screen is interactive on a PIN-locked phone and hides itself from Recents. medTimer uses `singleInstance` (own task), Emivita `singleTask`. | - | — |
| B-32 | **Q5** Alarm activity: close semantics | `alarm/AlarmFragment.kt:428-437` (`pendingIntent.send()` then `finishAndRemoveTask()` in `onDestroyView`) | `AlarmActivity.kt:97` (`finish()`), `:237-268` (calls repository directly, then `NotificationManagerCompat.cancel`) | divergent | equal | medTimer's buttons fire the notification's own `PendingIntent`s (`AlarmFragment.kt:409-425`), so notification, alarm screen and swipe all take one code path; Emivita re-implements Take/Skip/Snooze inline (`resolveAndDismiss`/`snoozeAndDismiss`) duplicating `ReminderActionReceiver.kt:71-115` including the lock re-check (`:243-253`). Emivita `excludeFromRecents` (manifest `:137`) makes `finishAndRemoveTask` unnecessary. | - | — |
| B-33 | **Q10** Full-screen intent gating (API 34 `canUseFullScreenIntent`) | `ReminderNotificationFactory.kt:164-166,169-183` (`setFullScreenIntent` unconditionally, `@SuppressLint("FullScreenIntentPolicy")`); `feature/ui/.../RequestPostNotificationPermission.kt:18-33` (dialog → `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, called from `MedicineSettingsFragment.kt:76` and `AdvancedReminderPreferencesRootFragment.kt:188`) | `ReminderNotificationBuilder.kt:100-129` (`NotificationManagerCompat.canUseFullScreenIntent()`; else "Show alarm" action); `SettingsDetailScreens.kt:324-368` (`FullScreenAlarmAccessCard`, re-read on `ON_RESUME`) | parity | emivita-better | medTimer asks at the moment the user turns on "show as alarm" (dialog) but the builder never checks; on a revoked grant the alarm silently degrades. Emivita checks at post time (`:104,122-127`) and shows a persistent status card with a deep link (`:549-560`). | - | — |
| B-34 | **Q8** `POST_NOTIFICATIONS` request | `MainActivity.kt:153-161` + `MedTimerAppIntro.kt:28` (every launch until granted, `RequestPostNotificationPermission.kt:35-52`); denial writes `show_notification=false` (`PersistentDataDataSource.kt:101-103`) | `navigation/EmivitaApp.kt:127-148` (asks once, `notification_permission_requested`) | divergent | emivita-better | medTimer re-prompts on every cold start while denied (`:154-159`; Android stops showing the dialog after two denials). medTimer's `showNotifications` flag is **write-only** — no reader anywhere outside `PersistentDataDataSource.kt:163` / `PersistentData.kt:33` (grep). Emivita asks once and never again (PERM-023/024). | - | — |
| B-35 | **Q8** `POST_NOTIFICATIONS` denial surfaced to user | ABSENT in medTimer (searched: `areNotificationsEnabled`, `showNotifications` readers) — `ReminderNotificationProcessor.kt:185-187` silently skips `notify` | `core/notification/NotificationDelivery.kt:149-189`; `SettingsDetailScreens.kt:485-538` (`NotificationsBlockedCard`: `areNotificationsEnabled()` + tiers at `IMPORTANCE_NONE`); `NotificationPermission.kt:110-119` | absent-in-medtimer | emivita-better | Emivita shows a card (R58) covering both app-level block and per-tier block, with deep link to `ACTION_APP_NOTIFICATION_SETTINGS` (`:540-543`); medTimer only logs nothing and drops the notification (`canShowNotifications()` false → no `notify`). | - | — |
| B-36 | **Q9** Battery-optimisation warning | `MainActivity.kt:279-286,323` (card if `!isIgnoringBatteryOptimizations` && not dismissed, hidden in DEBUG; OK button only dismisses `:218-221`; text `strings.xml:305-306`); settings row `NotificationSettingsFragment.kt:128-143` (→ `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, manifest `:24`) | `SettingsDetailScreens.kt:399-466` (`BatteryOptimizationCard`: re-read on `ON_RESUME`; → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list; no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, manifest `:31-44`) | divergent | equal | medTimer's card is on the main screen (seen once, dismissable, no button to fix) and the fix lives in Settings with the direct allow-this-app dialog; Emivita's card is in Notifications & Alerts, undismissable while not exempt, with a one-tap-further system list. Trade-off: medTimer more discoverable, Emivita least-privilege (R39). | - | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` deliberately absent in Emivita. |
| B-37 | **Q9** Exact-alarm warning | `MainActivity.kt:288-295,324` (card if `exact_reminders` false and not dismissed; text `strings.xml:331-332`; OK only) | n/a — exact is unconditional (B-01/B-02); `AlarmScheduler.kt:135` logs a `Log.w` on the inexact fallback | absent-in-emivita | n/a | medTimer must nag because exact is opt-in; Emivita's `USE_EXACT_ALARM` makes `canScheduleExact()` true on every supported device, so the only inexact case is an OEM stripping the permission, which is logged and not surfaced. | - | — |
| B-38 | Per-reminder importance override | `notificationData/ReminderNotificationPart.kt:13-41` (`effectiveImportance`/`effectiveShowAsAlarm`/`channelForHighestImportance`: `SAME_AS_MEDICINE`/`DEFAULT`/`HIGH`/`HIGH_AND_ALARM`) | `ReminderNotificationBuilder.kt:77-78` (`doseGroup.priority ?: medicine.notificationPriority`); per-reminder: ABSENT (inventory: "no per-reminder importance override") | divergent | inspiration | medTimer resolves the channel from the highest importance across combined parts; Emivita resolves from DoseGroup (schema only, R29) or medicine. The per-reminder tier is a pure enum + `when` on medTimer's side. | M | Requires a `Reminder` column + migration + form row. |
| B-39 | Hide medicine name in notifications | `core/common/.../MedicineHelper.kt:22-32` (`getMedicineName(..., notification=true)` → first letter + `*`); used `NotificationStringBuilder.kt:210-211`, `OutOfStockNotificationFactory.kt:40`, `ExpirationDateNotificationFactory.kt:113` | ABSENT (searched: `hideMedicineName`, `hide_med`, `"*".repeat`); SPEC §9 #11 lists "hide names" as unbuilt | absent-in-emivita | import-as-is | `MedicineHelper.getMedicineName` body (`:27-31`) is 5 pure-Kotlin lines; Emivita's `ReminderNotificationBuilder.kt:51` uses `medicine.name` directly and `AlertNotificationBuilder.kt:41,63` puts the name in the title. | S | Needs a DataStore boolean; SPEC §9 #11 already plans it. |
| B-40 | Notification text: instructions, stock, tags, hidden name | `NotificationStringBuilder.kt:150-220` (bold name, amount, instructions, stock string + "⚠/🚫" icons, tag names; concise mode when > 3 parts `:172`) | `ReminderNotificationBuilder.kt:85-90` (`"Dose of $amount at $time"`, hardcoded English, inventory §6) | divergent | inspiration | medTimer's body is a `SpannableStringBuilder` per part; Emivita's is one untranslated literal (`:89`) and shows neither instructions nor stock nor tags (Emivita has no per-reminder instructions, inventory). | S | Pure Kotlin builder but depends on medTimer `Tag`/`Medicine` types. |
| B-41 | Big notification with custom buttons (`RemoteViews`) | `notificationFactory/BigReminderNotificationFactory.kt:261-296` (`R.layout.notification`, `DecoratedCustomViewStyle`, skip hidden when `cannotBeSkipped` `:271-273`) ; pref `big_notifications` | ABSENT (searched: `RemoteViews`, `setCustomBigContentView`) | absent-in-emivita | inspiration | Emivita relies on standard action chips (`:57-67`). | M | XML `RemoteViews` layout — not portable as code (brief fact 3). |
| B-42 | Sticky on lock screen (`setOngoing`) | `ReminderNotificationFactory.kt:160-163` (API 34+, `stickyOnLockscreen` pref); `NotificationSettingsFragment.kt:58-60` | ABSENT (searched: `setOngoing`) | absent-in-emivita | inspiration | One `setOngoing(true)` line behind a pref; Emivita notifications are swipeable (B-11). | S | — |
| B-43 | Large icon / colorised notification | `NotificationFactory.kt:38-46,51-70` (icon grid composite via `MedicineIcons.getIconsBitmap`; `setColorized` only when exactly one coloured medicine) | ABSENT (searched: `setLargeIcon`, `setColorized`, `setColor(`) — `ReminderNotificationBuilder.kt:49-58` sets only `setSmallIcon` | absent-in-emivita | inspiration | Emivita has 11 Material icons and a medicine colour (inventory) but does not put either on the notification. | S | `MedicineIcons.getIconsBitmap` depends on `com.maltaisn:icondialog` — not importable. |
| B-44 | Out-of-stock / expiration notifications | `OutOfStockNotificationFactory.kt:36-61` (own channel, "Refill N" action, ack on swipe); `ExpirationDateNotificationFactory.kt:109-129`; `StockIntentBuilder.kt:149-174` | `AlertNotificationBuilder.kt:36-70`; `AlertActionReceiver.kt:149-180` | parity | equal | Both offer one-tap refill only when a refill size exists (medTimer always adds it `:56-60`; Emivita gates on `refillQuantity > 0` `:50-53`). medTimer records ACKNOWLEDGED on swipe (`:55`); Emivita `setAutoCancel(true)` records nothing. Emivita re-checks the alert condition at fire time (`ReminderAlarmReceiver.kt:176-213`) — medTimer's out-of-stock is event-driven at stock change (`StockHandlingProcessor`, area C). | - | — |
| B-45 | Variable amount on Taken | `NotificationIntentBuilder.kt:39-63` (`askForAmount` → activity intent `VARIABLE_AMOUNT_ACTIVITY`) | ABSENT (searched: `askForAmount`, `variableAmount`, `VARIABLE_AMOUNT`) | absent-in-emivita | inspiration | Emivita's Take action always resolves with the scheduled `doseAmount` (`ReminderActionReceiver.kt:72-75`); editing the amount afterwards is a History/Today dialog (inventory `DoseEditDialog`). | M | Dialog is View-based in medTimer (`VariableAmountHandler`). |
| B-46 | Receiver protection / command bus | `ReminderProcessorBroadcastReceiver.kt:88-160` (single receiver, custom permission `NOTIFICATION_PROCESSED` `:163`, manifest `:5,18`; `ProcessorCode` dispatch; `goAsync` + app scope) | `ReminderAlarmReceiver.kt:43-76`, `ReminderActionReceiver.kt:35-63`, `AlertActionReceiver.kt:149-180` (three receivers, `exported=false`, `goAsync` + ad-hoc `CoroutineScope(Dispatchers.IO + SupervisorJob())`) | divergent | equal | Both use `goAsync()`; medTimer additionally guards with a signature-level custom permission (belt and braces over `exported=false`). Emivita creates a fresh unsupervised scope per broadcast (`:63`, `:54`, `:169`) where medTimer uses one injected `@ApplicationScope` (`:93-95`). | - | Hilt `@AndroidEntryPoint` in medTimer. |
| B-47 | Test coverage of this area | `app/src/test/.../processortests/` (7 Robolectric files incl. `AlarmProcessorTest.exactAlarms` `:20-21`), androidTest `NotificationTest.kt` (844 lines, inventory) | `androidTest/.../AlarmChainTest.kt` (1 test), `NotificationDeliveryDeviceTest.kt` (3), JVM `NotificationDeliveryTest` (6) | divergent | medtimer-better | Emivita has no JVM test for `ReminderAlarmReceiver`, `ReminderActionReceiver`, `AlarmScheduler` slot arithmetic, snooze or nag (inventory rows say "none"); medTimer covers snooze/repeat/show/reschedule with fakes (`TestReminderContext.kt`). | L | medTimer tests depend on Hilt test modules; only the *cases* port. |

##### Spot-check list

1. **B-22 (timezone)** — verifier should confirm `SchedulingEngine` is only ever built once (`AppContainer.kt:78-80` `by lazy`) and that no caller passes a fresh `zone`; then confirm on a device/emulator that changing the timezone with an armed FixedTime alarm leaves `dumpsys alarm` showing the old absolute trigger. The claim that recomputation after fire also uses the stale zone depends on there being no process restart in between.
2. **B-20 (reboot re-raise)** — I derived "no re-raise" from `rescheduleAll()` → `nextOccurrenceFor(reminder, now)` (`RealSchedulingCoordinator.kt:70,97`) and the absence of any PENDING-scan. A verifier should check `OccurrenceProjectionCache.kt` and `TodayScreen.kt` do not post notifications for overdue PENDING rows on app open (I grepped `buildAndPost` — only `ReminderAlarmReceiver.kt` calls it — but did not open the cache file).
3. **B-09 (medTimer full-screen request code 0)** — the "extras overwritten" consequence assumes two *separate* alarm-type notifications are active at once, which only happens with `combine_notifications` off and different-minute reminders; the verifier should confirm `ReminderAlarmActivity.getIntent` (`:281-291`) extras are what `AlarmFragment.onCreate` (`:343-345`) reads, and that `FLAG_UPDATE_CURRENT` with identical `Intent` (same component, no action) indeed matches by `filterEquals`.

##### Findings (defects in Emivita)

- **F-1 — Standard/High reminder notifications and both alert notifications have no content intent.** `ReminderNotificationBuilder.kt:49-58` never calls `setContentIntent` (only the ALARM branch does, `:128`); `AlertNotificationBuilder.kt:39-45,61-67` likewise. Tapping the notification body does nothing. medTimer: `NotificationFactory.kt:72-83` (`getStartAppIntent`) applied at `ReminderNotificationFactory.kt:152`, `OutOfStockNotificationFactory.kt:52`, `ExpirationDateNotificationFactory.kt:125`.
- **F-2 — Snooze and nag slots are per reminder, not per dose event.** `AlarmScheduler.kt:160,166` key the snooze/nag `PendingIntent` on `reminderId` with `FLAG_UPDATE_CURRENT` (`:150`). An ON_REMINDED interval reminder reschedules immediately on fire (`ReminderAlarmReceiver.kt:141-146`), so two unanswered doses of one reminder can coexist; snoozing the second silently cancels the first's pending snooze (and its nag chain, since `scheduleNag` at `:232-236` overwrites the same slot with the newer `originalScheduledEpochMillis`). medTimer keys by `reminderEventId` (`PendingIntentFactory.kt:87`, `AlarmProcessor.kt:106-115`).
- **F-3 — No reschedule on app launch after a force-stop.** `MainActivity.kt:38-48` and `EmivitaApplication.kt:11-22` never call `rescheduleAll()`; the only callers are boot (`BootRescheduleReceiver.kt:144`), weekend-save (`SettingsDetailScreens.kt:249`) and restore (`BackupRepository.kt:75`). After a user force-stop (which clears the app's alarms) reminders stay dead until reboot or an edit. medTimer: `MainActivity.kt:297-307` detects `REASON_USER_REQUESTED` and `:313-319` restarts `ReminderSchedulerService` on every resume, which reschedules (`ReminderSchedulerService.kt:481-485`).
- **F-4 — Timezone captured once.** `SchedulingEngine.kt:38` defaults `zone = ZoneId.systemDefault()` at construction and the engine is a lazy singleton (`AppContainer.kt:78-80`); with no `TIMEZONE_CHANGED` receiver (B-22) every FixedTime/DailyWindow computation after a zone change uses the old zone until the process dies. medTimer reads it live (`SystemTimeAccess.kt:11`) and reschedules (`TimeChangeReceiver.kt:143-147`).
- **F-5 — Swipe-dismiss leaves a PENDING dose with no record and no re-raise** (`ReminderNotificationBuilder.kt:56` `setAutoCancel(false)`, no `setDeleteIntent`). Arguably a product choice, but combined with B-20 (no re-raise on reboot) a dismissed-then-forgotten dose has no path back except a configured nag. medTimer: `ReminderNotificationFactory.kt:154` + `NotificationIntentBuilder.kt:119-133`.

##### As-is candidates

- **B-06** `C:/Users/User/Downloads/medTimer-main/medTimer-main/feature/reminders/src/main/java/com/futsch1/medtimer/feature/reminders/notificationFactory/NotificationFactory.kt:85-95` (`companion object { lastNotificationTime; shouldBeSilent() }`) → `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita/app/src/main/java/com/hazia/emivita/core/notification/NotificationPermission.kt` (next to `postIfPermitted`) or a new `core/notification/NotificationRateLimit.kt`; call `builder.setSilent(shouldBeSilent())` in `ReminderNotificationBuilder.kt:49`. Replace `kotlin.time.Clock.System.now()` with `System.currentTimeMillis()` to drop the `@ExperimentalTime` opt-in.
- **B-39** `C:/Users/User/Downloads/medTimer-main/medTimer-main/core/common/src/main/java/com/futsch1/medtimer/core/common/helpers/MedicineHelper.kt:22-32` (`getMedicineName`) → `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita/app/src/main/java/com/hazia/emivita/core/format/DoseFormatting.kt` as `fun maskedMedicineName(name: String, hide: Boolean): String`; signature changes from `(Medicine, Boolean, UserPreferences)` to `(String, Boolean)` — package/import edits only, no medTimer types survive.

### E.5.C — Dose events, stock, refill, tags, domain model

#### Area C — dose events, stock, refill, tags, domain model

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| C-01 | Medicine domain model | `core/domain/.../model/Medicine.kt:7-24` (flat data class; `notes`, `unit`, `refillSize`, `sortOrder`, `productionDate`/`expirationDate` as `LocalDate`, `tags`, `reminders` embedded) | `core/data/entity/Medicine.kt:25-73` (Room entity, `@Embedded StockInfo` :59, `profileId` FK CASCADE :16-21, `doseUnit` :45, `indication`/`storageNote` :70-71) | divergent | equal | medTimer keeps one model with stock columns inline and imports `android.app.NotificationManager` (:3), so it is not JVM-pure; Emivita splits stock into `StockInfo.kt:11-17` and adds `profileId`, `barcodeValue`, `doseUnit`. Neither side is copyable into the other without a full remap. | - | medTimer `Medicine.kt` needs `NotificationManager`/`Color` constants replaced before it is portable. |
| C-02 | "Stock tracking on/off" representation | `Medicine.kt:47-49` `isStockManagementActive() = amount != 0.0 \|\| hasStockReminder()`; `ReminderEventFactory.kt:67-68` writes `-1.0` when inactive | `Medicine.kt:36-44` comment + `StockInfo.kt:13` `unit: String? = null` = off; `DoseEventRepository.kt:328,350` `if (medicine.stockInfo.unit == null) return`; `MedicineDao.kt:104-105` `WHERE unit IS NOT NULL` | divergent | emivita-better | Not equivalent: in medTimer a tracked medicine that runs down to `amount == 0.0` with no out-of-stock reminder flips to "inactive" and its next events get `stockBefore = -1.0` (`ReminderEventFactory.kt:67`); Emivita's flag is the explicit `unit` column, so quantity 0 stays tracked. Emivita's only toggle is clearing the unit text (`StockExpirationScreen.kt:308` `unit.ifBlank { null }`), which is not discoverable. | S (add an explicit "track stock" switch) | — |
| C-03 | Dose-history record shape (snapshot vs join) | `ReminderEvent.kt:8-13,19` stores `medicineName`, `amount: String`, `color`, `iconId`, `tags: List<String>`, `notes`; `ReminderEventEntity.kt:15` no FK, `:31` tags as JSON list | `DoseEvent.kt:40-49` stores only `reminderId?`, `medicineId`, `doseAmount: Double`; display joins live medicine via `DoseEventDao.kt:23-47` `JOINED_SELECT`; FK `:20` `ON DELETE CASCADE` | divergent | equal | medTimer's event is a self-contained snapshot (renaming a medicine leaves old history untouched, `buildReminderEvent` :51-65); Emivita's history re-labels retroactively via the join and needs the medicine row to exist. Trade-off: medTimer survives medicine deletion (C-41), Emivita keeps exact numeric amounts. | - | — |
| C-04 | Event status enum | `ReminderEvent.kt:28` `RAISED, TAKEN, SKIPPED, DELETED, ACKNOWLEDGED`; `ReminderEventEntity.kt:10-12` filtered lists | `DoseEvent.kt:10` `PENDING, TAKEN, SKIPPED, REFILLED` | divergent | medtimer-better | medTimer carries `DELETED` (soft delete, C-12) and `ACKNOWLEDGED` (stock/expiry events, C-40); Emivita has neither — its refill is a status rather than a `reminderType`. ABSENT (searched: `DELETED`, `ACKNOWLEDGED`, `delete(` in `DoseEventDao.kt`). | M | — |
| C-05 | Take/Skip transition with stock decrement | `NotificationProcessor.kt:116-152` `setReminderEventStatus` (cancels alarms :135, `doStockHandling` :136, stamps `processedTimestamp` :139); `:156-169` decrements only when `!stockHandled && TAKEN` | `DoseEventRepository.kt:100-158` `resolve()`; PENDING guard inside `withTransaction` :107-110; `setStock` only for TAKEN :122-124; `cancelNag` :142 | parity | emivita-better | Both decrement on TAKEN and skip on SKIPPED. Emivita's idempotency is a transactional `status != PENDING` check (:110); medTimer's is the `stockHandled` flag (:157) with no transaction around fetch→update (`processReminderEventsInNotification` :44-58 then `updateAll` :150), so two near-simultaneous TAKEN deliveries can both pass. | - | — |
| C-06 | Taken↔Skipped switch — stock math (question 1) | `NotificationProcessor.kt:157-164`: SKIPPED after `stockHandled` → `amount = -parseAmount(event.amount)` → `processStock` (relative); `ActionsVisitor.kt:171-177` `undoStock` same relative delta from the *current* amount string | `DoseEventRepository.kt:191-234` `switchStatus`: delta = `stockBefore - stockAfter` recorded at resolve (:214-221), applied via `decrementStock`/`incrementStock` | parity | emivita-better | Both apply a *relative* delta, so both survive "stock changed in between" (§7 R27). Difference: medTimer re-parses `event.amount` at undo time, so editing the amount string after taking (`EditEventViewModel.kt:131`) makes the undo restore the edited number; Emivita reads the recorded pair, which `editDoseAmount` (:247-251) never touches. Edge: medTimer over-restores after a clamped decrement (`MedicineDao.kt:19` clamps to 0, undo adds the full parsed amount); Emivita's SKIPPED→TAKEN uses the *hypothetical* clamped pair (:348-354), so if stock was 1 when skipped and later refilled, switching to TAKEN decrements 1, not the dose. | - | — |
| C-07 | Status change from the edit sheet | `EditEventSheetDialogFragment.kt:134-144` toggle sets `viewModel.status`; `:146-149` `onDismiss → updateEvent()`; `EditEventViewModel.kt:130-139` writes `status` with a plain `repository.update`, no stock handling | `DoseEditDialog.kt:167` `if (chosenStatus != originalStatus) switchStatus(...)` | divergent | emivita-better | medTimer's edit sheet flips TAKEN↔SKIPPED without touching stock (`updateEvent` has no `stockHandlingProcessor` call), leaving `stockHandled` stale; Emivita routes the same UI through `switchStatus`. | - | — |
| C-08 | `stockBefore`/`stockAfter` sentinel (question 2) | `ReminderEvent.kt:24-25`, default `-1.0` (:58-59); written at *creation* `ReminderEventFactory.kt:67-68` (stock at raise time); `stockAfter` overwritten on TAKEN/SKIPPED `NotificationProcessor.kt:141-145`; never read for undo (undo uses `parseAmount`, `ActionsVisitor.kt:173`) | `DoseEvent.kt:62-63`, default `-1.0`; written at *resolution* `DoseEventRepository.kt:116-118` (and :276/:306 for PRN/refill); read by `switchStatus` :214 | divergent | emivita-better | Same type, same default, same DB default (`ReminderEventEntity.kt:35-36` vs `Migrations.kt:161-162`), but *not* identical semantics: medTimer's `stockBefore` is stock at raise time so a refill between raise and take makes `before - after ≠ consumed`, and the pair is display-only; Emivita's pair is written at resolve and is the source of truth for undo. §5 "adopted verbatim" (SPEC.md:556) overstates it. | - | — |
| C-09 | Stock undo on event removal | `ActionsVisitor.kt:161-169` RERAISE (undo + hard delete :164-165), `:179-191` DELETE (undo + `status = DELETED, stockHandled = false` :185-186) | ABSENT (searched: `delete(`, `DELETED`, `remove` in `DoseEventDao.kt:1-152`, `DoseEventRepository.kt`, `DoseEditDialog.kt`) | absent-in-emivita | medtimer-better | `DoseEventDao.kt:138-148` has only `deleteAllForMedicine` and `deleteAll`; there is no per-event delete path, so nothing needs (or has) an undo. See C-12. | M | — |
| C-10 | Reschedule a single pending dose (question 5) | `ActionsVisitor.kt:104-143` RESCHEDULE: time picker → `changeTimeMinutes` :117 → updates `remindedTimestamp` :127-128 and re-shows the notification :134-140; button visible for RAISED/PENDING `ReminderEventActions.kt:31-35` and for scheduled `ScheduledReminderActions.kt:13-21` | ABSENT (searched: `reschedule`, `postpone`, `re-raise` in `feature/`, `ui/`); snooze only: `AlarmScheduler.kt:77-85` keeps `originalScheduledEpochMillis` | absent-in-emivita | medtimer-better | Emivita's snooze re-fires the alarm after `snoozeDurationMinutes` but never moves `DoseEvent.scheduledEpochMillis`; there is no user-chosen time. | M | — |
| C-11 | Re-raise a processed dose | `ActionsVisitor.kt:161-169` hard-deletes the event so the scheduler re-creates it; hidden for manual doses `ReminderEventActions.kt:37-39` | ABSENT (searched as C-10) | absent-in-emivita | inspiration | Emivita's closest path is `DoseEditDialog` switching status, which never returns an event to PENDING (`resolve()` :105 forbids it). | M | — |
| C-12 | Soft-delete of a mistaken event (question 6) | `ActionsVisitor.kt:183-188` sets `DELETED`; excluded from overview `OverviewViewModel.kt:89`, scheduling `ReminderEventRepositoryImpl.kt:30,34`, backup `BackupRepositoryImpl.kt:33` | ABSENT (searched: `DELETED`, `delete` in `DoseEventDao.kt`, `DoseEditDialog.kt`, `HistoryScreen.kt`) | absent-in-emivita | medtimer-better | A user who logs a wrong PRN dose in Emivita cannot remove it: `DoseEditDialog.kt:150` requires `doseValue > 0`, and switching to SKIPPED leaves a row that lowers adherence (`HistoryScreen.kt:332,340-341` = taken/(taken+skipped)). | M (status + DAO + History gate + export exclusion) | — |
| C-13 | Variable amount at take time (question 7) | `Reminder.kt:26` `variableAmount`; `VariableAmountHandler.kt:32-59` dialog prefilled with `reminder.amount` :41, updates amount *then* marks TAKEN :45-49 so stock uses the entered amount; cancel touches `processedTimestamp` :62-64; overview path `ActionsVisitor.kt:201-204` | ABSENT (searched: `variableAmount`, `askForAmount`, `ask for amount`); edit-after only: `DoseEditDialog.kt:170` `editDoseAmount` → `DoseEventRepository.kt:247-251` "doesn't touch stock" | absent-in-emivita | medtimer-better | In Emivita a dose whose real amount differs from the reminder's `dose` can only be corrected afterwards, and that correction never reaches stock, so stock drifts by the difference every time. | M | — |
| C-14 | Edit event fields | `EditEventViewModel.kt:42-48` name/amount/notes; `:60-78` reminded time **and date**; `:80-90` processed time and date; `updateEvent` :124-141 | `DoseEditDialog.kt:66-76` status/recorded time/amount; `:132` `TimeOfDayField` hour+minute only; `:158` `withHour(hour).withMinute(minute)` on the original day | divergent | medtimer-better | Emivita cannot move a record to another day, cannot rename, and has no notes (C-36); medTimer can. Emivita's PENDING ("Missed") path `:159-165` resolves with the scheduled day (§7 R46), which medTimer lacks. | S (add a `DateField`) | — |
| C-15 | Ad-hoc / additional dose | `ManualDose.kt:75-88` entries: "Custom" :78, last custom dose :90-100 (persisted :129-138), each medicine, inactive reminders as presets :246-256; free amount :140-154; **time picker on the selected day** :156-159; stock via `processStockHandling` :173-177 | `TodayScreen.kt:472-586` `LogDoseDialog`: medicine dropdown, amount default `"1"` :482 or last-used :494-498, logs at `System.currentTimeMillis()` :571; `DoseEventRepository.kt:265-288` | divergent | medtimer-better | Emivita cannot backdate an ad-hoc dose (no time/date picker; only the hour can be edited afterwards), and cannot log a dose for a medicine that is not in the list (medTimer's "Custom" name :113-119). §5 (SPEC.md:557) documents the preset simplification, not the missing time picker. | S | — |
| C-16 | Refill as an event (question 3) | `RefillProcessor.kt:46-52` `amount += refillSize`; `:54-75` builds `ReminderEvent` with `reminderType = REFILL` :71, `reminderId = -1` :70, `status = ACKNOWLEDGED` :67, amount stored only as the string `"a ➡ b"` :61, `stockBefore/After` left at `-1.0` | `DoseEventRepository.kt:295-318` `logRefill`: `status = REFILLED` :303, `doseAmount = refillAmount`, `withStockSentinels(increase)` :306 → numeric `stockBefore/After` :326-338 | parity | emivita-better | Both are event-based. medTimer's refill row keeps the change only as formatted text, so it cannot be re-computed or exported numerically; Emivita's keeps the amount and both stock values. Emivita's `logRefill` silently skips the stock write when `unit == null` (:328) — see Findings F-2. | - | — |
| C-17 | Refill entry points | settings "refill now" `StockSettingsFragment.kt:184-187`; out-of-stock notification action → `RefillProcessor.kt:22-27` and acknowledges the event :38-43 | stock screen `StockExpirationScreen.kt:201-212`; alert notification `AlertActionReceiver.kt:49-50` (cancels notification); widget `StockLevelsWidget.kt:169` | parity | equal | Emivita has one more surface (widget); medTimer additionally records the acknowledgement as an event status. | - | — |
| C-18 | Event-driven out-of-stock check (ONCE/ALWAYS) | `StockHandlingProcessor.kt:27-51` `checkForThreshold` after every decrement: skip if `amount > threshold` :29; ONCE fires only on the crossing decrement :35; ALWAYS :38 | `LowStockExpirationAlertEvaluator.kt:81-117` on every projection recompute: `isLow` :82; ONCE = `lastAlertNotifiedEpochDay == null` :94; rearm when back above :86-88; ALWAYS :93 | divergent | emivita-better | medTimer's ONCE is stateless crossing detection, so setting the amount directly below threshold in settings (no decrement) never notifies; Emivita's runs on any medicine change and persists the "already notified" state across restarts. Both use `<= threshold`. | - | — |
| C-19 | Stock decrement clamp | `MedicineDao.kt:16-22` `maxOf(0.0, amount - decreaseAmount)` in a `@Transaction` | `MedicineDao.kt:77` `MAX(0.0, quantity - :amount)`; `:84` `incrementStock` no clamp; `:120` `setStock` | parity | equal | Same floor at zero; Emivita's is a single SQL statement, medTimer's a read-modify-write inside a Room transaction. | - | — |
| C-20 | Run-out date + "add to calendar" | `StockSettingsFragment.kt:142-162` observes `stockRunOutDates`; `:164-182` `addToCalendar` via `createCalendarEventIntent` (`CalendarHelper.kt:8-19`, all-day `CalendarContract` insert) | `StockExpirationScreen.kt:150,195-198` shows run-out (hard-coded `"Runs out: …"`); ABSENT calendar (searched: `CalendarContract`, `ACTION_INSERT`) | absent-in-emivita | import-as-is | `CalendarHelper.kt:8-19` is plain Android (`Intent`, `CalendarContract`, `java.time`), no Hilt, no medTimer types. | S | No new dependency; no permission needed for `ACTION_INSERT`. |
| C-21 | Production date | `StockSettingsFragment.kt:129-131` editable; `MedicineEntity.kt:23` | `StockInfo.kt:15` column exists; `StockExpirationScreen.kt:310` only carries the old value forward; no input (searched: `productionDateEpochDay` in `feature/`) | divergent | medtimer-better | Emivita stores a field no screen can set — same "settable column nobody can set" shape as §7 R59 rule 18. | S | — |
| C-22 | Expired indicator | `Medicine.kt:39-41` `hasExpired()`; `MedicineHelper.kt:49-55` "🚫" icon; card `MedicinesScreenViewModel.kt:63-71` | ABSENT (searched: `expir`, `stock` in `MedicinesScreen.kt`, `MedicineDetailScreen.kt` — only the overflow entry :111) | absent-in-emivita | inspiration | Emivita's list card shows no stock/expiry state at all; only the widget and the stock screen do. | S | — |
| C-23 | Low-stock indicator on the medicine card | `Medicine.kt:43-45` `isOutOfStock()`; `MedicineHelper.kt:34-47` "⚠"; `StockState` `MedicineScreenState.kt:19-23` rendered in `MedicineCard.kt:133-143` | ABSENT (as C-22) | absent-in-emivita | inspiration | Same as C-22; medTimer's `MedicineCard.kt` is Compose and could be read for the pattern, but its `StockState` comes from `SimulatedRemindersRepository`, which maps to Emivita's `OccurrenceProjectionCache.stockRunOutDates`. | S | — |
| C-24 | Dose amount representation / parsing (question 9) | `Reminder.kt:17` `amount: String`, `ReminderEvent.kt:9` `amount: String`; `MedicineHelper.kt:14,64-76` `parseAmount` regex first number, locale `NumberFormat` :16 | `ReminderConfig.kt:71,78,86,137` `dose: Double`; `DoseEvent.kt:49` `doseAmount: Double`; `Medicine.kt:45` `doseUnit`; input parsed with `toDoubleOrNull()` (`DoseEditDialog.kt:95`) | divergent | emivita-better | Emivita never parses: the number and the unit are separate columns. medTimer's `parseAmount("75 mg")` → 75 works, but `NumberFormat.getNumberInstance()` in a `de`/`it` locale parses `"1.5"` as 15. Emivita's `toDoubleOrNull()` rejects `"1,5"` (Save disabled) rather than misparsing. | - | — |
| C-25 | Unit field | `Medicine.kt:16` single `unit` used for stock and display (`formatAmount` `MedicineHelper.kt:57-62`); `MedicineDataStore.kt:51,70` key `stock_unit` | `Medicine.kt:45` `doseUnit` (display) **and** `StockInfo.kt:13` `unit` (tracking flag), deliberately separate per comment :36-44 | divergent | equal | Emivita asks the user for the unit twice (medicine form and stock screen); medTimer once. The split is deliberate in Emivita because `unit` doubles as the tracking switch (C-02). | - | — |
| C-26 | Sort order — reorder algorithm (question 10) | `MedicineRepositoryImpl.kt:54-70` `move()`: first → `next - 1.0` :63, last → `prev + 1.0` :64, else midpoint :65; single `update` :69; `MedicineDao.kt:25` `ORDER BY sortOrder`, `:58-59` `getHighestSortOrder` | `MedicineDao.kt:48` `ORDER BY sortOrder ASC`; `Medicine.kt:64` column; `AddMedicineScreen.kt:106` `sortOrder = now`; **no reorder method**: `MedicineDao.kt:67-68` has only full-row `update(medicine)`, no `UPDATE … SET sortOrder`, and `MedicineRepository.kt:19-102` has no `move` | divergent | inspiration | Emivita has the column and the ordering query but no write path other than a full entity update and no UI (§5 SPEC.md:560 says "adopted verbatim" — only the column was). `move()` is 17 lines of pure list logic but is typed on `FullMedicineEntity`, so it needs more than import edits. | M (repo method + drag UI) | `sh.calvin.reorderable` is **not** in Emivita's offline Gradle cache; a hand-rolled drag or a plain up/down control is needed. |
| C-27 | Sort menu (name / creation asc / desc) | `MedicinesMenu.kt:37-45` + `sortBy` :48-54 renumbers `sortOrder = 1.0 + index` via `updateAll` | ABSENT (searched: `sortBy`, `sortedBy` in `feature/medicines/`) | absent-in-emivita | inspiration | `sortBy` is 6 lines over `medicineRepository.getAll()`/`updateAll`; Emivita's `MedicineRepository` has neither `getAll` (only `observeForProfile` :29) nor `updateAll`. | S | — |
| C-28 | Duplicate medicine (with / without reminders) (question 11) | `EditMedicineMenuProvider.kt:55-62` `create(medicine.copy(id = 0))` + `assignTags` :72-77; `:80-87` also copies reminders with `id = 0, medicineRelId = new` | ABSENT (searched: `duplicate`, `copy(id = 0` in `feature/`); SPEC §6 (SPEC.md:647-649) lists "Duplicate, Duplicate-with-reminders, Delete" as the intended overflow menu | absent-in-emivita | inspiration | Planned in Emivita's own spec, not built; `MedicineDetailScreen.kt:111-112` overflow has only Stock & Expiration and delete (:214). Emivita's copy must also re-encode `configJson` and re-arm alarms via `schedulingCoordinator`. | S–M | — |
| C-29 | Activate / deactivate all reminders (question 11) | `MedicinesMenu.kt:31-36` (all medicines) and `EditMedicineMenuProvider.kt:49-54` (one medicine) → `ReminderHelper.kt:25-35` `setRemindersActive` (resets `intervalStart` on re-activation :28-31) | ABSENT (searched: `setEnabled(` callers — only `StockExpirationScreen.kt:352,383` for alert reminders; `enabled` in `AddReminderScreen.kt`, `MedicineDetailScreen.kt` — none) | absent-in-emivita | inspiration | `Reminder.kt:52` `enabled` and `ReminderRepository.kt:188-190` `setEnabled` exist, but no screen toggles a dosing reminder, so neither "pause one" nor "pause all" is reachable. | S (per-reminder switch) / M (bulk) | — |
| C-30 | Tag data model | `TagEntity.kt:8` no unique index on `name`; `MedicineToTagEntity.kt:6-7` composite PK, index on `tagId`, **no FKs** | `Tag.kt:14` `Index("name", unique = true)`; `Tag.kt:20-33` cross-ref with both FKs `CASCADE` and indices | divergent | emivita-better | medTimer allows two tags with the same name and orphan cross-refs on medicine delete; Emivita's schema forbids both. | - | — |
| C-31 | Tag editing UI | `TagDataFromMedicine.kt:13-25` shared tag list with per-medicine check state (associate/disassociate) and **delete tag** :20-24; `addTag` :51-56 creates then associates | `AddMedicineScreen.kt:389-425` free-text add + chip remove; `:192-199` set-diff on save via `addTag`/`removeTag` (`MedicineRepository.kt:83-101`); `TagDao.kt:24` `delete(tag)` is never called from app code (searched: `tagDao.delete`, `removeTag` callers) | divergent | medtimer-better | Emivita has no picker of existing tags (each medicine retypes the name; dedupe only by exact name :14) and no way to delete a tag, so detached tags accumulate as orphans. | S | — |
| C-32 | Tag filtering scope (question 8) | global filter persisted `TagFilterStore.kt:13-19` (`filterTags`); applied to medicine list `MedicinesScreenViewModel.kt:46`, overview events + simulated reminders `OverviewViewModel.kt:92,103,108`, statistics table `StatisticsScreenViewModel.kt:106` via `TagEventFilter.kt:8-19`; exports (`Common.kt`, per inventory) | ABSENT (searched: `tag`, `filter` in `feature/history/`, `feature/today/`, `feature/medicines/MedicinesScreen.kt` — only display chips `MedicinesScreen.kt:204-207`, `take(3)`, `enabled = false`) | absent-in-emivita | inspiration | Emivita's tags are decoration only; nothing filters by them. `TagEventFilter` filters events by tag *name* snapshot (:17-18), which Emivita's events do not carry (C-33), so the Emivita version must filter medicines by cross-ref id instead. §1 (SPEC.md:58-60) excludes multi-user, medTimer's main use for tags. | M | — |
| C-33 | Tags on events / exports / notifications | `ReminderEventFactory.kt:65` copies `medicine.tags.map { it.name }` into each event; CSV export includes tags (inventory row); notifications show tags (inventory) | ABSENT: `DoseEvent.kt:37-67` has no tag field; `HistoryExporter.kt:41` header `Date,Time,Medicine,Status,Dose,Measurement` | absent-in-emivita | n/a | Follows from C-32; only worth doing if filtering lands. | - | — |
| C-34 | Medicine notes / indication / storage note (question 12) | `Medicine.kt:17` `notes`; edited in `NotesDialog.kt:14-23`; shown in overview sheet `ShowMedicineSheetDialogFragment.kt:74-76` | `Medicine.kt:70-71` `indication`, `storageNote` shown `MedicineDetailScreen.kt:145-147,150-156`; `Medicine.kt:58` `notes` **never read or written** (searched: `.notes`, `notes =` outside entity/DAO/migrations — 0 hits) | divergent | equal | Emivita's two purpose-named fields cover medTimer's one free-text field; Emivita additionally carries a dead `notes` column (Findings F-4). medTimer shows notes from the overview; Emivita only on the detail screen. | S (drop or wire `notes`) | — |
| C-35 | Per-reminder instructions + sample list (question 12) | `Reminder.kt:15` `instructions`; `advanced_reminder_settings_instructions.xml:4-13` free text + `ListPreference` from `arrays.xml:31-36` (before meal / after meal / …) via `ReminderDataStore.kt:91`; appended to notification text `NotificationStringBuilder.kt:47-50` | ABSENT (searched: `instructions`, `instruction` in `core/data/`, `feature/reminders/`); nearest is medicine-level `indication` (C-34), not shown in notifications | absent-in-emivita | inspiration | A per-dose instruction ("with food") is the one text that belongs on the notification; Emivita has no slot for it at reminder level. | M (column + migration + form + notification text) | — |
| C-36 | Event notes | `ReminderEvent.kt:19` `notes`; editable `EditEventViewModel.kt:48`; exported in CSV (inventory) | ABSENT (`DoseEvent.kt:37-67` no field; `DoseEditDialog.kt:66-76` no notes input) | absent-in-emivita | inspiration | A clinician-facing history (Emivita §1) cannot record "took late because…" on an event. | M (column + migration + dialog + CSV) | — |
| C-37 | Schema migrations | `MedicineRoomDatabase.kt:21-66` v24, 23 `AutoMigration`s; specs :77-137 (rename :77-82, `pauseDays - 1` :89-94, sortOrder backfill :96-125, indices :128-133, drop cols :135-137); `MigrationTest.kt` covers 3 of 23 | `Migrations.kt:27-271` v6, 5 hand-written (`1_2` full table rebuild :27-189, `2_3` :199-202, `3_4` doseUnit :212-214, `4_5` :241-243, `5_6` :264-271); every step has a `MigrationFrom*Test` (inventory) | divergent | equal | medTimer relies on Room auto-migration with exported schemas (`core/database/schemas/1..24.json`); Emivita writes SQL by hand with a test per step. Emivita's `@ColumnInfo(defaultValue)` note (`Reminder.kt:81-92`) shows the cost of the manual route. | - | Emivita already uses SQLCipher `SupportOpenHelperFactory`; auto-migrations would work there too. |
| C-38 | Profile entity | ABSENT (medTimer uses tags for several people, `docs/UseCases.md` per inventory) | `Profile.kt:1-37` (name :11, emergency-card fields :26-35); every medicine has `profileId` (`Medicine.kt:27`) | absent-in-medtimer | n/a | Emivita is single-user by §1 (SPEC.md:58-60) and the profile is silently created; the entity exists mainly to carry the Medical ID card (§7 R48). | - | — |
| C-39 | DoseGroup entity | ABSENT (medTimer has no grouping entity; same-minute grouping is done at notification time, area B) | `DoseGroup.kt:17-27` (default `priority = ALARM` :25); `Reminder.kt:48` `groupId` SET_NULL; read only by `effectivePriority()`; no UI (§7 R29, SPEC.md:959) | absent-in-medtimer | n/a | Schema-only in Emivita, deliberately not built (R29). | - | — |
| C-40 | Stock / expiry alerts as history events | `NotificationProcessor.kt:121-125` OUT_OF_STOCK/EXPIRATION events are always set to `ACKNOWLEDGED`; `ReminderEventFactory.kt:24-30` stores the current amount / formatted expiry date as the event's amount; `ReminderEventActions.kt:18-23` show ACK/DELETE buttons | Alerts post notifications directly (`LowStockExpirationAlertEvaluator.kt:106`) and never create a `DoseEvent`; excluded from projection so they never appear on Today (§7 R37, SPEC.md:967) | divergent | equal | medTimer's history shows *when* it warned you; Emivita's does not, by design (an alert is "a chore, not a dose", `ReminderConfig.kt:105`). | - | — |
| C-41 | Deleting a medicine — history retention | `ReminderEventEntity.kt:15` no FK; `MedicineRepositoryImpl.kt:34-36` deletes only the medicine row; events keep their `medicineName` snapshot (C-03) | `MedicineRepository.kt:61-76` `deleteAllForMedicine` :65 + FK cascade `DoseEvent.kt:20` | divergent | medtimer-better | For an app whose purpose is an adherence record (§1), deleting a finished course erases its history and the adherence it contributed to (`HistoryScreen.kt:340-341`); medTimer keeps it. Emivita's own comment (`DoseEventDao.kt:129-137`) records this as a resolved decision ("cascade"), so it is deliberate, but it contradicts the export-to-clinician goal. | M (soft-delete / archive flag) | — |

#### Spot-check list

1. **C-06 / C-08 (undo semantics and sentinel timing).** Verify that `ReminderEventFactory.kt:67-68` really is the only place medTimer writes `stockBefore` (I found no other write; `NotificationProcessor.kt:141-145` writes only `stockAfter`), and that nothing in medTimer's UI reads `stockBefore/stockAfter` for anything but display (`ReminderHelper.formatScheduledReminderString` was named in the inventory but not opened). If a reader path exists, the "display-only" claim weakens.
2. **C-29 (no per-reminder enable toggle in Emivita).** I searched `setEnabled(` callers and `enabled` in `AddReminderScreen.kt`/`MedicineDetailScreen.kt`. A verifier should grep `enabled` across all of `feature/` (e.g. a `Switch` bound to `reminder.enabled` in a list row) to confirm a dosing reminder truly cannot be paused from the UI.
3. **C-32 (tag filtering absent).** I grepped `feature/history`, `feature/today`, `feature/medicines/MedicinesScreen.kt` and `core/data/export`. A verifier should also open `feature/widget/*` and `core/notification/*` for any tag read, and confirm `TagDao.observeAll()` (`TagDao.kt:16`) has no non-test caller — that would show whether a picker was ever started.

#### Findings (Emivita defects uncovered by the comparison)

- **F-1 — Correcting a "Missed" dose to TAKEN with a different amount decrements stock by the wrong amount.** `DoseEditDialog.kt:159-170`: for a PENDING event it calls `resolve(doseEventId, chosenStatus, newResolvedAt)` (:165) *before* `editDoseAmount` (:170). `resolve` computes the sentinels from the stored `event.doseAmount` (`DoseEventRepository.kt:116`) and `editDoseAmount` explicitly never touches stock (:247-251), so the stock change is the reminder's default dose, not the amount the user just typed. medTimer's `VariableAmountHandler.kt:45-49` does it the other way round (update amount, then mark TAKEN). Fix: call `editDoseAmount` first when `wasUnresolved`.
- **F-2 — "Refill now" on a medicine without a saved unit records a REFILLED event but changes nothing.** `StockExpirationScreen.kt:201-212` calls `logRefill` with whatever is in the refill field, even when the unit field has not been saved yet (Save is a separate button :304-313). `DoseEventRepository.kt:326-328` `withStockSentinels` returns early when `stockInfo.unit == null`, *before* the `incrementStock` at :336, so the quantity is unchanged while a `REFILLED` row with `-1.0/-1.0` sentinels is inserted (:297-307). medTimer's `RefillProcessor.kt:46-47` adds `refillSize` unconditionally. Either gate the button on a saved unit or apply the increment regardless of tracking.
- **F-3 — SKIPPED→TAKEN after the hypothetical pair was clamped under-decrements.** `hypotheticalStockSentinels` (`DoseEventRepository.kt:348-354`) clamps `after` at 0 when stock was insufficient at skip time; `switchStatus` (:214-221) later decrements `before - after`, i.e. the clamped consumption, even if stock has since been refilled. Example: stock 1, dose 2, skipped → pair (1, 0); refill to 30; switch to TAKEN → 29, not 28. medTimer (`NotificationProcessor.kt:161-165`) decrements the full parsed amount at switch time. Low severity, but it is the mirror of the case R27 fixed.
- **F-4 — Dead `Medicine.notes` column.** `Medicine.kt:58` declares `notes`; `DoseEventDao.kt:34` selects it into the join; no screen reads or writes it (grep `.notes`, `notes =` across `feature/`, `ui/`: 0 hits). medTimer uses its equivalent (`ShowMedicineSheetDialogFragment.kt:74-76`). Either wire it (SPEC §6 line 647 says notes should be an inline section) or drop it in the next migration.
- **F-5 — `productionDateEpochDay` cannot be set.** `StockInfo.kt:15` / `StockExpirationScreen.kt:310` carries the old value forward, no `DateField` for it (the expiration one is at :272). medTimer edits it at `StockSettingsFragment.kt:129-131`. Same shape as R59's "settable column nobody can set".
- **F-6 — Two gate-blind literals not in the inventory's §6 list.** `DoseEditDialog.kt:174` `"Couldn't save: …"` and `TodayScreen.kt:575` `"Couldn't log dose: …"` are assigned to state before reaching a sink, exactly the pattern R59 describes.
- **F-7 — Per-reminder `enabled` is unreachable for dosing reminders** (see C-29): `Reminder.kt:52`, `ReminderRepository.kt:188-190` exist; the only callers are the alert toggles in `StockExpirationScreen.kt:352,383`. A user cannot pause a FixedTime/interval reminder without deleting it or setting `activeUntil`.

#### As-is candidates

- **C-20** — `C:/Users/User/Downloads/medTimer-main/medTimer-main/core/common/src/main/java/com/futsch1/medtimer/core/common/helpers/CalendarHelper.kt:1-19` (`createCalendarEventIntent(title, date)`) → `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita/app/src/main/java/com/hazia/emivita/core/util/CalendarIntents.kt` (new file; only the package line changes). Call it from `StockExpirationScreen.kt` next to the run-out text at :195-198 with `runOut` as the date, wrapped in the same `ActivityNotFoundException` catch medTimer uses (`StockSettingsFragment.kt:172-181`).

No other row in this area qualifies: `MedicineHelper.parseAmount/formatAmount` (`MedicineHelper.kt:57-76`) is pure but Emivita stores numeric doses and already has `formatDecimal`/`formatDoseWithUnit` (`core/format/DoseFormatting.kt:64,78`), so importing it would add a second, locale-divergent formatter; `MedicineRepositoryImpl.move()` (:54-70) and `ReminderHelper.setRemindersActive` (:25-35) are typed on medTimer entities and need more than import edits; `TagEventFilter.kt:8-19` filters by tag-name snapshots that Emivita's events do not carry.

### E.5.D — Screens — Today, Medicines, History, Settings, navigation, theme

#### Area D — screens (Overview/Today, Medicines, Statistics/History, Settings, navigation, intro, icons, colour, theme)

Paths: medTimer root `C:/Users/User/Downloads/medTimer-main/medTimer-main`, `ui/` = `feature/ui/src/main/java/com/futsch1/medtimer/feature/ui/`, `coreui/` = `core/ui/src/main/java/com/futsch1/medtimer/core/ui/`. Emivita root `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita`, `em/` = `app/src/main/java/com/hazia/emivita/`. Every line number below comes from a file opened in this session. Offline-cache facts were verified by listing `~/.gradle/caches/modules-2/files-2.1/` and unzipping the AARs (`unzip -l classes.jar`, `javap`).

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| D-01 | Medicines list: drag-to-reorder | `ui/medicine/MedicinesScreen.kt:61-63` (imports `sh.calvin.reorderable`), `:123-134` (`ReorderableItem` + `draggableHandle()`), `:149-183` (`rememberReorderableMedicines`: local shadow list, haptic on move `:161`, commit on drag end `:171-180` → `onMedicineMove(id,newIndex)`); `MedicinesFragment.kt:257-261` → `medicineRepository.move()` | ABSENT: `em/feature/medicines/MedicinesScreen.kt:144-153` is a plain `LazyColumn`/`items(medicines, key=…)`, no drag modifier; `em/core/data/dao/MedicineDao.kt:48` only reads `ORDER BY sortOrder`; no DAO/repo write of `sortOrder` (grep `sortOrder` → entity `Medicine.kt:64`, migration, `AddMedicineScreen.kt:106` only). SPEC §3 l.323-344 adopted the column and says "a future drag-reorder can slot between any two rows". | absent-in-emivita | inspiration | medTimer needs: a `ReorderableLazyListState` (`:157`), a shadow `SnapshotStateList` (`:154`), a handle modifier on the card (`MedicineCard.kt:118`), and `move(id, toPosition)`; Emivita has only the column. A hand-rolled replacement is feasible without the library: `detectDragGesturesAfterLongPress` (Emivita already uses `detectDragGestures` in `em/ui/components/ColorPickerDialog.kt:126,183`) + `LazyListState.layoutInfo.visibleItemsInfo` to find the target index + `Modifier.graphicsLayer{translationY}` on the dragged item + `Modifier.animateItem()`; ~120 lines. | M | `sh.calvin.reorderable:3.1.0` is NOT in Emivita's offline Gradle cache (`find … -iname "*reorder*"` → nothing). Hand-roll uses only `androidx.compose.foundation` already present. Haptics via `LocalHapticFeedback` (foundation). |
| D-02 | Fractional `sortOrder` move algorithm | `core/database/src/main/java/com/futsch1/medtimer/database/MedicineRepositoryImpl.kt:54-70` — `move(id,toPosition)`: remove/insert in the in-memory list `:59-60`, then `newSortOrder` = neighbour−1 at top `:63`, neighbour+1 at bottom `:64`, midpoint otherwise `:65`; single `medicineDao.update` `:69` | ABSENT: no `move`/reorder function in `em/core/data/repository/MedicineRepository.kt` (grep `sortOrder` in `em/` → no writer besides create) | absent-in-emivita | inspiration | Logic is 12 lines and type-independent; it needs an Emivita `MedicineDao.updateSortOrder(id, Double)` (or `update(medicine)`) and `observeForProfile` already orders by it (`MedicineDao.kt:48`). Not import-as-is only because it is written against `FullMedicineEntity.medicine.sortOrder` (`:56,63-65`). | S | none |
| D-03 | Swipe-to-delete with confirm dialog | `ui/medicine/MedicinesScreen.kt:186-260` — `SwipeToDismissBox` end-to-start only `:228`, dialog `:201-224`, snap back on cancel `:205,218`, red background with alpha ∝ offset `:230-244` | ABSENT on the list; delete lives only in `em/feature/medicines/MedicineDetailScreen.kt:115-116` (overflow menu) → confirm `:196-220` (cascade) | absent-in-emivita | inspiration | medTimer deletes from the list surface; Emivita requires opening the detail screen first. `SwipeToDismissBox`/`rememberSwipeToDismissBoxState` exist in material3 1.4.0 (Emivita BOM 2026.06.00 → material3 1.4.0, verified in the BOM pom). | S | none (material3 already present) |
| D-04 | Medicine card content | `ui/medicine/MedicineCard.kt:54-59` (custom bg colour + contrast-picked content colour `:187-194`), `:89-96` reminder-count plural, `:97-107` "inactive" vs joined reminder times, `:127-148` name + stock string + "⚠" + run-out date, `:153-181` `FlexBox` tags max 5 + "+n" overflow (`MAX_VISIBLE_TAGS=5` `:184`); data: `MedicinesScreenViewModel.kt:58-78` (`getReminderTimes`, `isOutOfStock`, `stockRunOutDates`, inactive flag `:77`) | `em/feature/medicines/MedicinesScreen.kt:184-215` — name `:203`, first 3 tags `:206`, icon in accent circle `:196-200`, `accent.copy(alpha=0.14f)` tint `:190`; no reminder times, no stock, no run-out, no inactive marker | divergent | medtimer-better | medTimer's card carries 5 facts Emivita's omits (times, count, stock, run-out, inactive); Emivita has the inputs (`occurrenceProjectionCache.state.stockRunOutDates`, `Reminder.enabled`) but the card reads only `MedicineWithTags`. Emivita's card does carry `Role.Button` (`:193`); medTimer's `Card(onClick)` `:70` relies on M3's default. `FlexBox`/`ExperimentalFlexBoxApi` exists in foundation-layout 1.11.3 (Emivita BOM) — verified in the AAR. | M | none |
| D-05 | Sort medicines by name / creation asc / desc | `ui/medicine/MedicinesMenu.kt:119-127` (three menu items), `:130-136` rewrites every `sortOrder` to `1.0 + index` and `updateAll` | ABSENT (searched `sortBy`, `sortedBy`, `sort` in `em/feature/medicines` → none) | absent-in-emivita | inspiration | One-shot renumbering `:133` is compatible with Emivita's fractional scheme (SPEC §3 l.323-331). Needs a `DropdownMenu` on the Medicines header (`em/…/MedicinesScreen.kt:105-125` has room next to the scan button) and a repo `updateAll`. | S | none |
| D-06 | Activate / deactivate all reminders (list + per-medicine) | `ui/medicine/MedicinesMenu.kt:113-117,138-144`; `ui/medicine/EditMedicineMenuProvider.kt:49-53,126-135` | ABSENT: `em/core/data/repository/ReminderRepository.kt:188` `setEnabled()` exists but its only callers are `em/feature/medicines/StockExpirationScreen.kt:352,383` (alert reminders); no bulk toggle, no per-reminder switch in `MedicineDetailScreen.kt:175-190` or `AddReminderScreen.kt` (grep `enabled` → only `Button(enabled=saveEnabled)` `:399`) | absent-in-emivita | inspiration | medTimer exposes "pause this medicine" in two places; Emivita's `Reminder.enabled` (`em/core/data/entity/Reminder.kt:52`) is unreachable from the UI for dose reminders. See Finding F1. | S | none |
| D-07 | Add-medicine entry flow | `ui/medicine/MedicinesFragment.kt:277-318` — name-only `MaterialAlertDialogBuilder`, then navigate to edit `:314` | `em/feature/medicines/AddMedicineScreen.kt:97-110` full form (name, barcode, icon, colour, tags, indication, storage note, dose unit, lock override, priority) | divergent | emivita-better | Emivita captures 10 fields on creation; medTimer creates with defaults and pushes the user into the edit screen. | - | none |
| D-08 | Duplicate medicine (with / without reminders, keeps tags) | `ui/medicine/EditMedicineMenuProvider.kt:55-71` (two menu items), `:73-78` `assignTags`, `:80-88` `duplicateIncludingReminders` | ABSENT (searched `duplicate`, `copy(` in `em/feature/medicines` → none) | absent-in-emivita | inspiration | Two menu items + repository copy; Emivita's `MedicineRepository.create` + `ReminderRepository.create` already exist, so it is a menu item and ~30 lines. | S | none |
| D-09 | Per-reminder enable switch + delete | `feature/ui/src/main/res/xml/advanced_reminder_settings_root.xml:8` (`reminder_active` switch); `ui/medicine/ReminderViewHolder.kt` (row menu delete); `ui/medicine/EditMedicineFragment.kt` reminder list | ABSENT: `em/feature/reminders/AddReminderScreen.kt:237-260` `EditReminderScreen` has Save/Cancel only (`ReminderFormScaffold` `:380-404`); `em/feature/medicines/MedicineDetailScreen.kt:175-190` rows are type-label-only (`reminder.type.labelRes()` `:186`); `ReminderRepository.delete()` `:213` has no UI caller (grep `reminderRepository.delete` in `em/feature` → none) | absent-in-emivita | inspiration | A dose reminder in Emivita can be neither paused nor deleted; the only exit is deleting the medicine (`MedicineDetailScreen.kt:214`). `EditReminderScreen`'s own doc `:228-234` says changing type is "delete this one, add a different one", which the UI cannot do. Finding F1. | S | none |
| D-10 | Reminder form field surface | `feature/ui/src/main/res/xml/advanced_reminder_settings_root.xml:17` `instructions` (+ `advanced_reminder_settings_instructions.xml:6,13` sample list), `:73` `variable_amount`, `:78` `automatically_taken`, `:83` per-reminder `notification_importance`, `:66` `remind_on_days` (days of month), `:60` weekdays, `:54` cyclic, `:47` `interval_start` (reminded/processed), `advanced_reminder_settings_status.xml:4-9` period start/end | `em/feature/reminders/AddReminderScreen.kt:603-735` FixedTime patterns Daily / SpecificDaysOfWeek / EveryNDays / CyclicActivePause (`:673-676` chips), `:503` autoResolve switch, `:515-516` activeFrom/Until, `:526-545` per-reminder nag, `:568` weekend opt-in, `:573` `StartBehaviorPicker`; no instructions, no variable-amount flag, no per-reminder importance (grep `instructions|variableAmount|askForAmount|priority` in `AddReminderScreen.kt` → 0) | divergent | inspiration | Emivita has three things medTimer keeps global (nag per reminder `:526`, weekend opt-in `:568`, EveryNDays); medTimer has four Emivita lacks: per-reminder instructions text, "ask for amount when taking", per-reminder importance override (Emivita only per-medicine `PriorityRow`, `AddMedicineScreen.kt:97-110`), and days-of-month. | M | none |
| D-11 | Reminder type picker | `ui/medicine/dialogs/NewReminderTypeDialog.kt:82-94` — 5 choices: time-based, continuous interval, windowed interval, out-of-stock, expiration; linked added from advanced root `advanced_reminder_settings_root.xml:20` | `em/feature/reminders/AddReminderScreen.kt:102-105` — 4 cards: FixedTime, RepeatingInterval, DailyWindow, LinkedFollowing; alert reminders are created from `em/feature/medicines/StockExpirationScreen.kt:218-262` instead (§7 R15) | divergent | equal | Same six reminder kinds reachable on both; Emivita moved the two alert types out of the picker by decision (§7 R15) and promoted linked into it. | - | none |
| D-12 | Interval editor unit handling | `coreui/Interval.kt:7-47` — `IntervalUnit{MINUTES,HOURS,DAYS,WEEKS}`, `getUnit()` picks the largest exact unit `:26-32`; `ui/medicine/editors/IntervalEditor.kt` value+unit picker | `em/feature/reminders/AddReminderScreen.kt:746-790` — single decimal "hours" text field, `formatHours(intervalMinutes)` `:1164`, stored as `(hours*60).toInt()` `:763` | divergent | medtimer-better | A weekly interval is "168" hours in Emivita and "1 week" in medTimer; Emivita's `:753-756` guard exists because 0.01 h truncates to 0 min. `Interval.kt` minus `toTranslatedString` is pure Kotlin. | S | none |
| D-13 | Medicine settings: colour, icon, importance, cannot-skip | `feature/ui/src/main/res/xml/medicine_settings.xml:6` `use_color`, `:10` `color`, `:14` `notification_importance`, `:20` `cannot_be_skipped` | `em/feature/medicines/AddMedicineScreen.kt:97-110` — `color`, `iconKey`, `priority`, `remindersLockedUntilTaken` (3-way, inherit default) | parity | equal | Same four per-medicine knobs; Emivita's lock is tri-state (`Boolean?` `:104`) where medTimer's is a bool. | - | medTimer colour picker = `hsv-alpha-color-picker` (jitpack); Emivita has its own Compose HSV picker (`em/ui/components/ColorPickerDialog.kt`, 247 lines) with ΔE conflict check — not needed. |
| D-14 | Stock settings: "add run-out date to calendar" | `ui/medicine/medicineSettings/StockSettingsFragment.kt:67` (`stock_run_out_to_calendar` → `addToCalendar()` via `CalendarContract`), `:153-156` visible only when a run-out date exists; `:69-83` production date + clear dates | `em/feature/medicines/StockExpirationScreen.kt:168-212` quantity/unit/refill/refill-now, `:218-262` low-stock + expiration alerts, `:310` `productionDateEpochDay`; ABSENT calendar intent (grep `CalendarContract` in `em/` → 0 files) | absent-in-emivita | inspiration | One `Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)` button; no permission needed. Rest of the stock screen is at parity. | S | none |
| D-15 | Overview day navigation | `ui/overview/DaySelector.kt:29` range start `now−3 years`, `:30` end `now+DEFAULT_SIMULATION_DAYS`, `:175-180` end grows with `simulatedThrough`, `:42-58` week-scroll auto-select, `:149-159` prev/next day, `:182-191` midnight rollover; `ui/overview/OverviewFragment.kt:133-136` prev/next-week buttons, `:168-175` swipe left/right = next/prev day, `:263-265` tab reselect → today, `:244-248` title "Overview – Month yyyy" | ABSENT on Today: `em/feature/today/TodayScreen.kt:158-160` window is `[startOfToday, startOfTomorrow)` only, no selector (inventory: "no day navigation"). Past browsing is on History (`em/feature/history/HistoryCalendarView.kt:93` 24 months back, `:97` `endMonth = currentMonth`). | absent-in-emivita | inspiration | medTimer's one screen browses 3 years back and ~28+ days forward with a week strip; Emivita splits "today" and "past" across two tabs and has no future-day view at all. `WeekCalendar` composable exists in Emivita's pinned `com.kizitonwose.calendar:compose:2.7.0` (verified `weekcalendar/WeekCalendarKt.class` in the AAR), so a Compose week strip needs no new dependency. | L | kizitonwose compose 2.7.0 (present). medTimer's `DaySelector` is `WeekCalendarView` (View) — not portable as code. |
| D-16 | Overview status filters (Taken/Skipped/Scheduled/Raised), persisted | `ui/overview/FilterToggleGroup.kt:13-18` map, `:35-46` persisted in `checkedFilters`, `:48-62` restore; `ui/overview/OverviewViewModel.kt:158-168` add/remove/set, `:205-237` visibility rules (empty set = show all `:210,222`) | ABSENT: Today has no filter (`TodayScreen.kt:195-234`); History's chips `HistoryScreen.kt:200-207` switch view mode, not status | absent-in-emivita | inspiration | Pure state + a `MaterialButtonToggleGroup` in medTimer; in Emivita it is a `FilterChip` row over `buildTimeline()` output (`TodayScreen.kt:252-300`) plus one DataStore string-set key. | S | none |
| D-17 | Act on a future (simulated) reminder ahead of time | `ui/overview/actions/ScheduledReminderActions.kt:18-20` — TAKEN / SKIPPED / RESCHEDULE buttons on a scheduled row; `OverviewViewModel.kt:183-188` scheduled rows built from `SimulatedReminder`s, `:131-136` widens the simulation window when day offset ≥21 | `em/feature/today/TodayScreen.kt:281-294` projected rows get `doseEventId = null` `:291`; `:334` `onResolve` returns early; comment `:89-92` "shown but not actionable" | divergent | medtimer-better | medTimer materialises the event on demand (`ReminderEventCreator.getOrCreateReminderEvent`, inventory) so a user can pre-log tonight's dose; Emivita can only act once the alarm has fired. | M | none |
| D-18 | Row tap → edit event (not swipe) | `ui/overview/ReminderViewHolder.kt:123-136` — tap on a past non-RAISED row opens `EditEventSheetDialogFragment`, otherwise `ShowMedicineSheetDialogFragment` | `em/feature/today/TodayScreen.kt:340-342` tap on TAKEN/SKIPPED → `DoseEditDialog`; `em/feature/history/HistoryScreen.kt:235,462` | parity | equal | Both use tap; SPEC §9 #3 l.1109-1110 records "a tap, not the swipe §6 originally specified". | - | none |
| D-19 | Edit-event field set | `ui/overview/EditEventViewModel.kt:42-96` — medicine name, amount, notes, status, reminded time **and date**, taken time **and date**; `:144-145` `changeTimeMinutes` + `changeInstantDate` | `em/ui/components/DoseEditDialog.kt:45-64` status radio, `:132` `TimeOfDayField` (time only; date fixed at `originalZoned.withHour(h).withMinute(m)` `:157`), `:134-141` amount; no notes, no name, no date | divergent | medtimer-better | A dose taken after midnight cannot be dated correctly in Emivita (time-only editor `:157`); medTimer edits both dates. Emivita's dialog does carry `Role.RadioButton` semantics `:50,60`. | S | none |
| D-20 | Multi-select action mode on the day list | `ui/overview/OverviewFragment.kt:274-287` long-press starts `ActionMode`, `:281-285` selects same-time events when `combineNotifications`, `:302-339` select-all + bulk button via `ActionsVisitor` | ABSENT (searched `selection`, `ActionMode`, `selected` in `em/feature/today` → none) | absent-in-emivita | inspiration | Bulk "take all 08:00 doses" is one long-press in medTimer; Emivita's node already groups same-time doses (`TodayScreen.kt:296-299`) so a per-node "take all" chip is the cheap equivalent. | M | none |
| D-21 | Re-raise / delete / reschedule a past event | `ui/overview/actions/ReminderEventActions.kt:26-40` — TAKEN/SKIPPED/RERAISE/DELETE/RESCHEDULE visibility rules; ACKNOWLEDGED for stock events `:20-23` | `em/core/data/repository/DoseEventRepository.kt` `switchStatus` (l.191 per inventory) only; no delete/re-raise/reschedule of a materialised event (grep `reraise|reschedule` in `em/feature` → none) | absent-in-emivita | inspiration | Overlaps the dose-event area; listed here because the buttons are Overview UI. RERAISE and DELETE undo stock (inventory: `undoStockOnDeleteTest`). | M | none |
| D-22 | Log manual dose: pick time and the selected day | `ui/overview/OverviewFragment.kt:225-237` passes `overviewViewModel.day` to `ManualDose`; `ui/overview/ManualDose.kt` time picker (inventory) | `em/feature/today/TodayScreen.kt:571` `logAdhocDose(medicine.id, amount, System.currentTimeMillis())` — always "now"; no time/date field in `LogDoseDialog` (grep `TimeOfDayField|DateField` in `:470-586` → 0) | divergent | medtimer-better | A dose taken two hours ago can only be logged as "now" in Emivita (then corrected via D-19, time-only). One `TimeOfDayField` + `DateField` (both exist in `em/ui/components/`) fixes it. | S | none |
| D-23 | Statistics view switch persisted | `ui/statistics/StatisticsScreen.kt:80-90` three `ViewChip`s; `StatisticsScreenViewModel.kt:62-65` seeded from `active_statistics_fragment`, `:142-146` write-through | `em/feature/history/HistoryScreen.kt:134` `var viewMode by remember { … LIST }` — reset to LIST on every entry; chips `:200-207` | divergent | medtimer-better | One DataStore key (`SettingsRepository.kt` pattern `:39-59`) + `rememberSaveable` at minimum. | S | none |
| D-24 | Analysis range selector 1/2/3/7/14/30 days, persisted | `ui/statistics/RangeDropdown.kt:320-327` `ANALYSIS_RANGES`, `:332-359` chip-anchored `ExposedDropdownMenuBox`; `StatisticsScreenViewModel.kt:148-152` persists `analysis_days`, `:82-97` recomputes charts via `snapshotFlow{analysisDays}` | `em/feature/history/HistoryScreen.kt:76` `HISTORY_LOOKBACK_DAYS = 30L` fixed; `:126` query range, `:352-366` chart window, `:300,402` summary text all hard-wired to it | absent-in-emivita | inspiration | `RangeDropdown.kt:320-359` is near-verbatim Compose (M3 1.4.0 has `ExposedDropdownMenuAnchorType`); needs 6 string resources and an icon instead of `R.drawable.caret_down_fill` `:343`. Emivita's summary plural `:398-407` already takes the day count as an argument. | S | none |
| D-25 | Charts: per-medicine stacked bars + two taken/skipped pies vs status-stacked bars with texture | `ui/statistics/charts/ChartsContent.kt:370-458` bar chart (`ColumnCartesianLayer.MergeMode.Stacked` `:411`, sync `CartesianChartModel` `:444-448`, `CartesianChartHost(chart, model, …)` `:456`, legend `:423-441`, `Zoom.Content` `:449`); pies `:238-263` (`rememberPieChart`, `PieChartHost`, `PieValueFormatter`), empty/100% fallbacks `:213-294`; tablet split `:108-124` | `em/feature/history/AdherenceChart.kt:95-187` — taken/skipped/missed `columnSeries` `:100-104`, `MergeMode.stacked()` `:156`, `CartesianChartModelProducer` `:96`, bitmap-texture fills `:116-118,191-198`, chart `contentDescription` `:140-148`, stack-order caption `:180-184` | divergent | inspiration | Vico 2.1.0 (Emivita's cached artifact) **has** `CartesianChartHost` incl. the synchronous `(CartesianChart, CartesianChartModel, …)` overload (javap on `compose-2.1.0.aar`), `VerticalAxis.ItemPlacer.step`, `HorizontalAxis.ItemPlacer.aligned`; it has **no** `PieChart`/`rememberPieChart`/`PieChartHost` (0 classes matching "pie" in compose, compose-m3 and core 2.1.0 AARs — Emivita's own comment `AdherenceChart.kt:70-74` says the same). Port of the bar chart needs 8 import rewrites (`compose.cartesian.data.*`→`core.cartesian.data.*`, `compose.cartesian.Zoom`→`core.cartesian.Zoom`, `compose.common.LegendItem`→`core.common.LegendItem`, `compose.common.component.ShapeComponent`→`core.common…`) and `MergeMode.Stacked`→`MergeMode.stacked()`; the pies must be hand-drawn (`Canvas.drawArc`) or dropped. Emivita's chart is better for screen readers and CVD (§7 R55); medTimer's answers "which medicine". | M (bars) / L (pies) | Vico 2.1.0 vs 3.2.3 — different packages, no pie in 2.x. |
| D-26 | Per-series colour assignment (custom colour wins, else palette) | `ui/statistics/charts/ChartSeriesColors.kt:12-40` — `assign(names, colorsByName)` `:14-19`, 17-entry palette `:22-40`; caller `StatisticsScreenViewModel.kt:88-93` (`.reversed().associate` so first custom wins) | ABSENT (Emivita has no per-medicine series; `AdherenceChart.kt` colours are per status `:108-110`) | absent-in-emivita | import-as-is | Pure Kotlin + `androidx.compose.ui.graphics.Color`/`toArgb`; no medTimer types. Only useful once a per-medicine series exists (D-25/D-27). Emivita medicine colours are hex strings (`parseHexColor`, `MedicinesScreen.kt:186`) so the caller maps `String→Int` once. | S | none |
| D-27 | Per-day aggregation | `ui/statistics/StatisticsProvider.kt:177-181` `aggregate(events, days)`; `:208-248` per-medicine `IntArray(days)` keyed by `normalizeMedicineName`; `:183-186` taken/skipped tallies, `days==0` = all-time `:198` | `em/feature/history/HistoryScreen.kt:351-366` `dailyAdherenceCounts` per status incl. missed=PENDING, refills excluded `:354`; `:339-343` window tallies | divergent | inspiration | medTimer groups by medicine (TAKEN only `:232`); Emivita by status incl. missed. Emivita has no all-time tally (D-52). `StatisticsProvider` uses medTimer `ReminderEvent` — not as-is. | S | none |
| D-28 | Sortable table component | `coreui/component/SortableTable.kt:66-138` generic (`columns` minWidth/fill `:44-49`, `:82-93` width fill computation, `horizontalScroll` `:96`, 3-state sort DESC→ASC→UNSORTED `:103-112`, `rememberSaveable` sort state `:75-76`, `sortRows` `:140-154`, header `:157-194`, `mergeDescendants` row `:123`) | `em/feature/history/HistoryTableView.kt:57-62` weighted columns, `:70-86` 2-state sort with column reset, `:110-137` header with `onClickLabel` + `Role.Button` `:118` and direction `contentDescription` `:129`, `:140-193` row | divergent | equal | medTimer: reusable, scrolls horizontally for narrow widths, third "unsorted" state, `rememberSaveable`. Emivita: weights (no horizontal scroll), accessible header (`:116-118`) where medTimer's header `clickable` `:169` has no role and its sort icon is `contentDescription=null` `:186`. Neither is strictly better; SPEC §5 l.555 chose hand-rolled. | S | `SortableTable.kt` uses `kotlinx.collections.immutable.ImmutableList` (`:39-41,60,68-69`) — **not in Emivita's offline cache** (`find … -iname "*immutable*"` → nothing), plus `R.drawable.sort_up/sort_down` `:184`; so near-verbatim, not as-is. |
| D-29 | Table text filter (debounced, off main thread) | `ui/statistics/table/ReminderRowFilter.kt:96-108` substring over all cells, `:118-125` `mapLatest`+`withContext(dispatcher)`; `StatisticsScreenViewModel.kt:126-138` 300 ms debounce (`:161`), `snapshotFlow` bridge; `ui/statistics/table/ReminderTable.kt:238-255` field with animated clear button | ABSENT (`HistoryTableView.kt` has no query parameter; inventory "no text filter") | absent-in-emivita | inspiration | Filter is 4 lines (`:104-107`); the debounce/dispatcher plumbing is the value. Emivita's table rows are `DoseEventWithMedicine`, so the predicate is `name.contains || statusLabel.contains`. | S | `ReminderRowFilter` signatures use `ImmutableList` (not cached) — rewrite as `List`. |
| D-30 | Tag filter applied to the table | `StatisticsScreenViewModel.kt:101-111` `tagEventFilter.filter(events, selectedTagIds, tags)`; charts explicitly not filtered (`ChartsContent.kt:80`) | ABSENT: Emivita tags are display-only (inventory "No filtering anywhere"; `MedicinesScreen.kt:204-211` renders chips) | absent-in-emivita | inspiration | Requires a global tag-filter store first (tags area); UI cost here is a chip row. | M | none |
| D-31 | Calendar range and future doses | `ui/statistics/calendar/CalendarContent.kt:69-70` defaults `pastMonths=3, futureMonths=0`; `StatisticsScreenViewModel.kt:160` `CALENDAR_PAST_MONTHS=3`; `ui/statistics/CalendarEventsProvider.kt:101-109` adds simulated reminders as `Status.SCHEDULED` `:157-165`; per-medicine XML calendar opened with `(medicineId, 1, 9)` past/future months `ui/medicine/EditMedicineSubmenus.kt:71-73`, which requests a `(futureMonths+2)*31`-day simulation window `ui/statistics/CalendarFragment.kt:248-251` | `em/feature/history/HistoryCalendarView.kt:93` 24 months back, `:97` `endMonth = currentMonth` (future unreachable by design, doc `:74-79`), `:104-108` per-visible-month query; past only; no per-medicine calendar | divergent | inspiration | Emivita browses 8× further back and queries per month (scales); medTimer shows future doses (all-medicine view only inside the current month; per-medicine view 9 months ahead). Emivita already has `occurrenceProjectionCache.state.occurrences` (`TodayScreen.kt:281`), so future dots are a merge into `byDay` `:113-117` plus lifting `endMonth`. Per-medicine calendar = a `medicineId` filter on the same view. | M | kizitonwose compose 2.7.0 has every API `CalendarContent.kt` uses (`HorizontalCalendar`, `OutDateStyle.EndOfGrid`, `firstDayOfWeekFromLocale`, `nextMonth/previousMonth` — javap on `core-2.7.0.jar`). |
| D-32 | Calendar navigation row (prev/next month, month title with year dropdown) | `ui/statistics/calendar/CalendarNavigationRow.kt:137-165` row, `:168-212` `YearMonthTitle` with `DropdownMenu` of years and bold current; enabled bounds `:150,161`; `CalendarContent.kt:124-138` scroll targets | ABSENT: `HistoryCalendarView.kt:121-135` has `DaysOfWeekTitle` only; navigation is swipe (`HorizontalCalendar`), no month/year title (grep `monthHeader|YearMonth.*Text` → none) | absent-in-emivita | inspiration | Near-verbatim Compose; replace `R.drawable.chevron_left/right/caret_down_fill` (`:151,162,189`) with `Icons.AutoMirrored.Filled.KeyboardArrowLeft/Right` and `ArrowDropDown`, and give the chevrons a `contentDescription` (medTimer passes `null` `:151,162`). | S | none |
| D-33 | Calendar day cell | `ui/statistics/calendar/DayCell.kt:44-66` animated selected/hasEvents colours, bold when events `:67`, `maxCellSize` for tablets `:40`; no semantics (`clickable` `:76` without role/label) | `em/feature/history/HistoryCalendarView.kt:212-263` — 3 status dots `:252-260`, `contentDescription` naming date + counts `:219,228-231`, `selected` semantics `:230`, `onClickLabel`+`Role.Button` `:227`; out-of-month dimmed with WCAG rationale `:239-247` | divergent | emivita-better | Emivita's cell tells a screen reader "Tuesday 9 September, 2 taken, 1 missed" (`calendarDayDescription` `:271-283`); medTimer's tells it the day number. medTimer's only extra is the animated selection (`updateTransition` `:44`). | - | none |
| D-34 | Selected-day events panel | `ui/statistics/calendar/DayEventsCard.kt:41-111` — date title, per-row divider, status icon + type icon + reminded time, taken time (gated by `showTakenTimeInOverview`, `CalendarEventsProvider.kt:139-145`), elapsed interval `:149-155`; rows are not clickable | `em/feature/history/HistoryCalendarView.kt:147-190` `CalendarSelectedDayPanel` reuses `HistoryEntryRow` (status, time, dose; tap-to-edit `:184`), loading/empty states `:155-172` | divergent | equal | medTimer shows reminder-type and elapsed-interval detail; Emivita's rows are editable and merged for a11y (`HistoryScreen.kt:462-463`). Uses `DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)` `:47-49` where Emivita hardcodes `HH:mm` (Finding F3). | S | none |
| D-35 | Adaptive tablet-landscape layouts (charts side-by-side, calendar + panel side-by-side) | `ui/statistics/charts/ChartsContent.kt:87-93` (`currentWindowAdaptiveInfo()` + `WIDTH_DP_MEDIUM_LOWER_BOUND` + `ORIENTATION_LANDSCAPE`), `:108-124`; `ui/statistics/calendar/CalendarContent.kt:73,91-95,140-156,178-193`; injectable `windowAdaptiveInfo` for tests `:73` | ABSENT (grep `WindowSizeClass|windowSizeClass|adaptive|ORIENTATION_LANDSCAPE|NavigationRail` in `em/` → 0 hits) | absent-in-emivita | inspiration | Pattern = one boolean + two arrangements per screen. `currentWindowAdaptiveInfo`/`WindowAdaptiveInfo` come from `androidx.compose.material3.adaptive:adaptive`, which is **not** in the offline cache; `androidx.window:window-core:1.5.1` (cached) has `WindowSizeClass.isWidthAtLeastBreakpoint(int)` (javap) and `material3-window-size-class:1.4.0` is cached, so the boolean can be computed from `LocalConfiguration.current.screenWidthDp ≥ 600` or `calculateWindowSizeClass`. | M | `material3-adaptive` absent from cache; `window-core 1.5.1` + `material3-window-size-class 1.4.0` present. |
| D-36 | Navigation rail at ≥600 dp | `app/src/main/java/com/futsch1/medtimer/AppNavigation.kt:44-49` `navSuiteType()` (width-only, overrides the compact-height default per comment `:38-43`), `:85-99` `NavigationSuiteScaffold` | `em/navigation/EmivitaNavHost.kt:46-73` `Scaffold(bottomBar = NavigationBar{…})` unconditionally | absent-in-emivita | inspiration | `navSuiteType` is 5 lines; `NavigationSuiteScaffold` lives in `material3-adaptive-navigation-suite` (listed in BOM 2026.06.00 at 1.4.0 but the artifact is **not** in the cache). Hand-roll: `if (wide) Row { NavigationRail(...); NavHost } else Scaffold(bottomBar)`; `NavigationRail`/`NavigationRailItem` are in core material3. | M | see D-35 |
| D-37 | Top-level tab set | `AppNavigation.kt:60-64` — 3 tabs (Overview, Medicines, Analysis); Settings via options menu `app/…/OptionsMenu.kt:147-157` | `em/navigation/EmivitaNavHost.kt:51-72` 4 tabs (Today, Medicines, History, Settings; `EmivitaRoute.kt`) | divergent | equal | Emivita promotes Settings to a tab and has no options menu. | - | none |
| D-38 | Battery-optimisation / exact-alarm warnings | `app/…/MainActivity.kt:280-293` — two dismissible `CardView`s on the home screen (`battery_warning_dismissed`, `exact_reminders_warning_shown` `:219,224`), suppressed in DEBUG | `em/feature/settings/SettingsDetailScreens.kt:399-483` `BatteryOptimizationCard`, `:485-538` `NotificationsBlockedCard`, `:324-386` `FullScreenAlarmAccessCard` — all inside Settings › Notifications, re-read on resume | divergent | equal | Emivita covers three conditions (medTimer two) but only where a user who already opened Settings sees them; medTimer surfaces them on first launch. Emivita has no exact-alarm toggle because it declares `USE_EXACT_ALARM` (inventory). | S | none |
| D-39 | Theme / colour system | `coreui/theme/MedTimerTheme.kt:99-109` dynamic colour on API 31+, else static baseline `MedTimerColors.kt:19-79`; `MaterialExpressiveTheme` `:109` (material3 hand-pinned 1.5.0-alpha23 per inventory); XML `theme` pref switches `Theme_MedTimer2` `MainActivity.kt:103-104` | `em/ui/theme/Theme.kt:21-30` 6 palettes × `ThemeMode{LIGHT,DARK,SYSTEM}`, `:405-424` `EmivitaTheme` with extended `EmivitaColors` (`:379`), `PaletteContrastTest`; no dynamic colour (grep `dynamic` in `em/ui/theme` → 0) | divergent | emivita-better | Emivita's palettes are contrast-tested and CVD-audited (§7 R49–R56, Appendix A); medTimer ships M3 placeholders (`MedTimerColors.kt:8-9` "Inferred placeholder palette"). Only medTimer extra is Material You dynamic colour (`:100-102`) — one `dynamicLightColorScheme(context)` branch if wanted. | S (dynamic toggle) | `MaterialExpressiveTheme` needs material3 1.5.0-alpha; Emivita's BOM 2026.06.00 resolves material3 **1.4.0** (BOM pom) — do not copy `MedTimerTheme.kt` as-is. |
| D-40 | Medicine icon pack | `core/ui/src/main/res/xml/icon_pack.xml:1-8…` — 54 `<icon id path=…>` entries (16×16 Bootstrap SVG paths; `grep -c "<icon$"` = 54); `coreui/MedicineIcons.kt:13-15,32-36` loaded via `com.maltaisn.icondialog` `IconPackLoader`, tinted bitmaps `:52-60`, colour persisted `icon_color` `:37-41` | `em/ui/icons/MedicineIcons.kt:42-54` 11 `Icons.Filled.*` ImageVectors keyed by string; `:61` `iconFor(key)` fallback; doc `:24-29` anticipates swapping the set without a migration | divergent | inspiration | 54 vs 11. The 54 paths are plain SVG `d` strings — convertible to `ImageVector.Builder().path{…}` (or `res/drawable` vectors) with a script, no `icondialog` dependency; key them by Bootstrap name to keep Emivita's string-key seam. | M | `com.maltaisn:icondialog:3.3.0` not in cache and unnecessary. Bootstrap Icons are MIT — carry the licence notice. |
| D-41 | Notification behaviour preferences with no Emivita UI | `feature/ui/src/main/res/xml/notification_settings.xml:32` `override_dnd`, `:44` `dismiss_notification_action` (skip/snooze/take on swipe-away), `:55` `cannot_skip_reminders`, `:63` `sticky_on_lockscreen`, `:71` `exact_reminders`; `repeat_reminders_preferences.xml:7,17,27` global repeat on/count/delay; per-channel deep links `notification_settings.xml:10,17,24` | `em/feature/settings/SettingsDetailScreens.kt:266-318` `NotificationsAlertsScreen` = 3 tier rows + 3 status cards + one app-level `ACTION_APP_NOTIFICATION_SETTINGS` `:301,540-547`; repeat/nag is per reminder (`AddReminderScreen.kt:526-545`) — divergent by design; `lock_reminders_until_taken_by_default` key exists (`SettingsRepository.kt:46,99,145`) with **no UI** (grep `setLockRemindersUntilTakenByDefault` in `em/feature` → 0) | absent-in-emivita | inspiration | Missing in Emivita: DND override, swipe-away action, sticky-on-lockscreen, per-channel deep link (`Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS`), and the already-persisted lock default (Finding F2). `exact_reminders` is n/a (`USE_EXACT_ALARM`). | M (all) / S (lock toggle) | DND override needs `ACCESS_NOTIFICATION_POLICY` (no GMS). |
| D-42 | Display preferences | `feature/ui/src/main/res/xml/display_settings.xml:8` `big_notifications`, `:14` `combine_notifications`, `:20` `use_relative_date_time`, `:25` `show_taken_time_in_overview`, `:30` `system_locale`, `:38` `theme` | Only palette + theme mode (`SettingsDetailScreens.kt:576-636` `AppearanceScreen`); ABSENT relative time (grep `relative` → comments only), show-taken-time, locale override, big/combined notifications | absent-in-emivita | inspiration | Five toggles; `show_taken_time_in_overview` is the one that changes a screen in this area (`CalendarEventsProvider.kt:143`). `system_locale` is less relevant while Emivita has one locale (§9 #19 Stage C open). | S each | none |
| D-43 | Privacy preferences | `feature/ui/src/main/res/xml/privacy_preferences.xml:6` `window_flag_secure` (applied `MainActivity.kt:109`), `:13` `app_authentication`, `:18` `hide_med_name`, `:23` `disable_widget` | `em/feature/settings/SettingsScreen.kt:132-140` biometric toggle only; FLAG_SECURE / hide-names ABSENT (grep `FLAG_SECURE|hideMedName` → 0) — SPEC §9 #11 l.1167-1168 says "remain unbuilt" | absent-in-emivita | inspiration | `window.setFlags(FLAG_SECURE)` is one line in `MainActivity`; hide-name touches notifications/widgets (other areas). `disable_widget` has no Glance equivalent need. | S (FLAG_SECURE) / M (hide names) | none |
| D-44 | Alarm sound preferences | `feature/ui/src/main/res/xml/alarm_settings.xml:7` `no_alarm_sound_when_silent`, `:11` `no_vibration_when_silent`, `:15` `alarm_ringtone` (`com.takisoft.preferencex.RingtonePreference`) | ABSENT — `SettingsDetailScreens.kt:103-106` doc: "Alarm sound/vibration aren't built yet (no audio-picker work has landed anywhere in this app)"; `AlarmsSnoozeScreen` `:108-153` is snooze minutes only | absent-in-emivita | inspiration | Ringtone pick = `RingtoneManager.ACTION_RINGTONE_PICKER` intent + one string key; silent-mode checks read `AudioManager.ringerMode` in Emivita's `AlarmActivity`. | S | `preferencex-ringtone` not needed (intent-based). |
| D-45 | Snooze settings | `feature/ui/src/main/res/xml/snooze_settings.xml:8` `snooze_duration` `ListPreference` incl. negative = "Custom…" dialog (inventory) | `em/feature/settings/SettingsDetailScreens.kt:108-153` free-number field, `SettingsRepository.kt:41,75,125` | parity | equal | Emivita's free integer field already covers medTimer's fixed list; medTimer's per-notification "custom" prompt is a notifications-area feature. | - | none |
| D-46 | Location-based snooze settings | `snooze_settings.xml:18` `location_snooze_enabled`, `:23` `home_location`, `:26` `show_home_location_on_map` | EXCLUDED §5 (row "De-googled `foss` flavor pattern … not yet needed since that feature isn't planned"; §1 no GMS) | excluded-by-design | n/a | medTimer's `full` flavour only (`play-services-location`). | - | GMS |
| D-47 | Weekend-mode settings | `feature/ui/src/main/res/xml/weekend_mode_preferences.xml:8,14,19,24` switch, days multi-select, start/end time | `em/feature/settings/SettingsDetailScreens.kt:165-260` `WeekendModeScreen` (switch `:210`, days, start/end) + per-reminder opt-in `AddReminderScreen.kt:568` (§7 R36) | parity | equal | Same four inputs; Emivita adds the opt-in gate by decision. | - | none |
| D-48 | Automatic backup settings | `PreferencesDataSource` keys `automatic_backup_interval`, `automatic_backup_directory` (inventory §2); `OptionsMenu.kt` backup items | `em/feature/settings/BackupSyncScreen.kt` (415 lines) manual + auto-sync folder (`SettingsRepository.kt:52`) | parity | equal | Interval-based vs change-debounced (§9 #9); UI-wise both expose one folder picker. Backup internals are another area. | - | none |
| D-49 | Intro / onboarding | `app/src/main/java/com/futsch1/medtimer/MedTimerAppIntro.kt:37-71` six `addSlide`s, `:73-79` asks `POST_NOTIFICATIONS`, `:80` back locked; shown once `MainActivity.kt:124` (`intro_shown`, skipped in DEBUG); debug menu "show intro" `OptionsMenu.kt:242` | EXCLUDED by decision: `em/navigation/EmivitaApp.kt:61-65` "No onboarding UI (removed, spec §9 item 1 follow-up reversal) — the first Profile row is created silently"; SPEC l.773 "the onboarding screen was deleted (§9 #1's reversal)", l.476-477 "not a reason to bring onboarding back". Permission prompt handled by `NotificationPermissionEffect` `:126-148` | excluded-by-design | n/a | Absent by decision, not omission. Emivita still asks for notifications once (`:138-147`), which is the one thing medTimer's intro does that matters. | - | `AppIntro 6.3.1` (jitpack) not in cache. |
| D-50 | Accessibility semantics in Compose | grep over medTimer's Compose files: `MedicinesScreen.kt:96`, `AppNavigation.kt:98` `testTagsAsResourceId` only; `SortableTable.kt:123` `semantics(mergeDescendants=true){}` (empty); no `Role`, `stateDescription` or `onClickLabel` anywhere; `CalendarNavigationRow.kt:151,162,189` and `RangeDropdown.kt:343` icons `contentDescription = null`; `DayCell.kt:76` plain `clickable`; `MedicineCard.kt:70` `Card(onClick)` | `em/feature/today/TodayScreen.kt:394-395` `clickable(onClickLabel, role=Role.Button)` + `semantics(mergeDescendants){stateDescription}`; `em/feature/history/HistoryScreen.kt:462-463`; `HistoryCalendarView.kt:227-231`; `HistoryTableView.kt:116-118,129,152`; `AdherenceChart.kt:148`; `DoseEditDialog.kt:50,60` `Role.RadioButton`; tests `TimelineEntryRowSemanticsTest`, `CalendarDayCellSemanticsTest` | divergent | emivita-better | medTimer's Compose code carries no Stage-B-comparable semantics; anything ported from D-01/24/28/32 must have roles, labels and merged rows added and covered by Emivita's Robolectric semantics tests (§9 #17). | - | none |
| D-51 | Statistics table row → edit event | `ui/statistics/table/ReminderTable.kt:266-277` name cell underlined + `clickable{onEditEvent(row.id)}` | `em/feature/history/HistoryTableView.kt:100-102` whole row → `onRowClick` (REFILLED inert), `:152` with `onClickLabel`/`Role.Button` | parity | equal | Emivita's whole-row target is larger and labelled; medTimer's is the name cell only. | - | none |
| D-52 | All-time taken/skipped totals | `ui/statistics/StatisticsProvider.kt:180` `total = getTakenSkippedData(events, 0)`; `:198` `days == 0` short-circuit; rendered as second pie `ChartsContent.kt:137,142` | ABSENT: `HistoryScreen.kt:379-414` summary is the 30-day window only (`HISTORY_LOOKBACK_DAYS` `:402-406`) | absent-in-emivita | inspiration | One extra `observeInRange(profileId, 0, now)` count or a DAO `COUNT GROUP BY status`. | S | none |
| D-53 | Options menu extras: version, project link, "clear all events" | `app/…/OptionsMenu.kt:159-165` version, `:167-174` app URL, `:176-193` clear events (confirm) | ABSENT (grep `VERSION_NAME|versionName` in `em/` → 0; no delete-all in `em/feature`) | absent-in-emivita | inspiration | An "About" row on `SettingsScreen.kt` is trivial; "clear all events" is a footgun and optional. | S | none |
| D-54 | Screen-state holder pattern | `ui/medicine/MedicineScreenState.kt:226-232` interface + `Mutable…` with `mutableStateOf`; `ui/statistics/StatisticsScreenState.kt:190-246` same, VM is sole writer (`StatisticsScreenViewModel.kt:37-42`); stateless screen overloads `MedicinesScreen.kt:82-89`, `StatisticsScreen.kt:59-66` are the preview/test targets | Emivita screens collect repositories inside the composable via `produceState` (`MedicinesScreen.kt:68-70`, `HistoryScreen.kt:128-130`, `TodayScreen.kt:147-168`); no ViewModels (inventory "no Hilt") | divergent | inspiration | medTimer's split lets `StatisticsScreenTest`/`ChartsContentLayoutTest` render from a plain state object; Emivita's Robolectric tests target leaf rows (`TimelineEntryRow`, `HistoryEntryRow`) because the screens are repository-bound. A refactor, not a feature. | M | none |
| D-55 | Medicine notes / info sheet from the day list | `ui/overview/ShowMedicineSheetDialogFragment.kt` (opened from `ReminderViewHolder.kt:131-132` for RAISED/scheduled rows); notes edited via `ui/medicine/dialogs/NotesDialog.kt` (`EditMedicineSubmenus.kt:49`) | `em/feature/medicines/MedicineDetailScreen.kt:144-155` shows `indication` + `storageNote`; nothing opens from a Today row that is not editable (`TodayScreen.kt:340-342` no-op for PENDING/UPCOMING) | divergent | inspiration | A tap on an upcoming dose does nothing in Emivita; medTimer shows the medicine's notes/instructions there. | S | none |
| D-56 | Empty / loading states | `MedicinesScreen.kt` none (empty `LazyColumn`); `ChartsContent.kt:379-386` empty card, `:388-396` zero series keeps axis; `DayEventsCard.kt:60-66` "no reminders" | `MedicinesScreen.kt:136-142`, `TodayScreen.kt:204-213`, `HistoryScreen.kt:212-220,254-262` explicit loading (`CircularProgressIndicator`) and empty texts; `HistoryCalendarView.kt:155-172` | divergent | emivita-better | Emivita distinguishes "loading" from "empty" on every list; medTimer's medicine list renders blank until the flow emits. | - | none |

#### Spot-check list

1. **D-25 (Vico compatibility).** Verifier: unzip `~/.gradle/caches/modules-2/files-2.1/com.patrykandpatrick.vico/compose/2.1.0/*/compose-2.1.0.aar` → `classes.jar`; confirm `com/patrykandpatrick/vico/compose/cartesian/CartesianChartHostKt.class` exists and `javap` shows the `(CartesianChart, CartesianChartModel, Modifier, VicoScrollState, VicoZoomState, …)` overload; confirm `unzip -l | grep -i pie` returns nothing in compose, compose-m3 and core 2.1.0. Then check that `ColumnCartesianLayer.MergeMode` in core 2.1.0 has no `Stacked` object (javap shows only `Companion`; `stacked()` is an extension in `compose/cartesian/layer/ColumnCartesianLayerKt`). I did all of this, but the import-path mapping list in the row is by inspection, not by compiling.
2. **D-01 (hand-rolled drag feasibility, cost M).** Verifier: confirm `sh.calvin.reorderable` is absent (`find ~/.gradle/caches/modules-2/files-2.1 -maxdepth 2 -iname "*reorder*"` → empty) and that Emivita's foundation version (BOM 2026.06.00 → foundation 1.11.3) has `Modifier.animateItem` on `LazyItemScope` and `detectDragGesturesAfterLongPress`. The ~120-line estimate is mine; a verifier who has written one should sanity-check M vs L.
3. **D-35/D-36 (adaptive libraries).** Verifier: `ls ~/.gradle/caches/modules-2/files-2.1/ | grep material3` shows only `androidx.compose.material3` (with `material3`, `material3-android`, `material3-window-size-class(-android)`) — no `androidx.compose.material3.adaptive` group and no `material3-adaptive-navigation-suite` artifact even though the BOM pom lists them. If the owner is willing to go online once, D-35/D-36 drop to S with the real libraries.

#### Findings (defects in Emivita surfaced by the comparison)

- **F1 — A dose reminder cannot be deleted or disabled from the UI.** `em/core/data/repository/ReminderRepository.kt:213` `delete()` and `:188` `setEnabled()` have no caller in `em/feature/reminders` or `em/feature/medicines/MedicineDetailScreen.kt` (only `StockExpirationScreen.kt:352,383` for alert reminders). `EditReminderScreen`'s doc (`AddReminderScreen.kt:228-234`) says changing a type means "delete this one, add a different one", which the screen does not offer; the only path is deleting the whole medicine (`MedicineDetailScreen.kt:214`). medTimer handles it via `advanced_reminder_settings_root.xml:8` (`reminder_active`) and the reminder row's delete menu (`ui/medicine/ReminderViewHolder.kt`).
- **F2 — `lockRemindersUntilTakenByDefault` is persisted but unsettable.** `em/core/data/settings/SettingsRepository.kt:46,99,145` define the key/flow/setter; `TodayScreen.kt:187-189` and the notification builder read it; no screen calls `setLockRemindersUntilTakenByDefault` (grep in `em/feature` → 0). The per-medicine tri-state "inherit default" (`AddMedicineScreen.kt:104`) therefore always inherits `false`. medTimer exposes the global in `notification_settings.xml:55` (`cannot_skip_reminders`). SPEC §9 #12 (l.1169-1183) claims it as shipped.
- **F3 — Hard-coded time patterns defeat locale formatting.** `em/feature/today/TodayScreen.kt:309` `"HH:mm"`, `em/feature/history/HistoryScreen.kt:450` `"HH:mm"`, `HistoryTableView.kt:146` `"MMM d, HH:mm"`, `AdherenceChart.kt:124` `"M/d"`, `HistoryScreen.kt:424` `"EEE, MMM d"` — a 12-hour locale still sees 24-hour times, and the pseudolocale gate (§9 #19 Stage A) cannot catch pattern strings. medTimer's Compose calendar uses `DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)` / `ofLocalizedDateTime` / `ofLocalizedDate` (`ui/statistics/calendar/DayEventsCard.kt:47-49`).
- **F4 — History table sorts by a different value than it displays.** `HistoryTableView.kt:145-146` shows `resolvedAtEpochMillis ?: scheduledEpochMillis` in the Date column, but `:80` sorts DATE by `scheduledEpochMillis` only, so a dose taken late appears out of order relative to its displayed time. medTimer's `ui/statistics/table/ReminderTablePresenter.kt:158,161` gives each time cell its own `sortValue` (`processedTimestamp` for "taken", `remindedTimestamp` for "reminded") and shows both columns.
- **F5 (minor) — History view mode is not remembered.** `HistoryScreen.kt:134` `remember { HistoryViewMode.LIST }` resets on every navigation and on process death; medTimer persists `active_statistics_fragment` (`StatisticsScreenViewModel.kt:62-65,142-146`). Not a correctness bug; listed because the analogous state in medTimer is deliberately persisted.

#### As-is candidates

- `C:/Users/User/Downloads/medTimer-main/medTimer-main/feature/ui/src/main/java/com/futsch1/medtimer/feature/ui/statistics/charts/ChartSeriesColors.kt` lines 12-40 (`object ChartSeriesColors { assign(); PALETTE }`) → `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita/app/src/main/java/com/hazia/emivita/feature/history/ChartSeriesColors.kt` (package line only; depends on `androidx.compose.ui.graphics.Color`/`toArgb` already present). Useful only with a per-medicine series (D-25/D-27).

Everything else in this area that looked copyable fails the "import edits only" bar for a named reason: `SortableTable.kt`, `ReminderRowFilter.kt`, `ReminderTablePresenter.kt` and `MedicineScreenState.kt` use `kotlinx.collections.immutable` (not in the offline cache); `MedicinesScreen.kt` needs `sh.calvin.reorderable`; `ChartsContent.kt` needs Vico 3 packages and a pie chart Vico 2.1.0 does not have; `CalendarNavigationRow.kt`, `DayCell.kt`, `RangeDropdown.kt`, `MedicineCard.kt` reference medTimer `R.drawable`/`R.string` and `MedTimerTheme`/`MedTimerPreview`; `MedTimerTheme.kt` needs material3 1.5-alpha `MaterialExpressiveTheme`; `AppNavigation.kt` needs `material3-adaptive(-navigation-suite)`; `MedicineRepositoryImpl.move()` and `StatisticsProvider.kt` are written against medTimer entity/domain types.

### E.5.E — Data — database, backup, export, preferences, security, widgets

#### Area E — data layer: database + migrations, backup/restore, export, preferences, security, widgets, DI, locale/time formatting

Paths: medTimer relative to `C:/Users/User/Downloads/medTimer-main/medTimer-main`; Emivita relative to `C:/Users/User/Downloads/Builds/Emivita_reconciled (1)/Emivita/app/src/main/java/com/hazia/emivita` unless prefixed. Every line number below was read from the file in this session.

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| E-01 | Room schema migrations: AutoMigration vs hand-written | `core/database/.../MedicineRoomDatabase.kt:21-66` (v24, 23 `AutoMigration`s), specs `:77-137` (rename 1→2, pauseDays−1 5→6, sortOrder backfill 16→17, indices 20→21, `@DeleteColumn` 21→22) | `core/data/db/EmivitaDatabase.kt:23-35` (v6, `exportSchema = true`), `:58-64` adds `Migrations.MIGRATION_1_2..5_6`; `core/data/db/Migrations.kt:27-191` (1→2 table-recreate), `:199-201`, `:212-214`, `:241-243`, `:264-271` (`ALTER TABLE ADD COLUMN`) | divergent | equal | medTimer's 18 of 23 migrations are annotation-only (`AutoMigration(from=2,to=3)` etc. at `:28-65`); Emivita writes every migration by hand incl. a 165-line 1→2 table-rebuild (`Migrations.kt:27-191`). Neither uses `fallbackToDestructiveMigration` (searched both trees). AutoMigration needs exported schema JSON for both endpoints, which Emivita now has (see E-02), so future additive columns could use it. | - | Room 2.8.4 both sides. |
| E-02 | Room schema export (`room.schemaLocation`) + migration tests | `core/database/build.gradle.kts:8-10` `room { schemaDirectory("$projectDir/schemas") }`, `:29-33` schemas added as test assets; `core/database/schemas/.../1.json..24.json`; `core/database/src/test/.../MigrationTest.kt:24-27` `MigrationTestHelper(...)`, `:41`,`:61` `runMigrationsAndValidate(testDb, N, true)` | `app/build.gradle.kts:85-90` `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`; `app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase/2.json..6.json` — **no `1.json`**; `app/src/test/.../core/data/db/MigrationFrom2To3Test.kt:17-30` explains `MigrationTestHelper` was abandoned for a Room/Robolectric driver bug and the v2 table is rebuilt from raw SQL instead | divergent | medtimer-better | medTimer's `runMigrationsAndValidate(..., validateDroppedTables=true)` (`MigrationTest.kt:41`) validates the post-migration schema against the exported JSON; Emivita's hand-built fixtures (`MigrationFrom2To3Test.kt:33-45`) assert on columns only, so a migration that drifts from the entity would pass. medTimer runs this under Robolectric 4.16.1 / `@Config(sdk=[36])` (`MigrationTest.kt:14-15`); Emivita pins Robolectric 4.14.1 / sdk 35 (`app/build.gradle.kts:404`; `MigrationFrom2To3Test.kt:31-32`). | M | Bump Robolectric 4.14.1→4.16.1 is offline-cache-dependent; verify before relying on it. |
| E-03 | Database encryption at rest | ABSENT (`core/database/.../di/DatabaseModule.kt:36` plain `Room.databaseBuilder(context, ..., "medTimer")`) | `core/data/db/EmivitaDatabase.kt:54-57` `SupportOpenHelperFactory(passphrase)`; `core/data/db/PassphraseProvider.kt:55-60` Keystore AES/GCM wrap of a 32-byte `SecureRandom` key | absent-in-medtimer | emivita-better | SPEC §5 line 568 records this as original work; medTimer has no cipher layer at all. | - | `net.zetetic:sqlcipher-android:4.17.0` (pinned, `app/build.gradle.kts:306`). |
| E-04 | Backup payload shape | `app/.../database/backup/BackupManager.kt:157-178` JSON `{medicines, events, settings}`; per-section `{version, list}` via `JSONBackup.kt:20-24` (`DatabaseContentWithVersion(databaseVersion, list)`); DTO layer `core/domain/.../backup/*.kt` mapped by `core/database/.../toBackup/BackupMappers.kt:20-164` | `core/data/backup/BackupData.kt:26-37` one `@Serializable data class` holding 7 entity lists (`profiles, medicines, tags, medicineTagCrossRefs, doseGroups, reminders, doseEvents`); entities are serialized directly (`Medicine.kt:12`, `DoseEvent.kt:12`, etc. carry `@Serializable`) | divergent | equal | medTimer keeps a DTO per entity (`MedicineBackup.kt:3-18`, `ReminderBackup.kt:5-31`, `ReminderEventBackup.kt:6-23`, `TagBackup.kt:3-5`) and a mapper both ways; Emivita has zero mapping code but its JSON is welded to entity property names (a Kotlin rename breaks every old file unless `@SerialName` is added — none present, searched `@SerialName`). | - | Gson 2.14 vs kotlinx-serialization 1.8.1. |
| E-05 | Backup forward/backward compatibility (Q1) | `JSONBackup.kt:26-42` `parseBackup`: pass 1 with `setVersion(0.0)` + `FullDeserialize` (`:64-83`, throws `JsonParseException("Missing field…")` at `:72-73` only for fields still `null` after parse), pass 2 `GsonBuilder().setVersion(content.version)` (`:33`); no `@Since` anywhere (searched both `@Since` and `setVersion`, only `:28,:33`); legacy single-list fallback `BackupManager.kt:218-224` | `feature/settings/BackupSyncScreen.kt:161` `Json.decodeFromString(BackupData.serializer(), readResult.json)` — the **default** `Json` (no `ignoreUnknownKeys`, no `coerceInputValues`, `encodeDefaults=false`); `BackupData.kt:17` `BACKUP_SCHEMA_VERSION = 1`, `:28` `schemaVersion = BACKUP_SCHEMA_VERSION` — KDoc `:20-24` "Not yet consulted by restore"; export at `BackupSyncScreen.kt:105` and `AutoSyncManager.kt:82` also use default `Json` | divergent | medtimer-better | **Newer→older (file has an extra field):** medTimer Gson ignores unknown keys → restores; Emivita default `Json` throws `SerializationException: Unknown key` → whole restore fails with "Restore failed: …" (`BackupSyncScreen.kt:182-183`). **Older→newer (file lacks a field):** medTimer fills Kotlin defaults except the four null-default fields `MedicineBackup.name/refillSizes/unit` (`MedicineBackup.kt:4,10,11`) and `TagBackup.name` (`:4`) which `FullDeserialize` rejects; Emivita fills defaults for fields that have one, throws `MissingFieldException` for the rest (e.g. `DoseEvent.medicineId/scheduledEpochMillis/doseAmount/createdAtEpochMillis`, `DoseEvent.kt:44-49,68`). **Version gate:** medTimer writes the Room version (`BackupRepositoryImpl.kt:25-26`) but never branches on it; Emivita never checks `schemaVersion` and, because `encodeDefaults=false`, never even writes it (the field equals its default). | S | Fix is one `Json { ignoreUnknownKeys = true; encodeDefaults = true }` shared instance + a `require(data.schemaVersion <= BACKUP_SCHEMA_VERSION)` gate; no new dependency. |
| E-06 | Restore semantics: atomicity, id preservation, post-restore re-arm | `BackupManager.kt:239-262` `restoreCombinedBackup` runs medicines then events then settings, each `clear*` + insert without a DB transaction (`JSONMedicineBackup.kt:32-45` `clearMedicineData()` then per-medicine inserts; `BackupRepositoryImpl.kt:36-40`); `ReminderBackup.toEntity()` keeps `reminderId` (`BackupMappers.kt:84-86`) but `insertReminders` overwrites `createdTimestamp` with now (`BackupRepositoryImpl.kt:46-55`); `medicineId` is regenerated (`:42-44`) | `core/data/backup/BackupRepository.kt:50-77` `database.withTransaction { delete in FK order :52-58; restoreAll in reverse :60-66 }` with `OnConflictStrategy.REPLACE` (`dao/MedicineDao.kt:130-131`, `dao/DoseEventDao.kt:150-151`) so every id round-trips; then `schedulingCoordinator.rescheduleAll()` `:75` and `occurrenceProjectionCache.invalidate()` `:76` | divergent | emivita-better | A failure half-way through medTimer's restore leaves the DB with medicines cleared and events intact (no transaction around `:243-257`); Emivita's is all-or-nothing and explicitly re-arms AlarmManager. medTimer also silently rewrites every reminder's `createdTimestamp` on restore (`BackupRepositoryImpl.kt:47`), which feeds `cycleStartDay`-relative logic elsewhere. | - | |
| E-07 | Restore confirmation before wholesale replace | `BackupManager.kt:79-88` `MaterialAlertDialogBuilder` with `R.string.restore_start` ("This will replace all medicine settings and/or reminder events. Continue?", `core/ui/.../strings.xml:128`) before the file picker opens | `BackupSyncScreen.kt:256-265` static caption `backup_sync_replaces_everything_currently_on_this_device` then `OutlinedButton` → `openDocumentLauncher.launch(...)` `:264`; `:189-191` picker result → `attemptRestore(uri, null)` → `restoreSnapshot` `:163` with no confirm step | absent-in-emivita | medtimer-better | Emivita: choosing a file in the picker is the point of no return (unless the zip is encrypted, in which case the password dialog is an accidental second chance, `:168-176`); medTimer: modal confirm first. | S | Compose `AlertDialog` already used in the same file (`:311`, `:328`, `:372`). |
| E-08 | Backup delivery: share sheet vs SAF create | `BackupManager.kt:193-202` writes `context.cacheDir/MedTimer_Backup_<stamp>.json` (`app/.../helpers/ExportBackupPath.kt:20-27`) then `FileHelper.shareFile` (`core/common/.../FileHelper.kt:64-77`, `FileProvider` authority `:65`, `ACTION_SEND` chooser); the cache file is never deleted | `BackupSyncScreen.kt:93-129` `ActivityResultContracts.CreateDocument("application/zip")`, temp file in cache `:98`, copied to the SAF stream `:112-114`, deleted in `finally` `:126` | divergent | emivita-better | medTimer leaves the plaintext backup JSON in `cacheDir` after sharing (no delete after `:196`); Emivita wipes its temp and the (optionally AES) zip lands only where the user pointed. medTimer's SAF-only path exists just for the automatic backup (`:331-347`). | - | medTimer needs a `<provider>` (`app/src/main/AndroidManifest.xml:82-83`); Emivita declares none (searched `FileProvider`). |
| E-09 | Backup encryption / password | ABSENT (plain JSON; `FileHelper.kt:19` comment "Unencrypted file is intended here") | `core/data/backup/BackupZipWriter.kt:32-46` zip4j AES-256 (`KEY_STRENGTH_256` `:40`), `:50-74` read with `PasswordRequired / WrongPassword / Corrupt` outcomes; manual export password optional with explicit "export anyway" (`BackupSyncScreen.kt:87-89`), auto-sync always encrypted (`AutoSyncManager.kt:60`) | absent-in-medtimer | emivita-better | SPEC §5 line 556 and §9 #9 (`docs/SPEC.md:1148-1156`) record this as original because medTimer's DB is unencrypted. Wrong-password detection relies on the exception message text `"Wrong Password"` (`BackupZipWriter.kt:68`) — brittle across zip4j versions. | - | `net.lingala.zip4j:zip4j:2.11.5` (Apache-2.0). |
| E-10 | Automatic backup (interval → directory) vs auto-sync on change (Q4) | `BackupManager.kt:273-295` `autoBackup()`: `NEVER/DAILY/WEEKLY/MONTHLY` compared as `lastBackup.plusDays(1/7/30) <= LocalDate.now()` (`:281-283`), invoked only from `app/.../MainActivity.kt:321` in `onResume`; `performAutoBackup` `:297-329` writes a **new** date-stamped file each run (`ExportBackupPath.kt:20-27`, `saveToDirectory` `:331-347` `createFile`), `last_automatic_backup` stored via `PersistentDataDataSource.kt:78`; prefs `automatic_backup_interval/_directory` (`PreferencesDataSource.kt:266-267`) | `core/data/backup/AutoSyncManager.kt:18` `AUTO_SYNC_DEBOUNCE_MILLIS = 12_000`, `:42` conflated channel, `:44-52` loop `delay → writeIfConfigured`, `:54-56` `notifyChanged()`; called from 17 repository mutation sites (`DoseEventRepository.kt:157,233,242,250,286,316`, `MedicineRepository.kt:38,47,75,95,100`, `ReminderRepository.kt:85,128,157,167,173,191,233`); single file `emivita_autosync.zip` (`:19`) replaced via `.new` staging + rename (`:95-107`) | divergent | emivita-better | **Data-loss window:** Emivita ≤ 12 s after the last change (plus the process must survive the delay); medTimer up to the interval *plus* the time until the app is next foregrounded (`onResume` is the only trigger). **Write cost:** Emivita serialises + AES-zips the whole DB once per change burst (a day of 6 doses = 6 writes); medTimer at most one write per interval but never rotates old files (a year of DAILY = 365 files in the folder). Emivita's `ProfileRepository.update` (`core/data/repository/ProfileRepository.kt:26`) is **not** among the trigger sites — see Findings F2. | - | medTimer `androidx.documentfile:1.1.0`; Emivita `1.0.1`. |
| E-11 | Auto-sync passphrase storage | ABSENT (nothing to store; `automatic_backup_directory` is a plain string pref `PreferencesDataSource.kt:60-62`) | `core/data/backup/AutoSyncPassphraseStore.kt:45-55` `get()` → `CharArray`, `:60-71` `save`, `:77-79` `clear`, `:111-126` Keystore AES/GCM wrap key (no user-auth binding), separate alias from the DB key (`:33` vs `PassphraseProvider.kt:57`) | absent-in-medtimer | emivita-better | Distinct blast radius for the exported artefact vs the DB secret (`:22-24` rationale); caller wipes the array in `finally` (`AutoSyncManager.kt:67-71`). | - | |
| E-12 | Persistable SAF directory permission | `BackupManager.kt:134-142` `takePersistableUriPermission(READ or WRITE)` | `BackupSyncScreen.kt:205-214` `takePersistableUriPermission(READ or WRITE)` `:207-210` | parity | equal | Identical call; Emivita additionally checks `treeDoc.canWrite()` before each write (`AutoSyncManager.kt:80`) and silently skips if revoked, medTimer catches and shows `backup_failed` (`:322-327`, `:343-346`). | - | |
| E-13 | Settings included in backup | `app/.../backup/JSONSettingsBackup.kt:16-19` `createBackup` from `UserPreferences.toSettingsBackup()` (`core/datastore/.../BackupMapper.kt:12-45`, 32 fields incl. home location), `:21-35` `applyBackup` → `SettingsBackup.applyTo` (`BackupMapper.kt:47-94`); DTO `core/domain/.../backup/SettingsBackup.kt:3-36` | ABSENT (searched `BackupData` fields `BackupData.kt:26-37` — entity lists only; `SettingsRepository` keys `core/data/settings/SettingsRepository.kt:39-59` never serialised) | absent-in-emivita | medtimer-better | After an Emivita restore on a new phone the user re-picks palette, theme, snooze, weekend days/times, biometric lock, lock-default (11 keys, `SettingsRepository.kt:39-59`); medTimer restores all of them. Note medTimer restores `appAuthentication`/`useSecureWindow` too (`BackupMapper.kt:78-79`) — Emivita would need to decide whether `biometric_lock_enabled` and `auto_sync_folder_uri` (device-specific) should travel. | M | Add a `settings: SettingsBackup? = null` field to `BackupData` (nullable + default keeps old files readable once E-05 is fixed). |
| E-14 | Backup section selection (medicines / events) | `BackupManager.kt:98-112` multi-choice dialog `backup_types`, `:159-178` writes only the checked sections; restore accepts either (`:243-254`) | ABSENT (`exportSnapshot` `BackupRepository.kt:32-41` is all-or-nothing) | absent-in-emivita | n/a | Low value for Emivita: with `restoreSnapshot` being a wholesale replace inside one transaction (`:50-67`), a partial file would have to be given merge semantics first. | - | |
| E-15 | Legacy backup format parsing | `BackupManager.kt:218-224` falls back to parsing the whole file as a bare medicine list, then as a bare event list | ABSENT — only one format has ever shipped (`BackupData.kt:20-24`) | absent-in-emivita | n/a | Nothing to port; becomes relevant the first time `BACKUP_SCHEMA_VERSION` is bumped, at which point E-05's tolerant `Json` is the mechanism, not this fallback. | - | |
| E-16 | `BackupFieldParityTest` — reflection guard entity ⊆ DTO (Q2) | `core/database/src/test/.../toBackup/BackupFieldParityTest.kt:26-27` `fieldNames(clazz) = declaredFields.filterNot{isSynthetic}.map{name}`, `:29-41` asserts `fieldNames(entity) − fieldNames(backup) − excluded == ∅`; four tests `:43-89` pair `ReminderEventEntity↔ReminderEventBackup` (excluded `reminderEventId, notificationId, remainingRepeats, stockHandled, askForAmount` `:48-54`), `ReminderEntity↔ReminderBackup` (`medicineRelId` `:63-65`), `MedicineEntity↔MedicineBackup` (`medicineId` `:74-76`), `TagEntity↔TagBackup` (`tagId` `:85-87`) | ABSENT (inventory row 52 "no field-parity guard"); the entity/DTO pairs would be `Medicine↔Medicine`, `Reminder↔Reminder`, `DoseEvent↔DoseEvent`, `Tag↔Tag`, `Profile↔Profile`, `DoseGroup↔DoseGroup`, `MedicineTagCrossRef↔MedicineTagCrossRef` because `BackupData.kt:26-37` serialises the entities themselves | absent-in-emivita | inspiration | The test *would compile* against Emivita (it is pure JVM reflection + `kotlin.test`), but every call would be `assertBackupCoversEntity(X::class.java, X::class.java, …)` and pass vacuously — the bug class it guards (entity gains a field, DTO forgets it) cannot occur without a DTO layer. The analogous Emivita risk is a **new `@Entity` table** forgotten in `BackupData`: guard it with `EmivitaDatabase::class.java.getAnnotation(Database::class.java).entities` ⊆ {generic element types of `BackupData::class.java.declaredFields`}. Not `import-as-is`: only the 16-line helper `:26-41` is reusable, the assertions are medTimer-specific. | S | JVM-only, no Robolectric. |
| E-17 | `JSONBackupFuzzTest` — garbage-input parser test (Q3) | `app/src/test/.../JSONBackupFuzzTest.kt:19-27` seven inputs (`""`, `{}`, `[]`, `null`, `{"invalid":}`, `normal string`, `{"medicines":[]}`), `:31-36` runs both `JSONMedicineBackup` and `JSONReminderEventBackup` through `parseBackup` then `applyBackup` against a Mockito mock (`:32`); no `-Dfuzzing` gate exists in the file (inventory claim not borne out) | ABSENT (searched `test/` for `decodeFromString(BackupData`, `BackupZipWriter.read`) | absent-in-emivita | inspiration | Portable idea, S: parameterised `Json.decodeFromString(BackupData.serializer(), s)` must throw only `SerializationException` (never `NPE`/`IllegalState`), and `BackupZipWriter.read(File(garbage), null)` must return `Corrupt` not throw (`BackupZipWriter.kt:57-73` already promises this — untested). medTimer's file itself is not `import-as-is` (Mockito + `JSONBackup<T>` types). | S | Emivita test stack is mockk (`app/build.gradle.kts:408`); no Mockito in its cache. |
| E-18 | Backup round-trip unit tests | `app/src/test/.../JSONMedicineBackupUnitTest.kt:20-136` create→parse→compare, `:405-416` and `:432-442` missing-field/empty cases; `JSONSettingsBackupUnitTest.kt:107-125` invalid/empty/no-home-location | ABSENT (inventory row 52 test column "none"; `app/src/test/.../core/data/backup/` does not exist) | absent-in-emivita | inspiration | Emivita's whole backup/restore path — the disaster-recovery feature — has zero automated coverage; `DoseEventRepositoryTest` already shows the in-memory-Room pattern (`app/build.gradle.kts:404-406`) needed for `exportSnapshot → encode → decode → restoreSnapshot → getAllOnce` equality. | M | Robolectric-backed Room, already configured. |
| E-19 | Backup content excludes soft-deleted rows | `core/database/.../BackupRepositoryImpl.kt:32-34` `getAllLimited(0L, statusValuesWithoutDelete)` (`ReminderEventEntity.kt:10`) | n/a — `DoseStatus` has no DELETED value (`core/data/entity/DoseEvent.kt:10`), `doseEventDao.getAllOnce()` (`BackupRepository.kt:40`) | absent-in-emivita | n/a | Emivita deletes hard; nothing to filter. | - | |
| E-20 | CSV event/history export: columns, separator, escaping (Q7) | `feature/ui/.../exporters/CSVEventExport.kt:35-67`: `;`-joined header (`:40`) from `helpers/TableHelper.kt:7-17` (9 localized columns: reminded, name, dosage, taken, tags, interval, notes, reminded ISO-8601, taken ISO-8601), rows via `String.format("%s;%s;…")` `:42-59` with **no quoting/escaping**; rows = all events minus deleted/acknowledged (`app/.../OptionsMenu.kt:285-286`) | `core/data/export/HistoryExporter.kt:35-54`: `,`-separated, fixed English header `Date,Time,Medicine,Status,Dose,Measurement` (`:41`), RFC-4180 quoting `csvEscape` `:56-61`, status via `csvStatusLabel` (`core/format/DoseFormatting.kt:44-49`, English on purpose); rows = the History screen's 30-day window (`feature/history/HistoryScreen.kt:76,126,139`) | divergent | inspiration | Emivita's escaping is correct where medTimer's breaks on a `;` or newline in a medicine name/note; but medTimer exports both timestamps in full (`CSVEventExport.kt:44-58`) and *all* history, while Emivita emits scheduled **date** + resolved **time** in one row (`HistoryExporter.kt:43-47`, see Findings F8), no ISO column, and only 30 days. Adopt: two ISO-8601 columns (scheduled, resolved) and a range picker. | S | Neither needs a library. |
| E-21 | Export respects tag filter | `OptionsMenu.kt:275-295` toast `tag_filter_active` (`:276-284`) then `tagFilterViewModel.filterEvents(...)` (`:285-286`); same for medicines `:297-317` | ABSENT — no tag filtering exists anywhere (inventory row 37; `HistoryScreen.kt:139` `exportRows = events ?: emptyList()`) | absent-in-emivita | n/a | Depends on a filter feature that is another area's row; nothing to port in isolation. | - | |
| E-22 | PDF event/history export: library vs native | `feature/ui/.../exporters/PDFEventExport.kt:43-63` SimplyPDF table, 4 of the 9 columns (`:49`, `:77-90`), A4 via `Common.kt:45-52` | `HistoryExporter.kt:71-132` native `android.graphics.pdf.PdfDocument`, 595×842 pt (`:77-78`), 5 columns (`:86-90`), manual pagination `:97-119`, medicine name truncated `take(28)` `:125`, translated headers `:104-113` | excluded-by-design | equal | SPEC §5 line 557: "library choice diverges deliberately … zero new dependency". Emivita's hand-rolled column x-positions overflow silently for long names (hence `take(28)`); medTimer's table wraps but also drops 5 columns. | - | medTimer `simply-pdf` (jitpack). |
| E-23 | Medicine/reminder list export (CSV + PDF) | `exporters/CSVMedicineExport.kt:36-88` (`name;dosage;time`, reminder summary via `ReminderSummaryFormatter`), `exporters/PDFMedicineExport.kt:35-70` (bullet list per medicine) | ABSENT (only history is exportable; `HistoryScreen.kt:145-161`; `EmergencyCardExporter` lists medicines but as a card, not a schedule) | absent-in-emivita | inspiration | A "my medication schedule" sheet for a doctor is the one export Emivita lacks; the nearest existing code is `core/data/export/EmergencyCardExporter.kt` `buildLines(profile, medicines, labels)`. | M | Needs a per-reminder summary formatter (medTimer's `ReminderSummaryFormatter` is another area). |
| E-24 | Emergency card / Medical ID export | ABSENT (no profile/medical-ID concept; searched `allerg`, `Profile`, `emergency`) | `core/data/export/EmergencyCardExporter.kt` (inventory row 51: `buildLines`, `MAX_MEDICINES = 22`, 10 tests); `feature/settings/MedicalIdScreen.kt` | absent-in-medtimer | emivita-better | Original Emivita feature (§9 #18). | - | |
| E-25 | Export file delivery + null-stream handling | `OptionsMenu.kt:343-355` writes to `cacheDir`, `FileHelper.shareFile` chooser, `ExporterException` → toast `export_failed` (`:348-353`); `CSVEventExport.kt:64-66` wraps `IOException` | `HistoryScreen.kt:145-152` / `:154-161` `CreateDocument` then `context.contentResolver.openOutputStream(uri)?.use { … }` (`:150`, `:159`) — a null stream is a silent no-op with no status either way | divergent | medtimer-better | Same shape as SPEC §7 R23's fixed bug: `BackupSyncScreen.kt:107-112` now does `?: error("Couldn't open …")`, `HistoryScreen.kt:150,159` still does `?.use`. See Findings F3. | S | |
| E-26 | `FileHelper` (SAF read / share) | `core/common/.../helpers/FileHelper.kt:18-77` object: `saveToFile` `:20-30`, `readFromUri` `:32-47` (line-based, normalises to `\n`), `shareFile` `:64-77` | ABSENT as a helper — SAF I/O is inlined at each call site (`BackupSyncScreen.kt:112-114`, `:152-154`; `AutoSyncManager.kt:97-107`; `HistoryScreen.kt:150,159`) | absent-in-emivita | inspiration | Nearly pure (`android.*` + `LogTags.BACKUP` medTimer-only at `:26,:45`); Emivita would need a `<provider>` for `shareFile`, which it has no other use for. Only `readFromUri`-style centralisation is worth copying. | S | |
| E-27 | Preferences store | `core/datastore/.../PreferencesDataSource.kt:26-41` SharedPreferences + `PreferenceDataStore` + `callbackFlow → StateFlow<UserPreferences>`; 30 keys `:240-269`, parsed in `getSettings` `:110-237` (e.g. `automatic_backup_interval` accepts both ordinal and word form `:217-226`) | `core/data/settings/SettingsRepository.kt:21` `preferencesDataStore(name = "emivita_settings")`, 11 typed keys `:39-59`, one `Flow` per key `:62-115`, `suspend fun set*` `:117-165` | divergent | equal | StateFlow snapshot vs per-key flows; medTimer's `.value` reads are synchronous everywhere (widgets, formatter), Emivita's need `first()`/`collectAsState` (`AppLockGate.kt:61` handles the null-until-loaded window explicitly). | - | medTimer `preference-ktx`; Emivita `datastore-preferences:1.1.1`. |
| E-28 | Preference keys with no UI (Q10) | none: every `PreferencesDataSource` key `:240-269` has an XML preference entry (grep of `feature/ui/src/main/res/xml` per key), and `automatic_backup_interval/_directory` are set from the `BackupManager` dialog `:114-142` | `SettingsRepository.kt:46` `lock_reminders_until_taken_by_default`, setter `:145-147` — read at `feature/today/TodayScreen.kt:188`, no setter call anywhere (searched `setLockRemindersUntilTakenByDefault`: definition only) | absent-in-emivita | medtimer-better | The medTimer analogue `cannot_skip_reminders` is a visible switch; Emivita's default is unreachable, so `Medicine.remindersLockedUntilTaken == null` ("inherit") always inherits `false`. | S | One `TOGGLE` row in `SettingsScreen.kt` (`RowKind.TOGGLE` exists, inventory row 58). |
| E-29 | Persistent app state keys | `core/datastore/.../PersistentDataDataSource.kt:184-197` (14 keys across default + `MedTimerPreferences` file: intro shown, warnings dismissed, `last_automatic_backup` `:191`, notification id counter, filters) | `SettingsRepository.kt:59` `notification_permission_requested`; other state lives in DB (`Profile`) or Keystore-wrapped prefs (`emivita_secure_prefs`, `emivita_autosync_prefs`) | divergent | equal | Different needs; nothing missing that Emivita's features require. | - | |
| E-30 | Biometric prompt: Keystore-bound `CryptoObject` (Q5) | `app/.../Biometrics.kt:112-136` AES/CBC key `MedTimerBiometricKey` with `setUserAuthenticationRequired(true)` `:127` and `setInvalidatedByBiometricEnrollment(true)` `:128`; `:176-192` cipher init → `CryptoObject`; `:95-103` `doFinal("medtimer_auth")` proves the auth | `core/security/BiometricAuthenticator.kt:37-65` plain `BiometricPrompt.authenticate(promptInfo)` `:64`, no `CryptoObject` (searched `CryptoObject`, `setUserAuthenticationRequired`: none) | absent-in-emivita | inspiration | medTimer's own comment `:158` concedes "no user data is encrypted using it" — the key exists only so that an enrolment change is *noticed*. Emivita instead re-reads `canAuthenticate` on every lock cycle (`feature/lock/AppLockGate.kt:83-89`). Worth adopting only if Emivita ever wants "new fingerprint added → must re-enable lock". | S | `androidx.biometric:1.1.0` both. |
| E-31 | `KeyPermanentlyInvalidatedException` handling (Q5) | `Biometrics.kt:153-163` catches it, deletes the alias, retries — **but** `generateCryptoObject` `:176-192` already catches `Exception` at `:184` (the invalidation is thrown by `cipher.init` `:182`), returns null, and `:171-173` falls through to `authenticateWithDeviceCredentials()`; the `:157-162` branch is unreachable and the stale key is never regenerated | n/a — no key to invalidate; after an enrolment change `availability()` (`BiometricAuthenticator.kt:22-33`) still reports `AVAILABLE` and the prompt simply uses the new biometric | absent-in-emivita | n/a | Effective medTimer behaviour after enrolment change: silent permanent downgrade to the device-credential prompt (which on API 30+ still offers biometrics, `:64-68`). Not a defect on Emivita's side; medTimer's dead branch is worth knowing before "near-verbatim" porting (§5 line 565). | - | |
| E-32 | `DEVICE_CREDENTIAL` fallback and fail-closed vs fail-open | `Biometrics.kt:51-62` `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on API ≥30, `:194-201` PIN/pattern prompt when no strong biometric; failure → `MainActivity.kt:141` `finish()`; gate `:143` `appAuthentication && hasBiometrics()` where `hasBiometrics` = `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` (`core/common/.../ContextExtensions.kt:18-21`) | `BiometricAuthenticator.kt:60` `setAllowedAuthenticators(BIOMETRIC_STRONG)` only, `:61` negative button; `AppLockGate.kt:89-93` renders content unlocked when availability ≠ `AVAILABLE` (doc `:46-50` "Fails open, deliberately") | absent-in-emivita | medtimer-better | Concrete case: device has a PIN but the user removed all fingerprints — medTimer still challenges with the PIN; Emivita shows the app with no challenge. Emivita's minSdk 30 (`app/build.gradle.kts`) means medTimer's API 28-29 branch (`:56-61`, `:69-70`) is unnecessary; the change is one flag plus dropping the negative button. | S | SPEC §9 #11 (`docs/SPEC.md:1161-1165`) scopes "biometric-only", so this is a scope decision, cite before changing. |
| E-33 | Re-lock on background | `MainActivity.kt:117-119` authenticates once in `onCreate`; no lifecycle re-lock (searched `onStop`/`onPause` for `authenticate`: none) | `AppLockGate.kt:95-101` `ON_STOP → authenticated = false`, `:108-110` re-prompts on next composition | absent-in-medtimer | emivita-better | Switching away and back re-challenges in Emivita, not in medTimer. | - | |
| E-34 | `FLAG_SECURE` (screenshot/recents block) (Q6) | `MainActivity.kt:108-110` `window.setFlags(FLAG_SECURE, FLAG_SECURE)` when `useSecureWindow`; `feature/ui/.../preferences/PrivacyPreferencesFragment.kt:32-33` toggles live; key `window_flag_secure` (`PreferencesDataSource.kt:251`) | ABSENT (searched `FLAG_SECURE`: 0 hits) — SPEC §9 #11 `docs/SPEC.md:1166-1168` "no `FLAG_SECURE` or name-masking code exists anywhere" | absent-in-emivita | medtimer-better | Acknowledged gap. Two lines in `MainActivity.onCreate` + a DataStore key + a Settings toggle. | S | |
| E-35 | Hide medicine name in notifications (`hide_med_name`) (Q6) | `core/common/.../helpers/MedicineHelper.kt:22-31` `getMedicineName(medicine, notification, prefs)` → first char + `*`×(len−1) when `hideMedicineName && notification`; key `PreferencesDataSource.kt:250` | ABSENT (searched `hideMed`, `hide_med`, `maskName`: 0 hits) — SPEC §9 #11 `docs/SPEC.md:1166-1168` | absent-in-emivita | medtimer-better | Emivita's `ReminderNotificationBuilder.contentText()` (`core/notification/ReminderNotificationBuilder.kt:89`) interpolates the raw name. The masking function is 4 lines of pure Kotlin. | S | |
| E-36 | Disable widgets for privacy (`disable_widget`) | `feature/reminders/.../widgets/NextRemindersWidgetProvider.kt:19-23` substitutes a one-line `widget_disabled_privacy` provider (`core/ui/.../strings.xml:70`); same in `LatestRemindersWidgetProvider.kt:21`; key `PreferencesDataSource.kt:265` | ABSENT (searched `disableWidget`, `widget_disabled`, `privacy` in `feature/widget`: 0 hits) | absent-in-emivita | medtimer-better | Emivita's widgets render medicine names on the home screen unconditionally (`NextRemindersWidget.kt:134`, `StockLevelsWidget.kt:128`); a lock-screen-visible widget defeats the biometric lock. | S | Glance: early-return a placeholder in `provideGlance`. |
| E-37 | Next-reminders widget: content and refresh trigger (Q9) | `widgets/NextRemindersLineProvider.kt:15-25` reads `SimulatedRemindersRepository.simulatedReminders.value` (`:19-20`), kicks `triggerCalculation()` if never run (`:16-18`); pushed by `feature/reminders/.../AlarmProcessor.kt:131-137` broadcast to `WidgetUpdateReceiver` on every `setNextReminderAlarm`/`setSecondaryAlarm` (`:54`, `:87`); XML `updatePeriodMillis="3600000"` (`feature/reminders/src/main/res/xml/next_reminders_widget_info.xml`); whole widget click opens app (`WidgetImpl.kt:83-91`) | `feature/widget/NextRemindersWidget.kt:65-96` reads `occurrenceProjectionCache.state.value` (`:68`), `filter ≥ now → sortedBy → distinctBy(reminderId) → take(4)` (`:74-78`); refresh = `updatePeriodMillis="1800000"` (`app/src/main/res/xml/next_reminders_widget_info.xml`) + self-update after `TakeNextDoseAction` (`:183`); no repository/receiver calls `update`/`updateAll` (searched `NextRemindersWidget()`, `updateAll`: only `feature/widget`); doc `:58-62` calls this deliberate | divergent | medtimer-better | **Freshness regression:** a dose taken from the notification or Today screen stays listed as "next" for up to 30 min in Emivita; medTimer re-renders within the alarm-reschedule path that runs after every take/skip. Cost to fix is one `NextRemindersWidget().updateAll(context)` beside each `autoSyncManager.notifyChanged()` (E-10's 17 sites) or a single collector on `occurrenceProjectionCache.state`. See Findings F5 for the cold-start case. | S | Glance 1.1.1 `GlanceAppWidget.updateAll` is a suspend fun — call from the same repository coroutine. |
| E-38 | Widget responsive sizing | `widgets/WidgetImpl.kt:36-43` API 31+ `RemoteViews(Map<SizeF,RemoteViews>)` 4 variants (1/4 lines × short/long), `:44-48` pre-31 heuristic on `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`; line text per size via `getWidgetLine(i, isShort)` | `NextRemindersWidget.kt:41` fixed `MAX_ROWS = 4`, no `SizeMode` override (Glance default `SizeMode.Single`); XML `minWidth 180dp / minHeight 110dp`, `targetCellWidth 3 / Height 2` | divergent | inspiration | A 1-cell-tall Emivita widget clips rows 2-4; medTimer degrades to one line. Glance equivalent is `override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp,55.dp), DpSize(180.dp,110.dp)))` + `LocalSize`. | S | |
| E-39 | Widget action buttons | none — click anywhere opens `MainActivity` (`WidgetImpl.kt:64-67`, `:76-79`) | `NextRemindersWidget.kt:137-156` "Take" on the first row → `TakeNextDoseAction` `:170-185` (materialise-then-resolve); `StockLevelsWidget.kt:162-172` `RefillStockAction` → `logRefill` | absent-in-medtimer | emivita-better | Original; medTimer's widgets are read-only. | - | |
| E-40 | Latest-events widget | `widgets/LatestRemindersLineProvider.kt:13-33` last 7 days of events (`getLastDays(7)` `:20`) newest first; provider `LatestRemindersWidgetProvider.kt`; XML `latest_reminders_widget_info.xml` (`updatePeriodMillis` 1 h, line 17) | ABSENT (Emivita ships Next Doses + Stock Levels only, §9 #7 `docs/SPEC.md:1133-1135`) | absent-in-emivita | inspiration | Different second widget chosen deliberately; a "recent doses" Glance widget would read `doseEventDao` directly (no projection needed). | M | |
| E-41 | Stock-levels widget | ABSENT (medTimer surfaces stock in the medicine card and notifications only) | `feature/widget/StockLevelsWidget.kt:58-96` soonest run-out first via `state.stockRunOutDates` (`:75`), Refill action `:162-172`; literals `:121-122` (already in inventory) | absent-in-medtimer | emivita-better | Original. | - | |
| E-42 | Relative date/time display (`use_relative_date_time`) (Q11) | `core/ui/.../TimeFormatter.kt:105-117` `toConfigurableDateTimeString` → `DateUtils.getRelativeDateTimeString(..., MINUTE_IN_MILLIS, DAY_IN_MILLIS*2, FORMAT_SHOW_TIME)` when the pref is on, `:119-135` short/long variants; key `PreferencesDataSource.kt:252` | ABSENT (searched `getRelativeTimeSpanString`, `getRelativeDateTimeString`, `relative` in `.kt`: only stock/luminance comments) | absent-in-emivita | inspiration | "In 20 min" / "Yesterday 08:00" is not offered anywhere in Emivita; the Today timeline shows absolute `HH:mm` (`feature/today/TodayScreen.kt:309`). Pure `android.text.format.DateUtils`, no dependency. | S | |
| E-43 | `system_locale` + `LocaleContextAccessor` | `TimeFormatter.kt:200-207` `getLocale()` returns `configuration.locales[1]` when `systemLocale` and >1 locale (i.e. app language ≠ system language → format dates in the system's locale); `core/common/.../helpers/LocaleContextAccessor.kt:10-19` builds a context pinned to `locales[0]`; key `PreferencesDataSource.kt:253` | ABSENT (searched `systemLocale`, `system_locale`, `createConfigurationContext`: 0 hits) | absent-in-emivita | n/a | Only meaningful once Emivita ships a second real locale (§9 #19 Stage C open); until then there is no locale list to pick from. | S | |
| E-44 | Locale-aware time-of-day rendering | `TimeFormatter.kt:41-50` `DateFormat.getTimeFormat(localeAwareContext)` (honours 12/24 h system setting), `:52-56` `ofLocalizedTime(SHORT).withLocale(getLocale())`, `:93-99` instant→time/date | `TodayScreen.kt:309`, `HistoryScreen.kt:450`, `NextRemindersWidget.kt:128` all `DateTimeFormatter.ofPattern("HH:mm")` (24 h regardless of device setting); yet `core/notification/ReminderNotificationBuilder.kt:88` uses `ofLocalizedTime(FormatStyle.SHORT)` | divergent | medtimer-better | On a US-locale phone the notification says "8:00 AM" while Today, History and the widget say "08:00" for the same dose. No SPEC line records 24 h as a decision (searched `24-hour`, `HH:mm`, `12-hour`: none). See Findings F7. | S | One shared `timeFormatter(zone)` in `core/format/DoseFormatting.kt`. |
| E-45 | Locale-aware amount parsing/formatting | `core/common/.../helpers/MedicineHelper.kt:57-62` `formatAmount` via thread-local `NumberFormat` (max 2 fraction digits), `:64-72` `parseAmount` via `NumberFormat.parse` (accepts "0,5" under it/de) | `core/format/DoseFormatting.kt:64-65` `formatDecimal` = `Double.toString()` (always `.`), input parsed with `String.toDoubleOrNull()` at `feature/reminders/AddReminderScreen.kt:639`, `feature/medicines/StockExpirationScreen.kt:202-204, 307, 309, 316` | divergent | medtimer-better | `"0,5".toDoubleOrNull()` is null, so with `KeyboardType.Decimal` (`AddReminderScreen.kt:412`) on an Italian/German keyboard — whose decimal key inserts `,` — a half-dose cannot be entered. See Findings F6. | S | `java.text.NumberFormat`, no dependency. |
| E-46 | DI: Hilt vs manual container; test seams (Q12) | Hilt in every module except `:core:domain`, `:feature:reminders:api` (`core/database/.../di/DatabaseModule.kt:33-52` `@Provides` DB + DAOs; `core/common/.../di/` 7 modules); tests: `app/src/test/.../processortests/TestReminderContext.kt:38-43` `RepositoryFakes` with Mockito `mock()` + Hilt test module | `core/di/AppContainer.kt:27` plain class, `:34` `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, `by lazy` singletons `:42-138` (DB `:42`, projection cache `:97`, backup `:111`, auto-sync `:128`); tests: mockk (`app/build.gradle.kts:408`, 18 `mockk` sites across 7 test files) + in-memory Room | excluded-by-design | n/a | SPEC §5 line 567 / §2: "DI (Hilt) — Not adopted". Seams are equivalent: medTimer fakes repositories behind Hilt bindings; Emivita passes fakes through constructors (`AutoSyncManager(..., nowEpochMillis = …)` `AutoSyncManager.kt:40`). Nothing to port. | - | |
| E-47 | Cloud/device backup exclusion (`allowBackup`) | `app/src/main/AndroidManifest.xml:32-35` `allowBackup="false"`, `dataExtractionRules`, `fullBackupContent` | `app/src/main/AndroidManifest.xml:109-111` `allowBackup="false"` + both rule files; `res/xml/backup_rules.xml` excludes `root` and `file` domains (R20/R23 rationale in file header) | parity | equal | Identical posture; Emivita documents why both rule files are kept while inert. | - | |
| E-48 | Restore progress and result feedback | `BackupManager.kt:208-235` `ProgressDialogFragment` during restore + result `MaterialAlertDialogBuilder` (`restore_successful`/`restore_failed`) | `BackupSyncScreen.kt:166`, `:183` inline `restoreStatus` text below the button; export likewise `:116`, `:119` | divergent | equal | Emivita's inline status includes `e.message` (`:183`), which after E-05's failure mode would surface a raw `SerializationException` message to the user. | - | |
| E-49 | Backup DB-version stamp | `JSONBackup.kt:20-24` writes `version = database.version` (`BackupRepositoryImpl.kt:25-26`, `MedicineRoomDatabase.kt:69-70` reads it from the open helper) | `BackupData.kt:28` `schemaVersion` — omitted from output because default `Json` has `encodeDefaults = false` and the value equals its default (`BackupSyncScreen.kt:105`, `AutoSyncManager.kt:82`) | divergent | medtimer-better | An Emivita zip written today contains no version marker at all, so a future `BACKUP_SCHEMA_VERSION = 2` reader cannot distinguish v1 files from malformed ones. Folded into the E-05 fix. | S | |

#### Spot-check list

1. **E-05 / F1 (kotlinx default `Json` strictness).** Verifier: confirm `BackupSyncScreen.kt:161` and `:105` reference the companion `Json` object (import `kotlinx.serialization.json.Json`, no custom instance in the file — I found none; `ReminderConfigCodec.kt:13` is the only `Json { ignoreUnknownKeys = true }` in the tree and is not used for backups). Then confirm kotlinx-serialization 1.8.1's defaults `ignoreUnknownKeys=false`, `encodeDefaults=false` — both are documented defaults but I did not run a decode.
2. **E-37 / F5 (widget cold start renders empty).** Verifier: `NextRemindersWidget.kt:66-68` — `container.occurrenceProjectionCache` is `by lazy` (`AppContainer.kt:97`); its `init` (`OccurrenceProjectionCache.kt:125-152`) calls `invalidate()` which only *queues* a recompute behind `DEBOUNCE_MILLIS = 1_000` (`:23`). Confirm there is no `first { computedAtEpochMillis > 0 }` await between `:68` and `provideContent` `:91`, and check on a device whether a widget refresh after process death shows "All caught up" with pending doses.
3. **E-02 (missing `1.json`).** Verifier: `app/schemas/com.hazia.emivita.core.data.db.EmivitaDatabase/` lists `2.json..6.json`; `MigrationFrom1To2Test.kt:23-33` says the v1 shape is rebuilt by hand because no schema JSON exists for v1. Confirm no v1 build was ever shipped (`git log` on `app/schemas`); if one was, the v1 schema baseline is unrecoverable and the 1→2 test's fixture is the only record of it.

#### Findings (defects in Emivita uncovered by the comparison)

- **F1 — Backup restore is not forward- or backward-compatible.** `feature/settings/BackupSyncScreen.kt:161` decodes with the default `Json`, so a file written by a newer Emivita (any added entity field) fails with `Unknown key`, and `schemaVersion` is never written (`encodeDefaults=false`, `core/data/backup/BackupData.kt:28`) nor checked (`:20-24`). medTimer tolerates unknown keys and defaults missing ones (`app/.../backup/JSONBackup.kt:26-42`). Fix: shared `Json { ignoreUnknownKeys = true; encodeDefaults = true }` used at `BackupSyncScreen.kt:105,161` and `AutoSyncManager.kt:82`, plus a version gate.
- **F2 — Medical ID edits never trigger auto-sync.** `core/data/repository/ProfileRepository.kt:26` `update(profile) = profileDao.update(profile)` has no `autoSyncManager.notifyChanged()`; every other mutating repository method does (17 sites, E-10). Allergies/conditions edited in `MedicalIdScreen.kt:133,195` reach the sync folder only after some unrelated dose/medicine change. medTimer's interval backup (`BackupManager.kt:273-295`) is table-agnostic so has no equivalent hole.
- **F3 — History export silently writes nothing when the SAF stream is null.** `feature/history/HistoryScreen.kt:150` and `:159` `openOutputStream(uri)?.use { … }` — null → no write, no message (there is no success message either). The same bug shape was fixed for backup export under §7 R23 (`BackupSyncScreen.kt:107-112` `?: error(...)`) but not here. medTimer surfaces `ExporterException` as a toast (`app/.../OptionsMenu.kt:348-353`).
- **F4 — Hard-coded UI string missed by the text gate.** `BackupSyncScreen.kt:175` `restorePasswordError = "Wrong password -- try again."`, rendered at `:334`. The gate (`app/src/test/.../ui/HardcodedUiTextGateTest.kt:46-65`) is sink-based and this is a variable assignment, so it passes; it is not among the inventory's four known literals.
- **F5 — Next Doses widget renders "All caught up" on a cold process.** `feature/widget/NextRemindersWidget.kt:68` reads `occurrenceProjectionCache.state.value` synchronously; when the widget host starts the process (30-min tick), the cache is created lazily (`core/di/AppContainer.kt:97`) and its first recompute lands ≥ 1 s later (`OccurrenceProjectionCache.kt:23,125-152`), so `state.occurrences` is empty at `:74`. With no push from any repository (E-37) the wrong frame persists until the next tick. medTimer has the same synchronous read (`NextRemindersLineProvider.kt:16-21`) but is re-pushed on every alarm set (`AlarmProcessor.kt:54,87,131-137`).
- **F6 — Decimal-comma dose input is rejected.** `feature/reminders/AddReminderScreen.kt:639` `dose.toDoubleOrNull()`; `feature/medicines/StockExpirationScreen.kt:202-204,307,309,316` likewise. Under an it/de locale the `KeyboardType.Decimal` key (`AddReminderScreen.kt:412`) inserts `,`, so "0,5" → null → field invalid. medTimer parses with `NumberFormat` (`core/common/.../MedicineHelper.kt:64-72`).
- **F7 — Inconsistent time rendering across surfaces.** `TodayScreen.kt:309`, `HistoryScreen.kt:450`, `NextRemindersWidget.kt:128` hard-code `HH:mm`; `ReminderNotificationBuilder.kt:88` uses `ofLocalizedTime(SHORT)`. A 12-hour-locale user sees "8:00 AM" in the notification and "08:00" on Today for the same dose. medTimer routes everything through `TimeFormatter` (`core/ui/.../TimeFormatter.kt:41-56,93-99`).
- **F8 — CSV row mixes the scheduled date with the resolved time.** `core/data/export/HistoryExporter.kt:43-47`: `Date` = scheduled instant's date, `Time` = `resolvedAtEpochMillis ?: scheduled`'s time. A dose scheduled 23:30 on the 12th and taken 00:10 on the 13th exports as `2026-09-12,00:10`, a timestamp that never happened. medTimer exports reminded and taken as two complete timestamps plus ISO-8601 copies (`CSVEventExport.kt:44-58`).

#### As-is candidates

None. No row in this area qualifies for `import-as-is` under the brief's rule (copy with at most import/package edits):

- `BackupFieldParityTest.kt:26-41` (the reflection helper) compiles unchanged but is vacuous against Emivita's entity-as-DTO design (E-16); the useful Emivita guard is a different assertion.
- `JSONBackupFuzzTest.kt` depends on Mockito and `JSONBackup<T>` (E-17).
- `FileHelper.kt:18-77` references `LogTags.BACKUP` and a medTimer `FileProvider` authority, and Emivita has no `<provider>` (E-26).
- `MedicineHelper.getMedicineName` (`:22-31`) and `formatAmount/parseAmount` (`:57-72`) are 4-15 pure lines each but take medTimer's `Medicine`/`UserPreferences` types; rewriting the signature is the whole job (E-35, E-45).
- `Biometrics.kt` is `@AssistedInject`/Hilt and `FragmentActivity`-callback based (E-30/E-32).

### E.5.F — Tests, build, CI, quality gates, docs, i18n infrastructure

#### Area F — tests, build, CI, quality gates, docs, i18n infrastructure

Correction to the brief before the rows: Emivita is **not** "no CI". `.github/workflows/ci.yml` exists (78 lines, one job `verify`: assembleDebug / testDebugUnitTest / lintDebug, reports uploaded `if: always()`), and `gradlew` is mode `100755` in the index (SPEC §7 R38, `docs/SPEC.md:968`, fixed the `Permission denied` first-step failure). Rows F-18 onward compare against that file, not against "absent".

Scheduler tests (`schedulertests/`) are area A; `TestHelper.buildReminder` appears here (F-04) only because question (1) named it.

| # | feature | medTimer (file:line) | Emivita (file:line or ABSENT or EXCLUDED §-cite) | relation | verdict | evidence | cost | dependency/licence note |
|---|---|---|---|---|---|---|---|---|
| F-01 | Processor unit tests (notification / alarm / refill / repeat / schedule-next) on the JVM | `app/src/test/java/com/futsch1/medtimer/processortests/NotificationProcessorTest.kt:41-48` (`@HiltAndroidTest` + `RobolectricTestRunner` + `@Config(sdk=[36])` + `@UninstallModules(DatabaseModule, DatastoreModule, TimeAccessModule)`), `:60-121` (`@BindValue` per collaborator); `ScheduleNextReminderNotificationProcessorTest.kt:120-139`; `RefillProcessorTest.kt:128-160`; `RepeatProcessorTest.kt:122-132`; `ShowReminderNotificationProcessorTest.kt:134-210`; `AlarmProcessorTest.kt:20-49` | ABSENT on JVM (searched app/src/test for `AlarmScheduler`, `ReminderAlarmReceiver`, `ReminderActionReceiver`, `AlertActionReceiver`, `ReminderNotificationBuilder`, `RealSchedulingCoordinator` — only a comment hit at `SchedulingEngineTest.kt:497`). Device-only: `app/src/androidTest/java/com/hazia/emivita/core/scheduling/AlarmChainTest.kt:59-118` | absent-in-emivita | inspiration | medTimer pins concrete side effects: `verify(alarmManagerMock, times(1)).setAndAllowWhileIdle(anyInt(), eq(600 * 60 * 1000L), any())` (`ScheduleNextReminderNotificationProcessorTest.kt:139`), status `TAKEN` + `processedTimestamp == 10` + `cancel(1)` + `never().notify` (`NotificationProcessorTest.kt:158-164`), refill amount `"100 ➡ 110"` and type `REFILL` (`RefillProcessorTest.kt:140-144`), `remainingRepeats == -1` (`RepeatProcessorTest.kt:130-131`), Robolectric `shadowOf(app).broadcastIntents.size == 1` (`ShowReminderNotificationProcessorTest.kt:185-186`). Emivita's receivers and `AlarmScheduler` are exercised only by one real alarm fire on a phone (`AlarmChainTest.kt:102-118`), which CI never runs (`app/build.gradle.kts:37-41`). | M | Hilt `@BindValue` is not portable; Emivita would hand its receivers a test `AppContainer` (`core/di/AppContainer.kt`) with mockk (`app/build.gradle.kts:408`) + Robolectric `ShadowAlarmManager`. Robolectric 4.14.1 already present (`:404`). |
| F-02 | Repository fakes for processor tests (`RepositoryFakes`) | `processortests/TestReminderContext.kt:38-105` — Mockito `when(runBlocking { repo.x() }).thenAnswer` over `MedicineRepository`/`ReminderRepository`/`ReminderEventRepository`, backed by `MutableList<*Entity>` and `toEntity()/toModel()` mappers (`:54, :59, :63-77`), hand-written `decreaseRepeats` (`:86-92`) | `app/src/test/java/com/hazia/emivita/core/data/repository/DoseEventRepositoryTest.kt:58-77` — `RobolectricTestRunner`, real `Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java)` (`:67`), only `OccurrenceProjectionCache`/`AutoSyncManager` are `mockk(relaxed = true)` (`:76-77`) | divergent | emivita-better | medTimer re-implements repository semantics in the fake (`create` assigns `reminderEvents.size + 1` as id, `:67-72`), so the fake can drift from `MedicineRepositoryImpl`; Emivita's repository tests run the real DAO SQL (`DoseEventRepositoryTest.kt:127-243`, e.g. "a backdated correction does not become the ON_RESOLVED anchor", `:148`). Not import-as-is: Mockito + medTimer entity/model types. | - | Emivita uses mockk, not Mockito (`app/build.gradle.kts:408`). |
| F-03 | `NotificationManagerFake` (active-notification registry for tests) | `processortests/TestReminderContext.kt:107-145` — Mockito `NotificationManager` mock whose `activeNotifications` (`:113`) returns `StatusBarNotification` mocks; `add(id, reminderIds, reminderEventIds, remindTimestamp)` stubs a `Bundle` mock for `ActivityCodes.EXTRA_REMINDER_ID_LIST`, `EXTRA_REMINDER_EVENT_ID_LIST`, `EXTRA_REMIND_INSTANT`, `EXTRA_NOTIFICATION_ID` (`:135-141`) | ABSENT (searched app/src/test for `NotificationManager`, `activeNotifications` → none; `core/notification/NotificationDeliveryTest.kt:21-83` is pure over two booleans) | absent-in-emivita | inspiration | The fake lets medTimer assert "notification already active → cancel + re-notify" (`ShowReminderNotificationProcessorTest.kt:163-165`). Emivita's `ReminderNotificationBuilder` and the "already posted" branch have no JVM test. Not import-as-is: keyed on medTimer's `ActivityCodes` extras and Mockito `when`. | S | Robolectric's `ShadowNotificationManager.getAllNotifications()` would do the same job without a fake — Emivita already runs Robolectric. |
| F-04 | Test fixture builders (`TestHelper.buildReminder`, `TestMedicine`) | `app/src/test/java/com/futsch1/medtimer/schedulertests/TestHelper.kt:16-35` (`TestMedicine` wrapper with `amount`/`expirationDate` setters), `:37-56` (`buildReminder(medicineId, reminderId, amount, timeInMinutes, daysBetweenReminders)` = `Reminder.default().copy(..., pauseDays = daysBetweenReminders - 1)`) | Per-suite helpers: `core/scheduling/SchedulingEngineTest.kt:47-58` (`at()`, `asLocal()`, `assertOccursAt`), `androidTest/.../DeviceTestSupport.kt:37-44` (`testMedicine(profileId, tag)`) | parity | equal | Both sides build fixtures from a `default().copy(...)`-style call; medTimer's is shared across 19 files (`processortests/ExampleData.kt:11-14` reuses it), Emivita's are per file. Not import-as-is: `Reminder.default()`, `ReminderTime`, `pauseDays` are medTimer domain types; Emivita's `Reminder` carries a `ReminderConfig` JSON (`core/data/config/ReminderConfigCodec.kt:16-19`). | - | none |
| F-05 | Hilt test module + `HiltTestApplication` for Robolectric | `app/src/test/java/com/futsch1/medtimer/di/TestSystemServicesModule.kt:17-46` (`@TestInstallIn(replaces = [SystemServicesModule::class])`, mocks for AudioManager/PowerManager/ActivityManager/Vibrator/IMM/SharedPreferences); `app/src/test/resources/robolectric.properties:1` (`application = dagger.hilt.android.testing.HiltTestApplication`) | EXCLUDED (no DI framework; `core/di/AppContainer.kt`, `LocalAppContainer.kt`). `app/src/test/resources/robolectric.properties:27` pins `application=android.app.Application` because `EmivitaApplication.onCreate()` calls `System.loadLibrary("sqlcipher")` (`:18-20`) | excluded-by-design | n/a | medTimer must uninstall three Hilt modules per test class (`NotificationProcessorTest.kt:44-48`) and bind ~20 values; Emivita constructs the unit under test directly (`DoseEventRepositoryTest.kt:67-77`). Nothing to import. | - | none |
| F-06 | Robolectric SDK image pin | Per class: `@Config(sdk = [36])` in processor/app tests (`AlarmProcessorTest.kt:18`, `MedicineHelperTest.kt:27`), `@Config(sdk = [28])` in every Compose test (`core/ui/src/test/.../SortableTableTest.kt:20`, `feature/ui/src/test/.../StatisticsScreenTest.kt:28`, `CalendarContentLayoutTest.kt:41`) — two images in one repo | Global `sdk=35` in `app/src/test/resources/robolectric.properties:13` (reason `:1-12`: Robolectric 4.14.1 ships no 36 image and `isIncludeAndroidResources` makes it read targetSdk), repeated per migration class `@Config(sdk = [35], application = android.app.Application::class)` (`core/data/db/MigrationFrom5To6Test.kt:31`) | divergent | emivita-better | One image for every Robolectric test vs. medTimer's split (28 for Compose, 36 elsewhere) that its own files do not explain. Emivita's file says why and when to raise it (`robolectric.properties:10-12`). | - | medTimer is on Robolectric 4.16.1 (`gradle/libs.versions.toml:39`); Emivita 4.14.1 (`app/build.gradle.kts:404`). Both versions are in the offline cache (`~/.gradle/caches/modules-2/files-2.1/org.robolectric/robolectric/{4.14.1,4.16.1}`). |
| F-07 | Global test JVM timezone / locale pin | `app/build.gradle.kts:103-107` — `it.jvmArgs("-Duser.timezone=Europe/Berlin")`, `user.language=en`, `user.country=US` for all unit tests; `TimeHelperTest.kt:55-58` then switches `LocaleList(Locale.US, Locale.GERMAN)` explicitly | No global pin (searched `user.timezone`, `TimeZone.setDefault` in app/src/test and `app/build.gradle.kts` → none). Scheduling suites pass an explicit `ZoneId.of("Europe/Rome")` (`SchedulingEngineTest.kt:47`, `ReminderSchedulingTest.kt:36`, `OccurrenceProjectionCacheTest.kt:54`) — SPEC §7.1 rule 6 (`docs/SPEC.md:849`) | divergent | medtimer-better | Emivita's explicit-zone tests are sound, but anything that reads `ZoneId.systemDefault()` (`core/data/export/HistoryExporter.kt:35` default parameter) or `Locale.getDefault()` runs under whatever the CI runner has; medTimer's block makes the whole task deterministic. | S | none (one `testOptions.unitTests.all { }` block) |
| F-08 | Backup-parser fuzz test, gated behind `-Dfuzzing=true` | `app/src/test/java/com/futsch1/medtimer/JSONBackupFuzzTest.kt:13-27` (`@RunWith(Parameterized)`, 7 inputs: `""`, `{}`, `[]`, `null`, `{"invalid":}`, prose, `{"medicines":[]}`), `:38-43` parse then apply; excluded unless `-Dfuzzing=true` (`app/build.gradle.kts:224-227`), CI step `test.yml:56-57` | ABSENT (searched app/src/test for `BackupRepository`, `BackupZipWriter`, `decodeFromString` → none). Restore path `feature/settings/BackupSyncScreen.kt:160-162` decodes with `Json.decodeFromString(BackupData.serializer(), readResult.json)` inside `try/catch (e: Exception)` (`:182`) | absent-in-emivita | inspiration | Malformed or truncated backup JSON is caught by the blanket catch and reported via `backup_restore_failed` (`BackupSyncScreen.kt:183`), but nothing asserts that; medTimer asserts `parseBackup` returns null on each corpus item and that `applyBackup` never throws. | S | JUnit4 `Parameterized` and kotlinx-serialization are already present; no new dependency. |
| F-09 | Backup round-trip / missing-field unit tests | `JSONMedicineBackupUnitTest.kt:404-444` (`parseBackup` returns null for `{"medicine": {}}`, invalid JSON, `{"version": 1}`), `JSONReminderEventBackupUnitTest.kt:15-16`, `JSONSettingsBackupUnitTest.kt:57-133` (`testRoundTrip`, `testNoHomeLocationClearsIt`) | ABSENT — `core/data/backup/BackupRepository.kt:32-42` (`exportSnapshot`) and `:50-66` (`restoreSnapshot`: deletes children first `:51-58`, restores parents first `:60-66`) and `BackupZipWriter.write/read` (`BackupZipWriter.kt:32`) have no test | absent-in-emivita | inspiration | The FK-ordered delete/insert in `restoreSnapshot` is exactly the kind of ordering a Robolectric in-memory Room round trip (same shape as `DoseEventRepositoryTest.kt:67`) would pin; today only a manual restore proves it. | M | none new (Room in-memory + kotlinx-serialization already on the test classpath) |
| F-10 | Entity ↔ backup field-parity guard (reflection) | `core/database/src/test/java/com/futsch1/medtimer/database/toBackup/BackupFieldParityTest.kt:26-41` (`fieldNames(clazz)`, `assertBackupCoversEntity(entity, backup, excluded)`), `:44-89` (four entities, each excluded field with a reason) | Structurally different: entities are themselves `@Serializable` (`core/data/backup/BackupData.kt:23-25`, `core/data/entity/Medicine.kt:10-13`), so there is no DTO to drift. The *table set* can drift: `EmivitaDatabase.entities` lists 7 (`core/data/db/EmivitaDatabase.kt:23-31`) and `BackupData` has 7 lists (`BackupData.kt:28-36`) — equal today, unenforced (no test references `EmivitaDatabase.entities`) | divergent | inspiration | medTimer fails the build when an entity gains a field its DTO lacks; Emivita cannot have that bug but can silently add an 8th `@Entity` that `exportSnapshot` (`BackupRepository.kt:32-42`) never reads. A 10-line JVM test asserting `EmivitaDatabase`'s `@Database.entities` ⊆ `BackupData` constructor element types closes it. | S | none |
| F-11 | CSV exporter unit test | `app/src/test/java/com/futsch1/medtimer/CSVExportUnitTest.kt:43-46` (Robolectric + Hilt), `:122-126` pins header `Reminded;Name;Dosage;Taken;Tags;Interval;Notes;Reminded (ISO 8601);Taken (ISO 8601)` and two rows, `:195-196` empty-list case | ABSENT — `core/data/export/HistoryExporter.kt:35-54` (`buildCsv`, header `Date,Time,Medicine,Status,Dose,Measurement`) and `:56-61` (`csvEscape`, RFC-4180 quoting of `,` `"` `\n`) have no test (searched `buildCsv`, `HistoryExporter` in app/src/test → none) | absent-in-emivita | inspiration | `buildCsv` is a pure function of `List<DoseEventWithMedicine>` + `ZoneId` — testable without Robolectric, unlike medTimer's Hilt-bound exporter. The escaping branch (`:57-58`) and the `resolvedAtEpochMillis ?: scheduledEpochMillis` fallback (`:44`) are untested today. | S | none |
| F-12 | Formatter / helper tests under Robolectric with locale switching | `TimeHelperTest.kt:54-88` (`setLocales(LocaleList(Locale.US, Locale.GERMAN))`, asserts English then German date strings when `systemLocale` toggles), `:89-99` round trip; `MedicineHelperTest.kt:57-64` `testFormatParseRoundTrip`; `MedicineStringFormatterTest.kt`, `ReminderSummaryFormatterTest.kt` (all `HiltTestApplication`) | Partial: `core/data/export/EmergencyCardExporterTest.kt:56-160` (10 pure tests over card lines), `core/notification/NotificationDeliveryTest.kt`. `core/format/DoseFormatting.kt:64-65` `formatDecimal` = `value.toString()` and `:78-86` `formatDoseWithUnit` have no test (searched `DoseFormatting`, `formatDecimal` in app/src/test → none) | divergent | medtimer-better | medTimer's `formatAmount`/`parseAmount` are locale-aware and round-trip-tested; Emivita's `formatDecimal` always emits a `.` decimal separator (`DoseFormatting.kt:65`), which becomes visible the day Stage C ships a locale that uses `,` (see Findings 2). | S | none |
| F-13 | Compose UI test technique | `createComposeRule()` + `RobolectricTestRunner` + `@Config(sdk = [28])`: `core/ui/src/test/.../SortableTableTest.kt:19-24`, `feature/ui/src/test/.../StatisticsScreenTest.kt:27-32`, `DayEventsCardTest.kt:23-28`; deps `ui-test-junit4` + `ui-test-manifest` as `testImplementation` (`core/ui/build.gradle.kts:126-127`, `feature/ui/build.gradle.kts:241-242`); guideline `docs/guidelines/testing.md:115-126` | Identical: `createComposeRule()` + `RobolectricTestRunner` (`feature/history/HistoryEntryRowTest.kt:37-41`, `feature/today/TimelineEntryRowSemanticsTest.kt:30-34`, `ui/components/DoseEditDialogTest.kt:34-38`); `ui-test-junit4` testImplementation `app/build.gradle.kts:430`, `ui-test-manifest` debugImplementation `:434`, `isIncludeAndroidResources = true` `:78` with rationale `:71-77`, `:411-428` | parity | equal | Same rule, same runner, same artifacts. Assertion style differs: Emivita asserts accessibility contract — `hasStateDescription("Taken")`, `hasRole(Role.Button)`, `assertHasClickAction()` (`TimelineEntryRowSemanticsTest.kt:54-59`), `assertHasNoClickAction()` for non-actionable rows (`HistoryEntryRowTest.kt:115`); medTimer asserts text/contentDescription presence (`StatisticsScreenTest.kt:39-41`, `useUnmergedTree = true` at `SortableTableTest.kt:43`) and click wiring (`:64-69`). | - | none |
| F-14 | Layout tests via Robolectric qualifiers + `getBoundsInRoot()` | `feature/ui/src/test/.../calendar/CalendarContentLayoutTest.kt:53-66` (`@Config(qualifiers = "land")`, asserts `event.left > calendar.left`), `:67-78` (portrait stacks: `event.top > calendar.top`), `:80-92` (wide tablet portrait still stacks); `charts/ChartsContentLayoutTest.kt:44-84` same shape; `setCalendar(width, height, windowSizeClass)` wraps content in `Box(Modifier.size(...))` with `LocalInspectionMode provides true` (`CalendarContentLayoutTest.kt:95-115`) | ABSENT (searched app/src/test for `qualifiers =`, `getBoundsInRoot` → none). SPEC R59 (`docs/SPEC.md:989`) records the "Ser / tral / ine" squeeze found only by the owner on a phone and notes "Robolectric asserts semantics, not pixels" | absent-in-emivita | inspiration | Robolectric bounds are layout-phase, not rendered pixels, but a `getBoundsInRoot()` width assertion on the Due row's name node inside a 360.dp `Box` would have caught the chip-first measurement that R59 describes. | S | none (same `ui-test-junit4`) |
| F-15 | Compose test tags exposed as resource ids for UiAutomator | `feature/ui/src/main/java/com/futsch1/medtimer/feature/ui/medicine/MedicinesScreen.kt:262-267` (`object MedicineTestTags`), `:96` (`Modifier.semantics { testTagsAsResourceId = true }`); consumed by `By.res(MedicineTestTags.MEDICINE_ITEM)` in `app/src/androidTest/.../AndroidTestHelper.kt:210, :216` and `By.res(MedicineTestTags.MEDICINE_NAME)` at `:227` | ABSENT (`grep -rn testTag app/src/main/java` → 0). Device suite never drives UI (`DeviceTestSupport.kt:12-17`) | absent-in-emivita | inspiration | Only matters if an on-device UI test is ever written; R59 chose `adb shell uiautomator dump` by hand instead (`CLAUDE.md:58`). medTimer's guideline says tags are a last resort (`docs/guidelines/testing.md:122-123`). | S | none |
| F-16 | Instrumented harness (activity rule, permission grants, failure screenshots, orchestrator) | `app/src/androidTest/.../BaseTestHelper.kt:30` (`BaristaRule<MainActivity>`), `:34-43` (`GrantPermissionRule` POST_NOTIFICATIONS / USE_FULL_SCREEN_INTENT), `:73` (`grantAppPermission("SCHEDULE_EXACT_ALARM")` via UiAutomator), `:79-84` (fails unless device date is 2025-08-01 — set by CI `instrumentedTests.yml:98`), `:103-124` (dismiss ANR dialog); `MyFailureHandler.kt:24-30` (screenshot to test storage); `app/build.gradle.kts:28-33` (`clearPackageData=true`, `useTestStorageService=true`), `:109-110` (`animationsDisabled`, `ANDROIDX_TEST_ORCHESTRATOR`), `:182` (`androidTestUtil(orchestrator)`) | `app/src/androidTest/java/com/hazia/emivita/DeviceTestSupport.kt:22-45` — real `EmivitaApplication.container` (`:24-26`), `ensureProfileId` (`:30-33`), no activity, no orchestrator, no `clearPackageData`; tests write to and clean the developer's real database (`:19-20`); runner only `app/build.gradle.kts:42` | divergent | medtimer-better | Orchestrator + `clearPackageData` give each medTimer test a fresh process and empty data dir; Emivita's tests share the live DB and rely on their own `finally` cleanup (`DeviceTestSupport.kt:19-20`), so a crashed test leaves an "androidTest …" medicine behind (`:35-39` names it so it is findable). Deliberate per SPEC §9 #20 (`docs/SPEC.md:1423-1432`): the point is the *real* install. | M | `androidx.test:orchestrator`, Barista, UiAutomator, Screengrab are NOT in the offline cache (`~/.gradle/caches/modules-2/files-2.1/androidx.test` holds only annotation/core/monitor/rules/runner; no `com.adevinta.android`, `tools.fastlane`, `androidx.test.uiautomator` dirs). Barista is Apache-2.0. |
| F-17 | androidTest breadth (11 Espresso/UiAutomator classes vs 3 device classes) | `BasicUITest.kt` (nav, menus, notes, overview filters, day selection `:41-245`); `CalendarTest.kt` (calendar view; deleted event hidden `:55-57`); `DeleteTest.kt` (`:14-16`); `ExportBackupTest.kt` (export + backup triggers `:16-55`); `ManualDoseTest.kt` (manual dose, disabled reminder `:25-81`); `MedicineHandlingTest.kt` (drag reorder + sort by name `:22-52`); `MedicineStockTest.kt` (stock decrement, hidden name in stock reminder, amount warning, big amounts, run-out date, all-taken, expiration, undo stock on delete / re-raise, daily stock reminder `:36-503`); `NotificationTest.kt` (taken/skipped, action on dismissed notification, repeating, variable amount, custom snooze, hidden name, big buttons, same-time grouping, auto-taken, alarm, schedule, no-skip button `:99-751`); `ReminderTest.kt` (active toggle, interval, linked delete, all reminder types, edit, delete, cyclic, weekend mode, reschedule `:37-578`); `ScreenshotsTest.kt` (Screengrab per locale `:20-60`); `TagTest.kt` (tag handling, medicine visibility, overview visibility `:29-125`) | `SqlCipherRoundTripTest.kt:31-76` (row round-trips through real SQLCipher; file header is not plaintext SQLite magic); `NotificationDeliveryDeviceTest.kt:34-95` (channels exist; `blockedTiers` agrees with NotificationManager; alarm channel uses `USAGE_ALARM` + vibrates + old channel deleted `:71-77`); `AlarmChainTest.kt:59-118` (one reminder → real alarm → receiver → DoseEvent → notification) | divergent | inspiration | Behaviour Emivita has but does not device-test, by medTimer class: **MedicineStockTest** (Emivita stock decrement/refill/expiry alerts — `LowStockExpirationAlertEvaluator.kt`, `StockExpirationScreen.kt`), **NotificationTest** taken/skipped actions and same-time grouping (`ReminderActionReceiver.kt`, `AlertActionReceiver.kt`), **ManualDoseTest** (`ui/components/DoseEditDialog.kt`), **ReminderTest** weekend mode / linked following / edit (`SchedulingEngine.kt`, `AddReminderScreen.kt`), **ExportBackupTest** (`BackupSyncScreen.kt`, `HistoryExporter.kt`), **CalendarTest** (`HistoryCalendarView.kt`), **TagTest** (`TagDao.kt`, `MedicinesScreen.kt`), **DeleteTest**. Not applicable: MedicineHandlingTest drag (reorder UI unbuilt, SPEC §9), ScreenshotsTest (no store). Emivita covers most of these on the JVM instead (`SchedulingEngineTest` 36, `DoseEventRepositoryTest` 12, 6 Compose semantics classes), consistent with `testing.md:9-22`'s pyramid; the device gap is UI reachability, which R59 (`docs/SPEC.md:989`) says is exactly what the owner found by hand. | L | see F-16 |
| F-18 | CI pipeline shape | `.github/workflows/test.yml:20-23` staged lint → unit → compile → instrumented; `:24-41` lint job uploads `lint-results-fullDebug.html`; `:43-81` unit tests in four invocations (fuzz `:56-57`, foss `:59-60`, full `:65-66`, library modules `:70-71`); `:99-108` coverage run; `:112-126` matrix `api-level: [28, 36] × profile: [medium_phone, medium_tablet]`; `instrumentedTests.yml:52-56` KVM, `:58-65` AVD cache, `:93-99` device date `toybox date 0801160025` + pull test outputs; `build.yml:44-47` assembleRelease/bundleRelease, `:49-58` sign from secrets, `:114-119` GitHub release on `refs/tags/v*` with generated notes, `:42` `dependency-graph: generate-and-submit`; every action pinned by SHA (`test.yml:27`, `:29`, `:34`) | `.github/workflows/ci.yml:28-31` single job `verify` (rationale `:6-10`), `:13-17` push/PR/dispatch, `:20-22` concurrency cancel, `:24-25` `permissions: contents: read`, `:40-44` JDK 21 (matches `gradle/gradle-daemon-jvm.properties` `toolchainVersion=21` and `app/build.gradle.kts:22-24`), `:49-50` SDK, `:55-65` the three tasks, `:69-78` reports `if: always()` with 14-day retention; actions referenced by floating tag (`@v4`, `@v3` at `:35, :41, :50, :53, :71`) | divergent | medtimer-better | medTimer runs both flavors, fuzz, lint, an emulator matrix and coverage; Emivita runs the three tasks its ledger quotes. Two cheap deltas: (a) SHA-pin the five actions (scorecard's Pinned-Dependencies; `test.yml:27` shows the form); (b) `android-actions/setup-android@v3` (`ci.yml:50`) vs medTimer relying on the runner image + `setup-java` `cache: gradle` (`test.yml:33`). An emulator job is not cheap: Emivita ships arm ABIs only (`app/build.gradle.kts:49-51`), so an x86_64 AVD cannot load `libsqlcipher.so`; the file says drop the filter if emulators ever join the loop (`:48`). | S (pins) / L (emulator) | none for pins; `reactivecircus/android-emulator-runner` is Apache-2.0 |
| F-19 | CodeQL static analysis | `.github/workflows/codeql.yml:14-20` (push/PR to main + weekly cron), `:52` `language: [java-kotlin]`, `:63-66` `github/codeql-action/init` pinned by SHA, `:74-80` JDK 21 + Gradle | ABSENT (only `ci.yml` under `.github/workflows/`) | absent-in-emivita | inspiration | Zero code to write — one workflow file. Value is modest for a no-INTERNET app but non-zero (SQL strings in `Migrations.kt`, zip handling in `BackupZipWriter.kt`). | S | GitHub code scanning on a **private** repo requires GitHub Advanced Security; free on public repos only. |
| F-20 | OpenSSF Scorecard | `.github/workflows/scorecard.yml:9-15` (branch-protection, weekly cron, push main), `:40-57` `ossf/scorecard-action` with `publish_results: true`, `:70-73` SARIF upload | ABSENT | absent-in-emivita | inspiration | Opinion: for a single-owner private repo the checks that would move (Pinned-Dependencies, Token-Permissions) are the two things F-18 already names; the badge/publishing half is `false` on private repos by the action's own comment (`scorecard.yml:54-56`). | S | same private-repo caveat as F-19 |
| F-21 | Dependency review on PRs | `.github/workflows/dependency-review.yml:10` (`on: [pull_request]`), `:27` `actions/dependency-review-action` | ABSENT | absent-in-emivita | inspiration | Only fires on pull requests; Emivita's `ci.yml:13-16` accepts PRs but the ledger shows work landing on `master` directly (R38, `docs/SPEC.md:968`). Opinion: skip until there is a PR flow. | S | private repo: needs GHAS for the vulnerability database |
| F-22 | Code coverage (JaCoCo) | `app/build.gradle.kts:56-57` (`enableUnitTestCoverage`/`enableAndroidTestCoverage` on debug), `:219-223` JaCoCo task extension, `:244-254` nine-module `coverageModules`, `:257-305` aggregated `jacocoFullDebugCodeCoverage` (XML+HTML, excludes R/BuildConfig/Args/Directions `:232-240`); uploaded to Qlty `instrumentedTests.yml:101-123` (OIDC); `testing.md:128-136` "guideline, not a hard gate" | ABSENT (searched `jacoco`, `coverage` in `app/build.gradle.kts`, `ci.yml` → none) | absent-in-emivita | inspiration | Single-module Emivita needs only `enableUnitTestCoverage = true` on `debug` and the stock `createDebugUnitTestCoverageReport` task; the 60-line aggregation is multi-module plumbing. `testing.md:130-131`'s "not a gate" matches SPEC §7.1 rule 5 (`docs/SPEC.md:845`) — a number is not a proof. | S | `org.jacoco` is NOT in the offline cache (no `org.jacoco` dir under `~/.gradle/caches/modules-2/files-2.1`) — first run needs network. |
| F-23 | SonarQube / SonarCloud | `app/build.gradle.kts:5` plugin, `:204-217` `sonar { }` (project key `Futsch1_medTimer`, lint XML + JaCoCo XML fed in); `instrumentedTests.yml:125-130` runs `./gradlew sonar` only when `SONAR_TOKEN` is set; `kotlin-android.md:16-17` "new issues block merge" | ABSENT | absent-in-emivita | inspiration | Opinion: not worth it here — SonarCloud is free for public repos only, needs a hosted account, and Emivita's §10 "one file" rule (`docs/SPEC.md:1580-1586`) already keeps findings in the ledger rather than a dashboard. | M | SonarCloud paid for private repos; plugin 7.3.0 not in offline cache (not checked, assume absent) |
| F-24 | Monkey test | `.github/workflows/monkeyTest.yml:64-72` (`adb shell monkey -p com.futsch1.medtimer -v 10000 --throttle 20` on a cached API-36 AVD after `adb install -g`); `monkeyTest.sh:3` local variant (`--throttle 50`). Note: the job is `workflow_dispatch`-only (`:3-4`) yet gated `if: github.event_name == 'push' \|\| (pull_request from fork)` (`:11`) — that condition is never true under dispatch, so the CI job is dead; only the shell script can run | ABSENT | absent-in-emivita | inspiration | One adb line against the attached phone (the same device `CLAUDE.md:31-41` already uses) is the whole cost; a 10k-event monkey run is the cheapest way to hit `AlarmActivity`/dialog paths no test constructs (SPEC §7.1 rule 18, `docs/SPEC.md:903`). | S | none |
| F-25 | Firebase Test Lab (instrumentation + Robo) | `.github/workflows/firebaseTest.yml:28-41` (`google-github-actions/auth` with service-account secret, `gcloud firebase test android run tests.yml:medium-phone-arm --use-orchestrator`, then `robo.yml`); `tests.yml:1-13` (MediumPhone.arm v34 en portrait + MediumTablet.arm v34 de landscape); `robo.yml:1-9` (180 s Robo crawl) | EXCLUDED — Google Cloud service; SPEC §1 no-GMS/no-cloud posture (`README.md:89-93`; manifest `app/src/main/AndroidManifest.xml:87`) | excluded-by-design | n/a | A Firebase project is a Google account dependency for the *developer*, not the app, so §1 does not strictly forbid it; opinion: still off-brand for a repo whose README lists GMS among things "dropped for good". | - | Google Cloud account + service-account JSON |
| F-26 | Play upload / store listing / release signing in CI | `app/build.gradle.kts:2` (`triplet.play`), `:191-194` (`track internal`, AAB); `.github/workflows/storeListing.yml:26-27` (`publishListing`); `uploadToPlay.sh:3-6` (fastlane supply); `build.yml:49-58` signs AAB/APK from repo secrets | EXCLUDED — sideloaded only, release signed with the shared debug identity on purpose (`app/build.gradle.kts:118-124`, `:171`; trade-off `:126-170`) | excluded-by-design | n/a | Nothing to import. The one transferable idea is `build.yml:114-119` (GitHub release with artifacts on a `v*` tag), which Emivita replaces with the tracked `dist/vc1/` baseline (`git ls-files` shows `dist/vc1/emivita-0.1.0-vc1-release.apk`, `mapping-vc1.txt.gz`). | - | none |
| F-27 | Android Lint gate | `app/build.gradle.kts:112-119` (`abortOnError`, `warningsAsErrors`, `disable` IconLocation/GradleDependency/AndroidGradlePluginVersion/OldTargetApi), duplicated in `lint.xml:3-6` as `severity="ignore"`; no baseline file | `app/build.gradle.kts:230-259` — same two flags (`:231-232`), `baseline = file("lint-baseline.xml")` (`:241`, rationale `:233-240`), `informational += GradleDependency/NewerVersionAvailable/OldTargetApi` (`:252`, rationale `:243-251`), xml/html/text reports (`:256-258`); `lint-baseline.xml` holds e.g. `OldTargetApi` and `UnusedAttribute` entries | parity | emivita-better | Same gate; Emivita keeps version-currency checks *visible* (informational) where medTimer silences them (`disable`), and explains the baseline's purpose. Cost of the baseline: 33 pre-existing findings are permanently exempt (R38, `docs/SPEC.md:968`); medTimer has zero exemptions. | - | none |
| F-28 | Hardcoded-UI-text source gate | ABSENT (searched medTimer test trees for `HardcodedText`, `strings.xml` readers → none). Relies on lint `HardcodedText`, which fires on XML layouts only; medTimer's UI is 38 XML layouts + 13 Compose files, so the Compose fraction is unguarded | `app/src/test/java/com/hazia/emivita/ui/HardcodedUiTextGateTest.kt:45-66` (20 sink regexes: `Text(`, `contentDescription =`, notification builder setters, `NotificationChannel(`, `drawText`, biometric prompt setters, app composables, enum display arg, named `title/label/saveLabel/helper`), `:78-82` UI-layer scoping for the `title =` rule, `:88-91` comment blanking with preserved offsets, `:103-128` the gate, `:130-148` `theGateItselfCanFail` self-test (§7.1 rule 5) | absent-in-medtimer | emivita-better | The gate's stated blind spot (`:30-35`: a literal assigned to a `val` first) is live: `feature/settings/BackupSyncScreen.kt:175` `restorePasswordError = "Wrong password -- try again."` is rendered by `Text(it, …)` at `:336` and is not in the inventory's §6 list — see Findings 1. | - | none |
| F-29 | Palette-spec consistency + WCAG contrast + texture tests | `feature/ui/src/test/.../charts/ChartSeriesColorsTest.kt:10-48` (palette cycling, custom colour wins, wrap). No contrast or spec-parity test (searched medTimer tests for `luminance`, `contrast`, `SPEC` → none) | `ui/theme/PaletteSpecConsistencyTest.kt:66-84` parses `docs/SPEC.md` Appendix A, `:87-116` asserts 6 palettes × 2 modes × 11 roles = 132 values against `colorSchemeFor`/`emivitaColorsFor`, `:124-135` `splash_background` in `res/values/colors.xml` equals default palette background; `ui/theme/PaletteContrastTest.kt:26-38` WCAG 2.1 luminance/ratio over every scheme (`:41-47`); `ui/components/ColorDistinguishabilityTest.kt`; `feature/history/SeriesPatternTest.kt:43-51` (`patternLuminanceDistance` > 0.02 between textures) | absent-in-medtimer | emivita-better | Emivita makes its design doc executable (rule 8, `docs/SPEC.md:855`); medTimer's `MedTimerColors.kt` has no assertion tying it to any document. Nothing in medTimer to import. | - | none |
| F-30 | Pseudolocales for extraction checking | ABSENT (searched `isPseudoLocalesEnabled` across medTimer → none); 23 real locales instead (F-31) | `app/build.gradle.kts:193` `isPseudoLocalesEnabled = true` on debug (rationale `:177-192`); `CLAUDE.md:52-63` per-app locale command `adb shell cmd locale set-app-locales com.hazia.emivita --locales en-XA` + glyph traps; R59 found seven leftovers this way (`docs/SPEC.md:989`) | absent-in-medtimer | emivita-better | The right instrument *before* the first translation; medTimer never needed it because Weblate contributors surface untranslated strings. | - | none |
| F-31 | Locale build config (`resConfigs`, `generateLocaleConfig`, `localeFilters`) | `app/build.gradle.kts:23-25` (`resConfigs("en,ar,…,zh-rTW")`, kept because lint does not honour `localeFilters` — comment `:23`), `:70-98` (`androidResources { generateLocaleConfig = true; localeFilters += listOf(23 locales) }` → AGP writes the Android 13 `localeConfig` for per-app language); 26 `values-*` dirs in `core/ui/src/main/res` (incl. `values-is`, `values-nb-rNO` present but filtered out) | ABSENT — `app/src/main/res` has only `values`, `values-night`; no `generateLocaleConfig`/`localeFilters`/`resConfigs` (grep of `app/build.gradle.kts` → none). Stage C is open (`docs/SPEC.md:1406-1407`: "one real translation; the language is a decision for the owner") | absent-in-emivita | inspiration | For one real translation Emivita needs, beyond `values-xx/strings.xml`: (1) `androidResources { generateLocaleConfig = true; localeFilters += listOf("en", "xx") }` copied from `:71-98` (AGP 8.13 supports both; skip the deprecated `resConfigs` unless lint misreports); (2) nothing else for system-locale switching; (3) per-app language *picker* before API 33 needs `AppCompatDelegate.setApplicationLocales`, and Emivita has no appcompat dependency — on minSdk 30 the framework `LocaleManager` exists only from 33, so devices 30–32 would follow the system locale only (opinion: acceptable for a single-owner device). Also `formatDecimal` (F-12) must become locale-aware before a `,`-decimal locale ships. | S | none for (1)–(2); `androidx.appcompat` for (3) |
| F-32 | Translation tooling (Weblate, `translate.py`) | `README.md:98-99` Weblate project; `translate.py:13-38` language table, `:57-68` loads `core/ui/src/main/res/values/strings.xml` skipping `translatable="false"`, `:83-102` finds missing keys per `values-*`, `:117-141` DeepL backend, `:167-238` Claude-CLI backend, `:152-164` `_validate_format_specifiers` (regex `%\d*\$?[a-zA-Z]`, skips a translation whose specifiers differ), `:105-115` writes with lxml | ABSENT (no scripts; `docs/` holds `SPEC.md` + `palette-mockup.html` only) | absent-in-emivita | inspiration | The reusable idea is the specifier check (`:152-164`): as a JVM test comparing every `%1$s`/`%d` set in `values/strings.xml` against `values-xx/strings.xml`, in the same repo-reading style as `PaletteSpecConsistencyTest.kt:32-40`, it turns a translator's reordering mistake into a build failure. Weblate itself needs a public repo → excluded. | S | `translate.py` needs `lxml` + `deepl` (network); the JVM-test form needs nothing. |
| F-33 | Per-locale intro drawables + screenshot pipeline | `copyIntroDrawables.sh:8-9` (screenshot index → `intro_{medicine,reminder,notification,overview,analysis}.png`), `:19-38` copies fastlane screenshots into `drawable-<lang>[-r<REGION>]`, `:41-45` pngquant/optipng; `screenshots.sh:3-18` SystemUI demo-mode status bar, `:21` 23 locales, `:30-34` 12h for en-US / 24h otherwise, `:36-43` `fastlane screengrab … --use_tests_in_classes=…ScreenshotsTest`; `ScreenshotsTest.kt:20-25` `LocaleTestRule`; 22 `drawable-*` dirs under `core/ui/src/main/res` | ABSENT (no app intro, no fastlane; searched `intro_`, `Screengrab` → none) | absent-in-emivita | n/a | Feature-coupled (app intro + store listing), both outside Emivita's scope; the demo-mode status-bar block (`screenshots.sh:3-18`) is the only piece with standalone value for the owner's own screenshots. | - | fastlane (Ruby) + Screengrab; not in offline cache |
| F-34 | Gradle version catalog vs inlined dependencies | `gradle/libs.versions.toml` 159 lines (`[versions]:1`, `[libraries]:68`, `[plugins]:150`); every module uses `libs.*` (`app/build.gradle.kts:2-8`, `:132-188`; `build.gradle.kts:3-8`); `AGENTS.md:113` forbids unpinned additions; `jetpack-compose.md:25-26` "pin every Compose artifact through the BOM in `libs.versions.toml`" | Inlined coordinates `app/build.gradle.kts:264-434`; plugin versions in root `build.gradle.kts:14-17` plus one inline plugin version (`app/build.gradle.kts:6`); `resolutionStrategy.force(kotlinx-serialization-core:1.8.1)` `:436-440`; `gradle/` has only `wrapper` + `gradle-daemon-jvm.properties` | absent-in-emivita | inspiration | Opinion, labelled as such: not worth adopting for a single-module app. The value of Emivita's dependency block is its reasoning (why sqlcipher is pinned at 4.17.0 `:289-295`, why calendar is 2.7.0 `:366-371`, why ui-tooling is excluded `:373-382`) — a TOML carries only `#` comments and would split each decision from its coordinate. Adopt only if a second module appears. | - | none |
| F-35 | R8/ProGuard keep rules | `app/proguard-rules.pro:2-3` (`-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile` for readable release stack traces), `:6` Signature, `:9` annotations, `:13-20` Gson model keeps | `app/proguard-rules.pro:2-3` (`*Annotation*, InnerClasses, Signature, EnclosingMethod`, `kotlin.Metadata`), `:6-8` Room, `:13-15` SQLCipher JNI (`net.zetetic.database.**`), `:18-25` kotlinx.serialization, `:28-29` Glance receivers. No `SourceFile,LineNumberTable` (grep `keepattributes` → lines 2 and 18 only) | divergent | inspiration | Emivita ships a mapping file with its archived release (`dist/vc1/mapping-vc1.txt.gz` tracked) — retrace only recovers line numbers if `LineNumberTable` survived shrinking. Whether AGP's `proguard-android-optimize.txt` already keeps it was **not verified** in this session (spot-check 3). | S | none |
| F-36 | `full`/`foss` flavor split for de-googling | `app/build.gradle.kts:35-44` (`flavorDimensions "distribution"`, `full` default, `foss`), `:171` (`"testFullImplementation"(libs.play.services.location)`); `kotlin-android.md:177-194` (interface in `main/`, per-flavor Hilt binding, GMS tests in `testFull/`); `AGENTS.md:15-27` | EXCLUDED §5 / §1: no GMS, no network at all — `app/src/main/AndroidManifest.xml:20-29, :84` declare only SCHEDULE_EXACT_ALARM (≤32), USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT, VIBRATE, USE_BIOMETRIC; `:87` `<!-- No android.permission.INTERNET anywhere in this file, by design. -->`; transitive WAKE_LOCK/FOREGROUND_SERVICE/ACCESS_NETWORK_STATE/USE_FINGERPRINT removed with `tools:node="remove"` (`:69-72`); `res/xml/network_security_config.xml:3-9` blocks cleartext defensively | excluded-by-design | n/a | medTimer needs a flavor because one build talks to Play Services; Emivita has one build with no INTERNET permission, so there is nothing to split. | - | none |
| F-37 | Contributor / agent guideline documents | `docs/guidelines/README.md` (index), `kotlin-android.md` (229; enforcement `:10-22`, flavors `:177-194`, translations `:196-206`, Room `:208-217`), `jetpack-compose.md` (362; conventions `:47-62`, state-holder rules `:162-184`, performance `:264-277`, theming/previews `:279-291`), `testing.md` (163; pyramid `:9-22`, test-first `:24-32`, black-box `:34-48`, priorities `:50-64`, naming `:66-80`, Compose `:115-126`, coverage `:128-136`, fuzz `:138-152`), `documentation-guidelines.md` (Diátaxis `:40-47`, taxonomy `:15-35`); `AGENTS.md` (build commands `:48-67`, CI map `:74-83`, AI hygiene `:102-118`) | `CLAUDE.md:1-101` (commands only; "where the two disagree, SPEC.md wins" `:6-7`) + `docs/SPEC.md` §10 working agreements (`:1551-1586`) + §7.1 rules (`:825-910`) | divergent | equal | Different shape (medTimer: reference docs; Emivita: rules distilled from a defect ledger, each citing its row). Per-rule mapping is in F-38/F-39. | - | none |
| F-38 | Guideline rules Emivita already follows (by SPEC §10 / §7.1 or by code) | `testing.md:13-22` JVM-first pyramid; `testing.md:117-123` `createComposeRule` + Robolectric, query by semantics; `testing.md:28-29` failing test before fix; `testing.md:69-76` backtick test names; `testing.md:112-113` camelCase names in instrumented tests; `kotlin-android.md:14-15` lint `warningsAsErrors`, no unexplained suppressions; `kotlin-android.md:210-211` schemas committed; `AGENTS.md:117` migrations are "ask first"; `documentation-guidelines.md:84` no real user data in docs | `app/build.gradle.kts:411-428` (Compose tests in the unit source set, reasons); `TimelineEntryRowSemanticsTest.kt:54-59`; §7.1 rule 5 `docs/SPEC.md:845`; `SchedulingEngineTest.kt:177` backticks; `AlarmChainTest.kt` camelCase; `app/build.gradle.kts:231-232` + rule 14 `docs/SPEC.md:879`; `app/schemas/…/2.json…6.json`; §10 "spec-first checkpoint" `docs/SPEC.md:1553`; synthetic names "Sertraline"/"Ada Lovelace" in tests (`HistoryEntryRowTest.kt:68`, `MedicalIdScreenTest.kt:65`) | parity | equal | Nine of medTimer's enforceable rules are already practised, mostly with a ledger row explaining why. | - | none |
| F-39 | Guideline rules Emivita does not follow | `jetpack-compose.md:289-291` every screen has light+dark `@Preview` via a multipreview annotation; `:34-35`, `:167-168` `ImmutableList` in state holders; `:169-170` `derivedStateOf` for derived values; `:162-184` `XxxScreenState`/`MutableXxxScreenState` + ViewModel-only mutation; `kotlin-android.md:126-127` inject dispatchers, never `Dispatchers.IO` directly; `kotlin-android.md:18-19` + `CodeStyle.xml:2` 160-char right margin; `documentation-guidelines.md:15-35` ADRs/taxonomy; `AGENTS.md:106` never commit keystores | `grep -rn @Preview app/src/main/java` → 0; `ImmutableList`/`kotlinx-collections-immutable` → 0; `derivedStateOf` → 0; `class .*ViewModel` → 0 (screens read repositories via `LocalAppContainer`); `Dispatchers.IO`/`Default` direct uses → 17; no `CodeStyle.xml`/`.editorconfig` (`gradle.properties` has `kotlin.code.style=official` only); §10 "One file" (`docs/SPEC.md:1580-1586`) forbids separate ADRs by design; the debug keystore is committed on purpose (`.gitignore` `!keystores/my-shared-debug.keystore`, `app/build.gradle.kts:93-99`, `:126-170`) | divergent | inspiration | The two that cost nothing and would have paid off in R59's owner findings (`docs/SPEC.md:989`, "a component nobody compared"): light+dark `@Preview` per screen (`jetpack-compose.md:289-291`) and a shared `@Preview` multipreview. The ViewModel/state-holder pattern is a rewrite and is a decision for the owner (memory: discuss frontend before building). Keystore and ADR divergences are deliberate and documented. | S (previews) / L (state holders) | `ui-tooling-preview` already present (`app/build.gradle.kts:273`) |
| F-40 | Glossary / project context file | `CONTEXT.md:1-60` (Statistics-vs-Analysis naming decision, `StatisticsUiState`, Charts/Calendar/Table presenters — fixes vocabulary so code, strings and conversation agree; notes a cancelled rename `:16-17`) | `docs/SPEC.md` §3 data model (`:161-506`) and §6 UX decisions (`:572-798`) serve the same purpose inline; no standalone glossary (§10 one-file rule `:1580-1586`) | divergent | equal | Same function, different container. Nothing to import. | - | none |
| F-41 | Use-case documentation | `docs/UseCases.md:1-20` (birth-control cycles with `First cycle start`/`Active days`/`Pause days`, plus tapering, every-n-weeks, stock for another person, etc., each with a screenshot such as `cyclic_reminder_birth_control.png`) | ABSENT as a user document; requirements live in `docs/SPEC.md` §9 items (`:1063-1480`) | absent-in-emivita | n/a | User-facing how-tos presuppose users other than the owner; §1 is single-user. | - | none |
| F-42 | Line-ending / binary enforcement in git | ABSENT (no `.gitattributes` at medTimer root — `ls` shows none) | `.gitattributes:14` `* text=auto eol=lf`, `:20-22` CRLF for `*.bat`, `gradlew.bat`, `gradle-wrapper.properties`, `:30-41` keystore/apk/aab/jar/png/fonts/pdf `binary`; rationale `:1-13` (R57, `docs/SPEC.md:987`) and `CLAUDE.md:86-88` | absent-in-medtimer | emivita-better | Windows-developed repo; medTimer is Linux/CI-driven and never hit the CRLF diff blow-up. | - | none |
| F-43 | Build-verification discipline (exit codes, stale test XML) | `AGENTS.md:48-67` build commands; `testing.md:19-22` "do not run the full instrumented suite locally" | `CLAUDE.md:14-29` (`rm -rf app/build/test-results`, redirect to `build.log`, `echo EXIT=$?`, grep `^e: ` and `BUILD `), `:37-50` device-suite equivalent (`connectedDebugAndroidTest` uninstalls the app afterwards `:49-50`); §10 `docs/SPEC.md:1571-1579`; §7.1 rule 9 `docs/SPEC.md:858` | absent-in-medtimer | emivita-better | medTimer delegates the question to CI; Emivita, developed offline on Windows, wrote down the failure modes it actually hit (three false greens, R57). | - | none |
| F-44 | JDK / toolchain pinning | `app/build.gradle.kts:196-200` `java.toolchain languageVersion 21`, `:61-63` Java 21 bytecode + desugaring; CI `setup-java` 21 with `cache: gradle` (`test.yml:28-33`); `gradle.properties` enables configuration cache (`org.gradle.configuration-cache=true`) | `app/build.gradle.kts:22-24` `kotlin { jvmToolchain(21) }` (reason `:9-21`), `:54-58` Java 17 bytecode; `gradle/gradle-daemon-jvm.properties` `toolchainVersion=21` with foojay URLs; `ci.yml:37-44`; no configuration cache in `gradle.properties` | parity | equal | Both pin JDK 21 for compilation; Emivita additionally pins the *daemon* JVM in a tracked file. medTimer's `org.gradle.configuration-cache=true` is a free speed-up Emivita could try (opinion; AGP 8.13 supports it). | S | none |

#### Spot-check list (three least certain rows)

1. **F-35 (ProGuard `SourceFile,LineNumberTable`).** I did not open AGP 8.13.2's bundled `proguard-android-optimize.txt`. If it already carries `-keepattributes SourceFile,LineNumberTable`, Emivita's mapping file is retraceable and the row's "inspiration" drops to "equal". Verifier: `unzip -p ~/.gradle/caches/**/gradle-8.13.2*.jar com/android/build/gradle/proguard-android-optimize.txt | grep -n LineNumberTable`, or read `app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-8.13.2` after any release build.
2. **F-31 (per-app language on API 30–32).** The claim that Emivita would need `androidx.appcompat`'s `AppCompatDelegate.setApplicationLocales` for a language *picker* below API 33 is from memory of the platform API surface, not from either tree. Verifier: check `android.app.LocaleManager` min API (33) and whether `AndroidManifest.xml` needs `android:localeConfig` when `generateLocaleConfig = true` (medTimer's `app/src/main/AndroidManifest.xml` was not opened for this — grep it for `localeConfig`).
3. **F-24 (dead monkey CI job).** The `if:` at `monkeyTest.yml:11` under a `workflow_dispatch`-only trigger (`:3-4`) reads as never-true, but GitHub's `github.event_name` for a manual run is `workflow_dispatch`, so the job should be skipped every time. Verifier: the repository's Actions history for "MonkeyTest" — if runs show the job as executed, the reading is wrong (irrelevant to Emivita's decision either way; it only affects how much weight to give medTimer's monkey coverage).

#### Findings (Emivita defects uncovered by the comparison)

1. **Gate-blind hardcoded user-visible string, not in the inventory's §6 list.** `app/src/main/java/com/hazia/emivita/feature/settings/BackupSyncScreen.kt:175` assigns `restorePasswordError = "Wrong password -- try again."`, which `:334-336` renders with `Text(it, color = MaterialTheme.colorScheme.error, …)`. It is exactly the `val`-first blind spot `HardcodedUiTextGateTest.kt:30-35` documents, and it did not appear in the R59 en-XA sweep (`docs/SPEC.md:989` lists seven others) because the wrong-password branch needs a password-protected zip and a wrong password to render. medTimer's equivalent path is resource-backed (its dialogs are XML; `kotlin-android.md:196-206`). Fix: `R.string.backup_wrong_password_try_again` (lint will also want the `--` as a real dash once it is in XML, per `CLAUDE.md:81-82`). Cost S. Also add the fifth entry to the inventory's §6 list.
2. **`formatDecimal` is locale-blind — an i18n gap that Stage C will expose, not a crash.** `core/format/DoseFormatting.kt:64-65` returns `value.toString()`, so a 0.5 dose prints `0.5` under every locale; `formatDoseWithUnit` (`:78-86`) builds on it. medTimer formats amounts through a locale-aware path with a parse/format round-trip test (`MedicineHelperTest.kt:57-64`) and tests date formatting under `LocaleList(Locale.US, Locale.GERMAN)` (`TimeHelperTest.kt:55-58`). For an Italian translation (the owner's likely Stage C language, `docs/SPEC.md:1406-1407`) `0.5` is wrong and `0,5` is expected. No test exists for `formatDecimal` (searched app/src/test). Cost S (`NumberFormat.getInstance(locale)` or `String.format(locale, …)`), plus a test in the F-12 shape.
3. **No JVM test reaches the alarm/notification receivers (F-01).** Not a behavioural defect in itself, but by SPEC §7.1 rule 5 (`docs/SPEC.md:845`) it is a gap the ledger should carry: `AlarmScheduler.kt`, `ReminderAlarmReceiver.kt`, `ReminderActionReceiver.kt`, `AlertActionReceiver.kt`, `ReminderNotificationBuilder.kt`, `RealSchedulingCoordinator.kt` have no test class under `app/src/test`; only `AlarmChainTest.kt:59-118` (device, never in CI — `app/build.gradle.kts:37-41`) exercises them, with one reminder. medTimer's `processortests/*` pin the same seams on every push (`test.yml:59-71`).

Zero defects found in: CI configuration (the R38 `gradlew` mode fix is in the index: `git ls-files -s gradlew` → `100755`), lint gate, `.gitattributes`, robolectric properties, backup entity coverage (`EmivitaDatabase.kt:23-31` and `BackupData.kt:28-36` agree, 7/7), CSV escaping (`HistoryExporter.kt:56-61` is RFC-4180 correct), crash-log hygiene (`hs_err_pid*.log`/`replay_pid*.log` are gitignored, not tracked).

#### As-is candidates

None in area F. Every helper named in question (1) fails the brief's `import-as-is` test on inspection:

- `NotificationManagerFake` (`app/src/test/java/com/futsch1/medtimer/processortests/TestReminderContext.kt:107-144`): Mockito `mock<NotificationManager>()` + `Bundle` stubs keyed on `ActivityCodes.EXTRA_*` (`:137-141`) — medTimer-only constants and a mocking library Emivita does not use (mockk, `app/build.gradle.kts:408`). Would-be destination if rewritten: `app/src/test/java/com/hazia/emivita/core/notification/NotificationManagerFake.kt`, but Robolectric's `ShadowNotificationManager` makes it unnecessary.
- `RepositoryFakes` (`TestReminderContext.kt:38-105`): Mockito over medTimer repository interfaces with `toEntity()/toModel()` mappers Emivita does not have; Emivita's in-memory Room (`DoseEventRepositoryTest.kt:67`) is the better seam anyway.
- `TestHelper.buildReminder` / `TestMedicine` (`schedulertests/TestHelper.kt:16-56`): built on `Reminder.default()`, `ReminderTime`, `pauseDays` — medTimer domain types; Emivita's `Reminder` carries a `ReminderConfig` JSON (`core/data/config/ReminderConfigCodec.kt:16-19`). Area A owns the scheduler tests themselves.
- `BackupFieldParityTest.assertBackupCoversEntity` (`core/database/src/test/.../BackupFieldParityTest.kt:26-41`) is the only pure-JVM, dependency-free helper in the area, but its premise (entity vs DTO) does not exist in Emivita (`BackupData.kt:23-25` serializes entities directly); the adapted check in F-10 is a new 10-line test, not a copy.
