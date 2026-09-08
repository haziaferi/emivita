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

19. **⏳ Internationalisation + accessibility — scoped, Stage A in progress.**
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
    **A** (in progress): `isPseudoLocalesEnabled` on debug, extract all 159,
    include the non-Compose text, add the source-scan gate. Reaches no new
    user; makes the next translation hours rather than a rewrite, and makes
    an un-extracted string *visible on the screen* rather than a claim.
    **B**: `contentDescription` across the ~170 controls, plus
    `semantics`/`stateDescription` on the chart, the calendar grid and the
    dose rows, gated by Compose tests. **C**: one real translation; the
    language is a decision for the owner, not derivable from the code.

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
