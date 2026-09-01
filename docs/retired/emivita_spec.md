# Emivita — Project Specification

This is the source of truth. Anything decided in conversation and not reflected
here didn't happen — fold it in before it can affect the code. Anything here
that conflicts with a stray Kotlin comment or an old chat message loses to
this file until this file is edited.

Status key used throughout: 🔒 locked (don't revisit without a real reason) ·
✅ built · 🚧 designed but not built · ❓ open, needs a decision.

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
| `applicationId`/root package `com.hazia.emivita` | Fixed project identity — **confirmed now, not yet applied.** Overlooked earlier: the scheduling-engine batch (§4) already shipped under a placeholder identity (`com.personal.medkeeper` / "MedKeeper" — applicationId, namespace, every class/package name, `rootProject.name`, app label). Do the rename now, as §9 item 0, rather than letting it compound over more batches. A package/applicationId change is effectively a fresh install on Android (new app-private storage path, new Keystore alias) — fine to do now since §8 confirms no real medication data exists yet, much worse once it does |

---

## 2. Architecture 🔒

- **DI: manual `AppContainer`, not Hilt.** Decided explicitly (see §9 for the
  tradeoff writeup). OS-instantiated entry points (`BroadcastReceiver`,
  future `AppWidgetProvider`) reach the graph via
  `(context.applicationContext as EmivitaApplication).container` — the same
  idiom throughout, not a special case. Revisit only if a future batch
  genuinely needs multi-instance scoping (nothing on the roadmap does).
- **UI: Jetpack Compose / Material3 throughout.** The only XML views are the
  unavoidable pre-Compose splash theme (`themes.xml`) and the notification
  small-icon vector drawable.
- **DB: Room + SQLCipher**, encrypted at rest. Passphrase is a random 256-bit
  value (never user-typed), stored via `EncryptedSharedPreferences`/
  `MasterKey` (Keystore-backed). That API is deprecated
  (security-crypto 1.1.0-alpha07, no official replacement yet) —
  `@Suppress("DEPRECATION")`'d deliberately, DataStore+Tink rewrite deferred
  to a later, dedicated pass (not blocking, not forgotten — see §9).
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
| `FixedTime` | hour, minute, dose, `RepeatPattern` (Daily / SpecificDaysOfWeek / EveryNDays / **CyclicActivePause**) | ✅ built (Daily/SpecificDaysOfWeek/EveryNDays); 🚧 CyclicActivePause not yet |
| `RepeatingInterval` | intervalMinutes, dose, `IntervalStartBehavior`, anchorEpochMillis | ✅ built |
| `DailyWindow` | intervalMinutes, dose, window start/end hour+minute, `IntervalStartBehavior` | ✅ built |
| `LowStockAlert` | threshold, trigger mode (Once/Always/Daily) | 🚧 not yet — triggers off the projection cache's computed stock run-out date (§4/§9), not a separate periodic check |
| `ExpirationAlert` | lead days, frequency (Once/Daily) | 🚧 same as above, off the projection cache's date tracking |
| **`LinkedFollowing`** | delayMinutes (1–1440), dose, `IntervalStartBehavior` — `parentReminderId` lives as a real `Reminder` column, not here (see below) | 🚧 not yet |

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
on `Reminder` (`ON DELETE CASCADE` — deleting a reminder deletes its whole
following-dose chain, matching how the chain is nested in the UI even though
each link is its own row) — the same precedent `groupId` already set, and
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
notification regardless of it. Fixing this means: resolve the effective
lock state (`medicine.remindersLockedUntilTaken ?: preferences
.lockRemindersUntilTakenByDefault`) when building a notification and omit
the Skip action entirely when true, and defensively reject a Skip action in
`ReminderActionReceiver`/`AlarmActivity` too, in case a stale notification
button survives a setting change. Distinct from `DoseGroup.priority`
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

**`Medicine.color: String?`** (hex, new field) — 🔒 resolved. Continuous
HSV wheel + lightness strip + hex readout (Material-style picker, opened
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
*same* continuous HSV wheel + lightness strip + hex-readout picker component,
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

Deliberately not built yet (flagged, not silently skipped):
- The projection cache itself (§2/§9) and everything that reads from it:
  `LowStockAlert`/`ExpirationAlert` triggering (now designed around the
  cache's computed stock run-out date rather than a periodic threshold
  check — see §3/§9), Today/History/Widget screens.
- The five schema-driven gap resolutions from §3 (`autoResolve`,
  `activeFrom/UntilEpochDay` clamping, `repeatOnUnanswered` nagging, PRN
  `logAdhocDose`, `switchStatus` stock-correct undo) — schema is decided,
  engine/repository logic to act on them isn't written yet. Natural fit
  alongside #1 (`autoResolve` and the active-range clamp both live in
  `nextOccurrenceFor`/`ReminderAlarmReceiver`, right next to code already
  there) rather than a separate batch.
- `CyclicActivePause`, `LinkedFollowing`, and the weekend-mode adjustment
  step (§3 — schema/design now decided, engine work isn't written yet).
  `LinkedFollowing` needs two new cascade-reschedule trigger points, not
  just a new calculator: `ReminderAlarmReceiver` reschedules direct
  `ON_REMINDED` children when a reminder fires, `RealSchedulingCoordinator
  .onDoseResolved()` reschedules direct `ON_RESOLVED` children when one
  resolves — both look up children via the new `parentReminderId` column,
  one level down only (see §3 for why that's sufficient for arbitrarily
  long chains). The weekend adjustment applies last, after any type-specific
  calculator and the `activeFrom/UntilEpochDay` clamp, uniformly for every
  reminder.
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
| **Compose charting/table/calendar libraries** (ADR-0001: Vico for charts, hand-rolled `SortableTable`, Kizitonwose Calendar's Compose artifact — all Compose-native, GMS-free, verified against a de-googled flavor) | Direct library picks for the History screen (§9) — not a pattern to adapt, a shopping list |
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

## 6. UX decisions — confirmed 🔒 (from `docs/ux-audit.md`, carried forward)

- **Today screen:** linked/timeline pattern with aggregate grouped-dose
  nodes (not yet built — waiting on the Add flow, §9).
- **Medicine card:** full-card color, icon-per-medicine-type, tags as
  colored chips.
- **Add-medicine reminder-type picker:** 5 labeled cards — icon + name +
  one-line description visible *in the picker itself* (no separate help
  screen). Tapping a card goes straight to its config, no disambiguation
  step. Labels: "Fixed times", "Repeating interval", "Daily window",
  "Low-stock alert", "Expiration alert".
- **Medicine color picker:** continuous HSV wheel + lightness strip + hex
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
  `CyclicActivePause` stays out of the picker for now, same "coming soon"
  treatment as Low-Stock/Expiration above, not a new pattern.
- **Semantic state tokens** (selected/unselected, focus, error, disabled)
  are resolved relative to whichever of the 8 palettes' Accent is active
  (was 4 — updated this session along with the palette replacement above),
  not new fixed hues per palette. The old edge case this table used to
  carry — Neutral's Accent being fully desaturated, leaving no hue for the
  Error rule's "+150–165°" to rotate from — is now moot rather than fixed:
  Neutral is dropped entirely, and all 8 replacement palettes carry a real
  accent hue, so every one of them can run this formula as written:

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
- **Stock & expiration:** one screen, three visually zoned sections —
  Current stock (qty/unit/computed runs-out date), Low-stock alert
  (threshold/frequency), Expiration (date/lead time). One entry point, no
  duplicated fields elsewhere. **❓ Open, from `emivita-frontend-theme-review.md`:**
  this screen and History currently share no explicit register
  distinction, but shouldn't — Stock & Expiration is threshold/countdown-
  driven (closer to a status-board, ~0.55–0.6 tone per
  `app-register-layout`) while History is trend-driven (ambient-
  reflection, ~0.2–0.25 tone). Cheap to decide now since both screens are
  still unbuilt; gets expensive to retrofit once History's calmer visual
  treatment is already shipped and Stock & Expiration silently inherited it.
- **Settings:** 6 destinations grouped under section headers, not a flat
  list — matches the already-built `SettingsScreen.kt` groupings exactly.
  Every row gets a plain-language one-line description, not just some.
- **Text hierarchy:** heading (bold, full-contrast) / value (medium
  weight, full-contrast) / helper text (regular weight, muted) —
  consistent across every screen, not just some. **❓ Open, from
  `emivita-frontend-theme-review.md`:** dose amounts, times, and schedule
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
  **fully replaced**, not supplemented — confirmed this session, decided
  specifically because those 4 were picked without discussion (see §7 for
  the ledger entries this closes out). 8 new palettes, each with light +
  dark, each still traceable to `visual-craft`'s `design-lexicon.md`
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
- **"Log dose" entry point** (PRN, §3/§9): a secondary action alongside the
  Today screen's FAB, not a separate bottom-nav destination — picks a
  medicine, suggests its last-used amount, defaults to "just now."
- **Event editing:** swipe-to-edit on a Today/History entry (matches
  medTimer's interaction, §3/§9) — status switch (Taken↔Skipped), recorded
  time, and dose amount are each editable; editing amount doesn't
  retroactively touch stock (only a status switch does, per §3).
- **Snooze duration is user-configurable**, not hardcoded — lives on the
  already-planned "Alarms & Snooze" Settings destination, default 10
  minutes (§9).

---

## 7. Audit findings ledger

From `Emivita-audit.md`. Kept here so status doesn't have to be
re-derived by reading old chat every time.

| # | Finding | Status |
|---|---|---|
| 1 | Gradle/AGP version mismatch (§2.1) | ✅ Fixed |
| 2 | `NeutralPalette` AA contrast failure (§4.1) | ✅ Fixed |
| 3 | Stale `libs.versions.toml` (§2.2) | ✅ Fixed (deleted) |
| 4 | Playful dark-theme comment overstated contrast (§4.2) | ✅ Fixed |
| R1 | `MedicineRepository.addTag()` not transaction-wrapped | ❓ Still open |
| R2 | `MedicineRepository.decrementStock()` dead code | ❓ Still open |
| R3 | No tests for `resolve()`/tag-dedupe race-prone logic | ❓ Still open |
| §4.4 | `Color.asOnSurfaceSafe()` documented no-op | ❓ Still open |
| §7 | Stock launcher icon | ❓ Still open |
| §8 | Placeholder-screen copy ("...once the data layer is wired up") | ❓ Still open — and now visibly so, since the app runs but shows nothing (§9) |
| §2.3 | Full-screen alarm intent gating | ✅ Fixed (this batch) |
| R4 | `androidx.health.connect:connect-client` dependency + manifest Health Connect scaffolding present in delivered code, contradicting #13's ❌ Dropped decision (found in spec-vs-code review, not `Emivita-audit.md`) | ✅ Resolved — folded into §9 item 0 |
| R5 | `androidx.work:work-runtime-ktx` (WorkManager) dependency present for undesigned "periodic auto-backup," contradicting §9 #4's debounce+explicit-trigger design (found in spec-vs-code review) | ✅ Resolved — reconciled in §9 #9, cleanup folded into §9 item 0 |
| R6 | This batch's own bundled `README.md`/`docs/SPEC.md` describe the prior (data-layer) batch, not the scheduling-engine batch actually shipped — see §10's note on spec/code lag | ❓ Still open — regenerate both before the *next* batch ships, so lag doesn't compound across two batches |
| R7 | `Medicine.colorArgb: Int?` (shipped) vs. `Medicine.color: String?` (hex, spec'd) never reconciled; `Profile.colorArgb` undocumented entirely | ✅ Resolved — `color: String?` (hex) replaces `colorArgb` on both `Medicine` and `Profile` (§3), same picker component reused for both |
| R8 | §9 item 14's UI-scope wording treated `CyclicActivePause` as needing its own top-level Add-Reminder-picker card ("a 6th card"), contradicting §3/§6's locked decision that it's a 4th `RepeatPattern` case inside the existing "Fixed Time" card, not a new top-level type — found reviewing the rename/palette batch's bundled docs | ✅ Resolved — reworded in §9 item 14; only `LinkedFollowing` is an actual new top-level `ReminderConfig` needing a new picker card |
| R9 | The rename batch's `app/build.gradle.kts` had `security-crypto` pinned to a plain `1.1.0` — no such stable artifact has been published; this section already correctly describes the dependency as `1.1.0-alpha07` (the release where `EncryptedSharedPreferences`/`MasterKey` were deprecated), so the plain `1.1.0` string wouldn't have resolved at all — found in spec-vs-code review | ✅ Resolved — build file repinned to `1.1.0-alpha07` as part of the same rename batch |
| T1 | `LightOutline` fails 3:1 against `LightSurface` in all 4 outgoing palettes (1.48–1.68:1, checked against pure-white surface rather than the tint it was actually tuned against) — found in `emivita-frontend-theme-review.md`, this session | ✅ Resolved — moot, all 4 replaced (§6); every one of the 8 new palettes has its `outline` solved to clear 3:1 against **both** background and surface by construction, not patched per-hex after shipping |
| T2 | Row-description helper text (`onSurface.copy(alpha = 0.6f)`) fails AA normal-text contrast (3.38–3.97:1) in all 4 outgoing palettes — the same gap `docs/ux-audit.md` finding #8 already named ("helper text... secondary/muted tone") but the opacity shortcut didn't actually deliver; found in `emivita-frontend-theme-review.md`, this session | ✅ Resolved — moot, all 4 replaced (§6); every one of the 8 new palettes ships a real audited `mutedText` hex at ~4.6:1 against surface, not `.copy(alpha=)` |
| T3 | `colorSchemeFor()` only overrides `background/surface/primary/primaryContainer/secondaryContainer/tertiaryContainer/outline` — `secondary`/`tertiary`/`error`/`onError`/`errorContainer` are unset and silently fall back to Material3 stock red/etc.; found in `emivita-frontend-theme-review.md`, this session | ❓ Still open — was a 4-palette gap, is now an 8-palette gap post-replacement; folded into §9 item 0b |
| T4 | The Error state-token rule (§6 above, "Accent hue +150–165°") has no hue to rotate from against Neutral's fully-desaturated Accent — found in `emivita-frontend-theme-review.md`, this session | ✅ Resolved — moot, Neutral dropped entirely (§6); all 8 replacement palettes carry a real accent hue |
| T5 | Stock & Expiration and History share no explicit `app-register-layout` register distinction, though their actual data (threshold/countdown vs. trend-over-time) calls for different ones — found in `emivita-frontend-theme-review.md`, this session | ❓ Still open — logged in §6, cheap to decide since both screens are still unbuilt |
| T6 | `labelSmall` (11sp) has no rule against rendering dose/schedule-critical text at that size, given the app's own safety-critical-adherence purpose (§1) — found in `emivita-frontend-theme-review.md`, this session | ❓ Still open — logged in §6, not yet made a hard rule |

---

## 8. Current implementation status

**Built and functional:** encrypted Room DB + full repository layer,
scheduling engine end-to-end (§4), notification posting, boot recovery,
Settings *home* screen (real UI, grouped list, matches confirmed mockup —
its 6 detail destinations are still stubs), app shell (bottom-nav Scaffold,
type-safe `NavHost`, theming works end-to-end). **Note:** the theming
*mechanism* works, but the 4 palettes it's currently wired to
(Clinical/Cozy/Neutral/Playful) are the ones §6 just replaced — the code
hasn't caught up to that decision yet (§9 item 0b, same "spec runs ahead
of code, direction of sync is always code-to-spec" rule §10 already
states).

**Not built — literal placeholder text, no data-entry UI exists at all:**
`TodayScreen`, `MedicinesScreen`, `HistoryScreen`. No "Add Medicine"
button, no Add/Edit Reminder form, anywhere. This is *why* a fresh build
shows nothing: the scheduling engine has nothing to schedule because
there's no way to create a `Medicine`/`Reminder` row yet. Not a regression
from the scheduling batch — a known, flagged prerequisite gap (see the
leftover comment in `TodayScreen.kt` itself, and audit §8 above).

---

## 9. Roadmap & open decisions

Ordered by dependency, not just priority — each batch needs the one above
it to already exist. Items **0** and **0b** below are numbered outside
that sequence deliberately: neither is a dependency of #1, both are "do
this before touching the codebase again at all" items, both confirmed
this session.

0. **🚧 Rename batch — mechanical, do first, blocks nothing conceptually.**
   Applies §1's now-confirmed `com.hazia.emivita`/"Emivita" identity to a
   codebase that already shipped under a placeholder one. Scope: `applicationId`
   + `namespace` in `build.gradle.kts`, every `com.personal.medkeeper` package
   statement, `MedKeeperApplication`→`EmivitaApplication`,
   `MedKeeperRoute`→`EmivitaRoute`, `MedKeeperNavHost`→`EmivitaNavHost`,
   `MedKeeperDatabase`→`EmivitaDatabase`, `Theme.MedKeeper`→`Theme.Emivita`,
   `strings.xml` `app_name`, `rootProject.name` in `settings.gradle.kts`.
   `.debug` applicationIdSuffix carries over onto the new id unchanged. Same
   batch, same reason (existing code contradicting an already-decided spec
   position), two small dependency removals confirmed this session:
   - Drop `androidx.health.connect:connect-client` and the two Health-Connect-
     shaped manifest comments — contradicts #13's ❌ Dropped decision, which
     was decided *because* Health Connect needs Play services even on its
     "built-in" path, a direct conflict with §1's de-googled constraint.
   - Drop `androidx.work:work-runtime-ktx` (WorkManager) — see #9's
     housekeeping note; superseded by the debounce+explicit-trigger design
     now specified for both the projection cache (#4) and the auto-sync
     folder (#9).
0b. **🚧 Palette replacement batch — mechanical, do alongside item 0,
    confirmed this session.** Same "outside the dependency sequence,
    existing code contradicts an already-decided spec position" shape as
    item 0, just for §6's palette decision instead of §1's identity one.
    Scope: replace the 4 shipped palette objects (Clinical/Cozy/Neutral/
    Playful) with the 8 named in §6 (Golden Hour, Sunlit Focus, Forest
    Floor, Sage Clinical, Ink & Ledger, Cobalt Porcelain, Rose Petal,
    Velvet Ribbon) — a straight swap, not an addition, since §6 already
    settled that the old 4 are fully replaced. Hex values and every role
    (background/surface/outline/primary/secondary/accent/text/mutedText)
    come from Appendix A, not re-derived. Two things to close out in the
    same batch, not deferred:
    - T3 (§7): resolve `secondary`/`tertiary`/`error`/`onError`/
      `errorContainer` for all 8 palettes in `colorSchemeFor()` — do this
      once across all 8 in this pass rather than per-palette later, same
      reasoning as fixing outline/mutedText once via construction instead
      of patching four separate hex edits (§6, T1/T2).
    - Whatever currently persists a user's chosen palette (if anything
      does yet — not confirmed either way in this spec) needs its stored
      identifier reconciled to the new 8, since the old 4's identifiers
      go away entirely rather than just changing meaning.
1. **❓ Add/Edit Medicine + Add/Edit Reminder UI, wire Today/Medicines to
   real data (minimal).** Blocks everything else — nothing past this point
   is testable without it. "Minimal" here means Today/Medicines show actual
   DB contents, not yet the full timeline/projection UI from §6 — that
   waits on #4. Build against the full schema from §3 (including the seven
   gap-resolution fields and the newly-added `Medicine.sortOrder` /
   `Medicine.barcodeValue` / `Medicine.indication` / `Medicine.storageNote` /
   `Medicine.color` — layout and the toggle-off fallback are locked, §6/§3)
   even though the form only exposes the 3 core types + basic fields at
   first —
   see §4's note on `autoResolve`/active-range clamping being natural to
   wire in alongside this batch. Two open calls, need a decision before
   this starts:
   - Reminder types in the first version of the Add form: **recommended —
     just the 3 the engine actually schedules** (Fixed Time, Repeating
     Interval, Daily Window). Low-Stock/Expiration cards can still appear
     in the picker (matches the confirmed 5-card UX in §6) but land on a
     "coming soon" state rather than silently accepting data nothing will
     ever act on.
   - CRUD scope: **recommended — add + list only this pass**, edit/delete
     as an immediate follow-up. Smaller reviewable diff, and edit needs
     the same form as add anyway once add exists.

   Barcode scanning (§3) also lands in this batch: an optional "Scan
   barcode" action on the Add form (attaches a code to the medicine being
   created) and a "Scan to find" action on the Medicines list (looks up an
   existing medicine by its stored code, falls back to "not found, add
   new?" if nothing matches) — delegated entirely to an installed scanner
   app via `Intent`, no bundled library, no camera permission.
2. **🚧 PRN / ad-hoc dose logging** (§3/§6) — the "Log dose" entry point and
   `logAdhocDose`. Small and largely independent of the Add/Edit Reminder
   form itself; natural to ship right alongside or immediately after #1.
   The Stock & Expiration screen (§6, part of #1) gets a manual **Refill**
   action the same way — `logRefill` doesn't need #8's notification-triggered
   path to be useful on its own.
3. **🚧 Event editing / undo** (§3/§6) — `switchStatus` (stock-correct
   Taken↔Skipped), `editRecordedTime`, `editDoseAmount`, swipe-to-edit
   interaction. Needs #1 shipped first (nothing to edit otherwise).
4. **🚧 Shared occurrence-projection cache** (§2's locked decision; the
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
5. **🚧 Today screen** (full timeline UI from §6) — consumes the
   projection cache.
6. **🚧 History/Calendar screen** — future entries from the projection
   cache, past entries from a direct DB query (medTimer's own split, per
   ADR-0002). Library picks carried over from §5: **Vico** for charts, a
   hand-rolled `SortableTable`, Kizitonwose Calendar's Compose artifact.
7. **🚧 Widgets** (Glance, not RemoteViews — see §5) — reads the
   projection cache rather than running its own simulation.
8. **🚧 `LowStockAlert`/`ExpirationAlert` triggering** — alerts off the
   stock run-out / expiration dates the projection cache already computes.
   No separate `WorkManager` polling job; the cache's existing DB-change
   and alarm-fired triggers keep it current on their own. Its notification
   gets a **Refill** action alongside Take/Skip/Snooze once `logRefill`
   (§3) exists, using `StockInfo.refillQuantity` as the one-tap amount.
9. **🚧 SAF backup/restore + auto-sync folder (password-protected ZIP)**
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
   - 🚧 remaining: the exact "Backup & Sync" Settings copy/layout — no
     longer blocked on a design decision, just needs drafting now that the
     passphrase behavior above is settled. Should make clear, at minimum,
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
10. **🚧 CSV/PDF history export** (§5) — human-readable dose-history
    reports for sharing with a doctor; native `PdfDocument`, no new
    dependency, distinct from #9's JSON backup.
11. **🚧 Biometric app-lock** (`Biometrics.kt` port, §5).
12. **🚧 User preferences (DataStore): snooze duration, weekend mode,
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
14. **🚧 Cyclic, linked (following-dose), and weekend-mode reminders**
    (§3/§4) — schema and engine design are now resolved (`CyclicActivePause`,
    `LinkedFollowing` + cascade reschedule, the weekend adjustment step off
    #12's preferences). What's left is: the `SchedulingEngine` branches for
    the two new types, the cascade-reschedule trigger points, the weekend
    adjustment step itself, and Add-Reminder-form UI work — **only for
    `LinkedFollowing`**, since it's the one actual new top-level
    `ReminderConfig` variant (R8, §7: an earlier draft of this item wrongly
    scoped `CyclicActivePause` in here too, "a 6th and 7th card"; per §3/§6
    it's a 4th `RepeatPattern` case living inside the already-existing
    "Fixed Time" card, needing no new top-level picker card at all). So:
    a 6th card beyond §6's original 5 for `LinkedFollowing` (or nested
    under an "Advanced" entry point the way medTimer does it — not yet
    decided, smaller call than the schema work above), plus revealing
    `CyclicActivePause` as a 4th option in Fixed Time's existing
    `RepeatPattern` selector. Depends on #12 for the weekend-mode piece
    specifically; the cyclic/linked piece doesn't.
15. **Cheap fixes, whenever convenient** (§7's still-open rows: R1, R2, R3,
    `asOnSurfaceSafe`, launcher icon, placeholder copy — the last one
    partially resolves itself once #1 ships real screens).
16. **EncryptedSharedPreferences → DataStore+Tink migration** — deferred by
    design (§2), not urgent, revisit if the artifact is ever actually
    removed rather than just deprecated.

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
