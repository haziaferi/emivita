# Emivita legacy spec

The sections of the old `docs/SPEC.md` not yet migrated to `SPEC.md` (R149, R150). Each section below stays authoritative until a migration pass moves it and leaves a Moved line in its place. `spec-evidence/identifier-map.md` maps every old identifier to its new place.

Status key used throughout: 🔒 locked (don't revisit without a real reason) ·
✅ built · 🚧 designed but not built · ❓ open, needs a decision.

Moved: the source-of-truth statement and "Where everything went" to `SPEC.md` R149 and R150.

---

## 1. Purpose & constraints (moved)

Moved to `SPEC.md`: R147, R148, R152, R182.

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
- **DB recovery (§9 #32, §7 R71):** `EmivitaDatabase.open` builds, probes
  with a real query (`profileDao().count()` — Room opens lazily, so building
  proves nothing), and if the probe throws it closes, renames the file and its
  journal/WAL companions to `emivita.db.unopenable-<millis>`, rebuilds, and
  re-probes; a second failure is rethrown, never a second set-aside. **Nothing
  is ever deleted by the app on its own initiative**: the set-aside files are
  listed under Data & Backup (count, size, date) and removed only by the one
  button there, behind the app lock. A recovery writes an on-disk marker
  (`RecoveryNotice`, SharedPreferences) that the next launch answers with a
  blocking dialog before the lock — "Restore from backup" opens Data & Backup,
  "Continue empty" does not — so an app that had to start empty never does so
  silently. SQLCipher-specific fact this rests on: its `SupportHelper` never
  calls Room's `Callback.onCorruption` and its own error handler refuses to
  delete when a codec is loaded, so the corrupt file survives to be set aside
  without the keep-file wrapper the framework factory needs (Lunar's finding);
  `DatabaseRecoveryDeviceTest` proves that on the phone, `DatabaseFileTest`
  pins the framework's deletion on the JVM.
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

Since §9 #31 (R72): a daily window may end after midnight (the end is the
next day's; a slot belongs to the calendar day it falls on); a linked
follow-up is projected off its parent's *projected* slot as an estimate
(`ProjectedOccurrence.estimated`, shown "≈") until the parent's real dose
anchors it; the projection is published per medicine (`inProgress`) and its
compute budget is checked per reminder.

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
- **Medicine card:** tonal card, the medicine's colour as its icon tile
  (§7 R76; was full-card colour), icon-per-medicine-type, tags as chips.
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
  `EmivitaRoute.StockExpiration`) — one screen, three zoned sections, each
  a tonal card; the two that warn carry the attention glyph in their title
  (§7 R76; until then the three were painted `primaryContainer` /
  `tertiaryContainer` / `errorContainer`, which were the base roles, so
  they were solid slabs — §7 R75). T5's register distinction is carried by
  the titles and the glyph, not by fills.
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
- **Palettes: 4, each with light + dark — and one grammar for applying
  them (🔒 re-decided by the owner, §7 R76).** The third full replacement.
  R56's six had all come from the lexicon's *Health* entry (its pairs are
  named *Daylight* and *Linen*), paired off two by two, and read as stock;
  and `colorSchemeFor` made every M3 container its base role, so any screen
  that reached for one got a saturated slab (Stock, §7 R75).

  **How colour is applied comes first, and it is the reference app's:**
  every card is tonal (`surfaceContainer`, one step off the ground), the
  palette's hue lives in controls and mostly as the pale `primaryContainer`
  (the state disc, the Prendi chip, the tab pill, the FAB), solid `primary`
  only on the toggle and the timeline rail, a medicine's own colour is the
  one saturated thing on a card (its icon tile, ink chosen by luminance),
  and a warning is a glyph beside the text — `attention`, burnt orange,
  never yellow, never a fill; red stays for errors. `AlphaTintGateTest`
  keeps it so.

  | Palette | Character | Seed (visual-craft lexicon) |
  |---|---|---|
  | Porcellana | cobalt on porcelain white — the default | Ming blue-and-white |
  | Ardesia | steel blue on cool paper | Slate Scholastic |
  | Sangue di bue | oxblood on neutral grey | Tense · Held Breath |
  | Sabbia | taupe on warm white, the near-neutral | Serene · Chalk |

  Chosen by the owner from ten candidates, with the rules stated: no purple
  ("always reads like AI"), no yellow, no muddy green ("associated with
  sickness"). Each is three agreed colours per register — ground, text,
  primary — and everything else is derived by `tools/palette_derive.py`,
  which prints `Color.kt` and Appendix A and fails when the appendix
  drifts; `PaletteSpecConsistencyTest` fails when the code does. Every
  colour that can sit on a card is fitted against the darkest card tier.
  The ground is near-neutral in all four, which is what lets any hue from
  the medicine picker read as the medicine's and not the theme's.

  **The default is Porcellana**, the owner's choice. Both `splash_background`
  resources must equal its light and dark grounds; the consistency test
  enforces both (the night one had sat at a warm brown matching no palette
  from R56 to R75). `SettingsRepository.palette` maps the six names the
  previous build stored to their nearest of the four and falls back to the
  default for anything else. **Appearance applies on the tap** — the mode
  chips and the four cards, each drawn in its own palette, inside one
  collapsed Tema section; the Save button below the fold, the reason R75's
  pass chose a palette and saw nothing change, is gone.
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

## 7. Audit findings ledger (moved)

Moved: the ledger rows to `docs/spec-evidence/findings-ledger.md`, frozen at R146; the rules of 7.1 to `docs/SPEC.md`.

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

**Current palette set: the 4 of Appendix A** (Porcellana, Ardesia, Sangue
di bue, Sabbia), printed into `Color.kt` by `tools/palette_derive.py` and
wired in `colorSchemeFor()`, whose container slots are real tints since §7
R76. They replaced R56's six, which replaced item 0b's eight — §7 R56 and
R76 for why each register changed. One role beyond Material3's ColorScheme
travels on `LocalEmivitaColors`, read as `EmivitaTheme.colors.attention`;
every M3 slot is spoken for, and borrowing one whose name means something
else is the overload R56 exists to undo. The saved palette is applied by
`SavedEmivitaTheme` in every Activity, by `WidgetTheme` in both widgets and
as the notifications' accent.

**Notifications & Alerts** (`NotificationsAlertsScreen`): lists the 3 real
notification channels (Standard/High/Alarm) with plain-language
descriptions and a button deep-linking to Android's own
`ACTION_APP_NOTIFICATION_SETTINGS` for per-channel sound/vibration — a
deliberate scope boundary, not a stub: Android doesn't let an app override
a user's own per-channel choice once the channel exists, so there's
nothing further for in-app UI to do here.

**Appearance** (`AppearanceScreen`): a collapsed "Tema" section holding
the theme-mode chips (Light/Dark/System/AMOLED) and the four palette cards,
each drawn in its own palette; the four Today-layout cards (§9 #37); the
start page; language and time format — every one applied on the tap,
`SettingsRepository`-backed, no Save (§7 R76). This is the *only* place any
of them is chosen: the onboarding screen this paragraph used to compare
against was deleted outright (§9 #1's reversal), so a fresh install takes
`SettingsRepository.palette`'s default — Porcellana, the owner's choice
(§6) — with no prompt.

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

19. **✅ Internationalisation + accessibility — Stages A ✅, B ✅ and C ✅ complete.**
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

    **C ✅ (2026-09-13, §7 R67, §9 #28):** the owner chose Italian. An in-app
    language picker (system-registered on Android 13+, context-wrapped on
    11–12), a time-format choice that also fixes the 24-hour defect, and
    `values-it` complete — 504 strings, 9 plurals — behind
    `TranslationCompletenessTest`, which fails on a missing or extra key, a
    changed format argument, a plurals without one/other, or a value left in
    English. The next language is one folder, one list entry, and the gate.

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
    **2026-10-20 (§7 R102):** the owner's rule now: medTimer's features are
    adopted as-is unless scoring on axes the owner picks shows a variant
    is better; the axes are asked first.

22. **✅ Verified-defects batch — shipped.** §7 R61. The twenty S-sized
    findings the medTimer diff verified (Appendix E.3), built in five
    groups: alarms and receivers, stock, data, display and locale, settable
    columns. 227 tests. Two things deliberately not in it: `Medicine.notes`
    (owner's call: wire or drop) and reminder pause/delete, which opens
    #24. The device suite is owed a re-run for the alarm-slot change.

23. **✅ Cross-app diff — done; adoption re-ranked.** §7 R62, Appendix F.
    Five GPL apps read at delta depth, two sources online, 154 rows,
    six claims spot-verified. Emivita is ahead of the field on alarms,
    stock, recovery, encryption, accessibility and tests; the field is
    ahead on the medicine-cabinet model, notes journals, PRN headroom,
    quiet lock-screen answers, missed-dose back-fill and ranged reports.
    Appendix F.5 is E.4 re-ranked with those in; #24–#26 below are scoped
    from it. Inspiration only — no GPL code enters the tree.

24. **✅ Reminder management — shipped.** §7 R63. Pause switch on the detail
    row, delete in the edit form, duplicate (with/without reminders),
    pause/resume all, four persisted sort keys, hand-rolled drag-reorder
    with accessibility actions, hide-empty, and the card state line (due /
    done / later today, out of stock, runs out in N days, expired, course
    state). 246 tests. Owed a device look: the drag feel, and the card tint
    on the six palettes.

25. **✅ Notification polish — shipped.** §7 R64. Swipe setting (default
    Snooze), public lock-screen version, "N left", chime window, hidden
    names, alarm tone and silent-mode switches, setAlarmClock opt-in, test
    reminder, locale-following channel names, boot/launch re-raise of
    unanswered doses, per-reminder pre-alarm (DB v7), grouped "Take all",
    ask-each-time snooze. 259 tests. Declined by the owner: day-end expiry,
    DND override.

26. **✅ History as a record — shipped.** §7 R65. DB v8: dose notes, a
    `DELETED` status that keeps its slot claimed, per-reminder instructions
    and a dose-count course, archive instead of delete, the as-needed
    ceiling, a notes journal. Missed back-fill on launch and boot (60-day
    cap); stock recount on an amount edit; return to due, move, soft delete
    from the dose dialog; as-needed standing rows with time since last;
    backdated logging with headroom and a confirm at the ceiling; "Take now"
    on a projected slot; archived list under Settings › Data; ranged,
    per-medicine export with a Notes column. 287 tests. Decided by the owner:
    back-fill as Missed, Taken-at-fire kept, archive over cascade, warn never
    refuse. Not built: early-take window (MT-09), amount-before-confirm
    (DS-11), day-end auto-confirm (DS-02). Owed a device look: the journal
    on a small screen, the export chip row.

27. **✅ Medicine cabinet — shipped.** §7 R66 (Appendix F.5 group 4). DB
    v9: strength, Rx number, opened date + "use within N days" with an
    effective expiry the alert, the card and the sort all read. The icon is
    the dosage form (default unit fills a blank field, never a typed one);
    month/year expiry picker storing the month's last day with an exact-day
    switch; tags as kits (group-by-tag sections, a persisted tag filter with
    a badge); live search over name / strength / indication / tags; the
    Medicines tab badge and one summary notification over the stock alerts.
    311 tests. Decided by the owner: icon-as-form over a catalogue, tags over
    a Kit entity, month/year + opened-date, strength + Rx only, in-memory
    search over FTS, badge **and** summary, grouping in the sort menu with
    the filter persisted. Not built: photos (HM-04), versioning (MT-02),
    alias/composition/recommendations, per-tag ordering. Owed a device
    look: the month picker, sticky headers on the palettes, the badge in
    dark mode.

28. **✅ Stage C: language, time format, Italian — shipped.** §7 R67
    (Appendix F.5 group 5). `AppLocale` (LocaleManager on 13+, context wrap on
    11–12, locale config generated), a Language row and a Time format group
    on Appearance, `LocaleFormats.time` honouring the phone's 24-hour switch,
    `values-it` complete and gated. 322 tests. Decided by the owner: Italian in
    this batch via `correttore-italiano`; in-app list everywhere; time only,
    default Follow the phone; no start page or AMOLED. Owed a device look: the
    Italian wording itself, screen by screen.

29. **✅ Platform hygiene — shipped.** §7 R68 (Appendix F.5 group 6). The
    app lock falls back to the phone's PIN; a missed armed alarm is said so
    once (DP-F2 closed); back from a touched form asks; a first-launch
    disclaimer; Delete everything behind the lock; OEM auto-start deep links;
    a four-row permissions section; AMOLED black; a start page; and the
    backup coverage guard (E-16). 340 tests. Decided by the owner: all nine
    items, everything in the existing screens, and **MK-10 time-zone travel
    declined** — wall-clock is right for a medication app. HM-F1 kept. Owed
    a device look: the PIN lock, AMOLED on the palettes, the auto-start
    button.

30. **✅ Tests before the engine moves — shipped.** §7 R70 (E.4 group 8's
    B-47 and group 7's simulator port, in dependency order ahead of the
    engine edges). The two receivers run on the JVM through the production
    container over in-memory Room, reached by the armed PendingIntent's own
    intent (21 cases); medTimer's `SchedulingSimulatorTest` ported (six
    parities, four divergences pinned — A-20 and A-24 are what #31 flips).
    Found: every settings write re-armed every reminder
    (`useAlarmClock` without `distinctUntilChanged`) — fixed, pinned. 372
    tests. Decided by the owner: proceed by dependency — this, then #31
    engine edges, then the remaining quality gates, then one device pass,
    then a release build.

31. **✅ Engine edges — shipped.** §7 R72 (E.4 group 7). A daily window
    can end after midnight (the form says so; the slot belongs to the
    calendar day it falls on); a linked follow-up is projected off its
    parent's projected slot as "≈ 09:00 · Estimated: 60 min after the 08:00
    dose", and the stock run-out counts it; the compute budget is per
    reminder and keeps what was walked; the projection is published as it
    goes. 395 tests, device 7/0/0. Decided by the owner: the calendar day;
    the estimate marker. Owed on the device: an overnight window across
    midnight on Today; an estimated row and the widget's "≈".

32. **✅ Database recovery — shipped.** §7 R71 (Lunar's `onCorruption`
    finding, checked here). Emivita had no recovery at all: a corrupt file
    crashed the app on every launch. Now `openOrRecover` sets the file aside
    (never deleted; listed and deletable under Data & Backup behind the lock)
    and a blocking dialog before the lock says so once, with Restore from
    backup / Continue empty. Proven on the real SQLCipher factory on the
    phone — no keep-file wrapper needed, unlike the framework factory. 383
    tests, device 7/0/0. Decided by the owner: keep every set-aside, list it;
    the blocking dialog. Owed on the device: the dialog itself (needs a
    corrupt file, so not provoked on the owner's phone).

33. **✅ Quality gates — shipped.** §7 R73 (E.4 group 8's last two code
    gates). The backup fuzz: medTimer's list, Emivita's edges, 200 seeded
    mutants and 50 zip flips — the parsers' contract held on every one, and
    the transaction leaves the database untouched when a snapshot is
    refused. Six layout-bounds tests at 360 and 320 dp; the header one found
    History's 80 dp header against Today's 67 and the three tabs now share
    `TabHeader`. 407 tests. Decided by the owner: fuzz depth, layout scope,
    and the device pass on a release build. F-19..21 wait for CI billing.

34. **✅ Device pass on the release build — run, paused at Appearance.** §7
    R75. Live with the owner at the phone on seeded data (`PassSeedTest` /
    `PassCleanupTest`, gated on `seedPass`): the Alarm tier rang, Today,
    Medicines, the detail, Stock, History, Settings and Appearance were
    walked. Fixed in the batch: the detail's nested Scaffold, the chart that
    had never shown a bar on the phone, the AMOLED chip wrap, "Cancella".
    Recorded: the one-shot tone when unlocked (fixed, R80: `AlarmRingService`
    rings until answered), the biometric row (measured, R78–R79: the phone
    had no credential stored), the suite that wipes the phone. The Stock slabs and the Appearance screen stopped
    the pass and became #36; what remains of the list (Italian sweep, PIN,
    auto-start, widget "≈", TalkBack, splash, the Alarm channel) is #36's
    device look, on the new palettes. **Desk half closed in §7 R81** (the
    Italian sweep done, auto-start and the Alarm channel turned into fixes,
    splash light and the TalkBack tree checked over adb, seven live controls
    made single nodes); what needs the owner at the phone — the widget, ears
    on TalkBack, dark mode, blocking the channel, the four wording picks — is
    the sitting listed at the end of R81.

35. **✅ CI gates that cannot run yet — shipped.** §7 R74. CodeQL and
    Scorecard workflows (each saying where it fails on a private repository
    without Advanced Security), ci.yml as two parallel jobs (the triad; the
    release build with the R8 gate), every action pinned by SHA, and
    `tools/workflow_check.py` — pins resolved, inputs checked against each
    action's own `action.yml`, actionlint — because the account's billing
    block means no run can be watched. Decided by the owner: those two
    workflows now, dependency-review with the first PR, actionlint + the pin
    check as the gate. Nothing of E.4 remains as code. #34 stays next.
    **They run now (§7 R86):** the repository is public, the three
    workflows are green, dependency review came with the first PR, and
    `master` takes changes only by pull request with green CI.

36. **✅ Four palettes, the reference app's colour grammar, Appearance
    live — shipped.** §7 R76. Decided with the owner before anything was
    written: medTimer's grammar (tonal cards, the hue as the pale container
    in controls, the medicine's colour as one tile, warnings as glyphs);
    Porcellana (default), Ardesia, Sangue di bue, Sabbia, seeded from the
    visual-craft lexicon with no purple, yellow or muddy green and derived
    by `tools/palette_derive.py`, which prints Color.kt and Appendix A and
    checks the appendix; attention burnt orange. Appearance applies on the
    tap inside a collapsed Tema section, no Save. The palette now reaches
    the alarm activities, the widgets and the notifications.

37. **✅ The four Today layouts — shipped.** §7 R77. Timeline, by time of
    day, compact register, next dose first — the owner's mockups as an
    Appearance option, the same rows and actions in all four, no single tap
    changing a dose.

38. **✅ Decisions the whole-tree audit raised — settled by the owner and
    shipped.** §7 R82 listed them, §7 R83 records the batch. The owner's
    answers, not to be re-opened:
    (a) **Every form keeps its input** through a rotation, a theme or language
    switch and a process death (`rememberSaveable`, not `configChanges`); the
    lock keeps its unlock across a configuration change and re-locks on every
    real stop.
    (b) Drag-to-reorder is keyed on the item, so the drop fires — a bug, fixed.
    (c) **A parent delayed by the phone rings as soon as it can, and its
    follow-up comes the set delay after the real ring.** The owner considered
    and dismissed a ±10-minute ring window. And the app **asks for every grant
    reminders depend on**: a one-time setup sheet on first launch, a Today
    card when one goes missing later ("Not now" holds per set of missing
    grants), and the battery exemption through the system's one-tap dialog,
    so the manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (kept out
    since R39 on least-privilege grounds; the app is sideloaded, §1).
    (d) The ring service stays in the foreground while it rings — a bug, fixed.
    (e) A snoozed dose is not re-posted on launch, and a reboot no longer
    loses a running snooze — a bug, fixed.
    (f) **A lost Keystore key starts the app empty with honest words**: the
    data cannot be read, now or later; restore a backup. The app warns ahead
    of time, always on Data & Backup and on a Today card until the first
    backup is written.
    The two computed file splits and the comment cap were applied in the same
    batch.

### Not yet scoped — parking lot ❓

- **Salvage rows from a set-aside database** (§9 #32): the file is
  encrypted with the same Keystore passphrase, so a read-only open with
  `PRAGMA integrity_check` and a per-table copy is possible — worth it the
  day a set-aside file actually exists.

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

## 10. Working agreements (moved)

Moved to `SPEC.md`: R149 to R159.

---

## Appendix A — Palette token reference (full tables)

Referenced from §6's Palettes bullet. Every hex here is derived, not
eyeballed: the four palettes are three agreed colours per register — ground,
text, primary — from named, contrast-verified pairs in the visual-craft
lexicon, chosen by the owner from ten candidates (§7 R76), and every other
role is derived from those three by the same rules for all four in
`tools/palette_derive.py`. **The tables below are printed by that tool**
(`--tables`), as is `Color.kt` (`--kotlin`); `python tools/palette_derive.py
--check` fails when this appendix drifts from the tool, and
`PaletteSpecConsistencyTest` fails when the code drifts from this appendix.
Nothing in this appendix is typed by hand.

**This set replaced R56's six (Daylight → Periwinkle) on 2026-09-14 — see
§7 R76.** The six came from the lexicon's *Health* entry, whose pairs are
named Daylight and Linen; the owner's verdict was that they read as stock.

**Role legend** (Material 3's names, so a screen reaches for the role the
tables audit):

- **background / surface** — the page ground; one colour, never a hue
- **surfaceContainer / surfaceContainerHigh** — the two card tiers, the
  ground blended 4.5 % and 9 % toward the text (6 % and 11 % in dark). Every
  card in the app is one of these two: the reference app's grammar (§6)
- **onSurface / onSurfaceVariant** — text and muted text; muted is fitted to
  ≥ 4.5:1 against the *high* card, not the ground
- **outline / outlineVariant** — a border that should be seen (≥ 3:1 on the
  ground) and a hairline divider that should not compete
- **primary / onPrimary** — the palette's hue as a solid: the toggle, the
  timeline rail, a filled button; fitted to ≥ 4.5:1 against the high card
  so it can also be text (the Salta chip)
- **primaryContainer / onPrimaryContainer** — the pale tint the hue mostly
  appears as: the state disc, the Prendi chip, the tab pill, the FAB, the
  selected day; its text at ≥ 7:1
- **attention** — *a missed dose, low stock, an approaching expiry*, drawn as
  a glyph beside the text and never as a fill. Burnt orange in all four —
  the owner rules out yellow — and deliberately **not red**
- **error / onError / errorContainer / onErrorContainer** — **genuine
  failures only**: validation, a failed backup, a permission the app cannot
  work without (the three permission cards are the one `errorContainer`)

Retired with R76: `secondary`, `tertiary` (aliased to the primary roles and
the high card in `colorSchemeFor` — nothing asks for a third hue),
`neutralFill` (a skip is drawn with `outline`) and `onAttention` (nothing
is filled with `attention`).

**Checks.** Every line under a table is what the tool measured for that
register: text on the ground and on the high card, muted text on both,
outline on the ground, primary on the ground, the two `on*Container` pairs,
the pale container against the ground (≥ 1.3:1, so the tint is visible),
attention and error on the ground and on the high card, `onError` on error.
`PaletteContrastTest` asserts the same pairs against the `ColorScheme` the
app actually builds, in light, dark **and AMOLED** (the dark register with
`background`/`surface` at `#000000`, the cards keeping their dark tone).
The consistency test also pins **both** `splash_background` resources
(`values/`, `values-night/`) to the default palette's grounds — the night
one had sat at a warm brown matching no palette from R56 to R75.

**Register.** The ground is near-neutral in all four and the hue is
carried by the controls, so a medicine's own colour — any hue, from a free
HSV picker — is the one saturated thing on a card and reads as *the
medicine's*, never as the theme's. Dark grounds stay above 5 % lightness
except in AMOLED, where the ground is switched off on purpose; no pure
white text anywhere; dark-mode text/ground contrast held near 13–15:1
rather than pushed to extremes.

### 1 · Porcellana — Ming blue-and-white (visual-craft lexicon, world traditions)

| Role | Light | Dark |
|---|---|---|
| background | `#F5F6F8` | `#0F141C` |
| surface | `#F5F6F8` | `#0F141C` |
| surfaceContainer | `#EBEDF1` | `#1C2129` |
| surfaceContainerHigh | `#E1E5EB` | `#262B33` |
| onSurface | `#143667` | `#E4E8EF` |
| onSurfaceVariant | `#4F6A78` | `#8E9296` |
| outline | `#7691A6` | `#60656C` |
| outlineVariant | `#CDD3DE` | `#353A42` |
| primary | `#17488C` | `#8FB0E0` |
| onPrimary | `#F5F6F8` | `#212A36` |
| primaryContainer | `#BBC9DC` | `#45566E` |
| onPrimaryContainer | `#143767` | `#F1F3FB` |
| attention | `#A05027` | `#E0925F` |
| error | `#9E2A25` | `#F09590` |
| onError | `#F5F6F8` | `#3A0B08` |
| errorContainer | `#E2C9CA` | `#654548` |
| onErrorContainer | `#572F45` | `#F8E8E9` |

Checks — Light: text on ground 11.08 · text on the high card 9.47 · muted on ground 5.3 · muted on the high card 4.53 · outline on ground 3.05 · primary on ground 8.29 · onPrimary on primary 8.29 · onPrimaryContainer on primaryContainer 7.05 · primaryContainer against ground 1.55 · attention on ground 5.29 · attention on the high card 4.52 · error on ground 6.91 · error on the high card 5.91 · onError on error 6.91 · onErrorContainer on errorContainer 7.08. Dark: text on ground 15.03 · text on the high card 11.58 · muted on ground 5.89 · muted on the high card 4.54 · outline on ground 3.14 · primary on ground 8.31 · onPrimary on primary 6.52 · onPrimaryContainer on primaryContainer 6.75 · primaryContainer against ground 2.47 · attention on ground 7.43 · attention on the high card 5.72 · error on ground 8.27 · error on the high card 6.37 · onError on error 7.64 · onErrorContainer on errorContainer 7.09.

AMOLED ground `#090C11` (dark scheme, ground and surface swapped) — checks: text on ground 15.94 · text on the high card 11.58 · muted on ground 6.25 · muted on the high card 4.54 · outline on ground 3.33 · primary on ground 8.82 · onPrimary on primary 6.52 · onPrimaryContainer on primaryContainer 6.75 · primaryContainer against ground 2.62 · attention on ground 7.88 · attention on the high card 5.72 · error on ground 8.77 · error on the high card 6.37 · onError on error 7.64 · onErrorContainer on errorContainer 7.09.

### 2 · Ardesia — Slate Scholastic (lexicon, contexts)

| Role | Light | Dark |
|---|---|---|
| background | `#EEF4F6` | `#152328` |
| surface | `#EEF4F6` | `#152328` |
| surfaceContainer | `#E4EBED` | `#223034` |
| surfaceContainerHigh | `#DBE2E4` | `#2D3A3F` |
| onSurface | `#19282E` | `#F3F5F6` |
| onSurfaceVariant | `#5D656A` | `#9BA2A3` |
| outline | `#818E96` | `#697376` |
| outlineVariant | `#C8CFD2` | `#3D494D` |
| primary | `#2D6276` | `#7FB2C6` |
| onPrimary | `#EEF4F6` | `#1E2C32` |
| primaryContainer | `#BCCED5` | `#425F6A` |
| onPrimaryContainer | `#1C3E49` | `#EBF3F8` |
| attention | `#9C4E26` | `#E0925F` |
| error | `#9E2A25` | `#F09590` |
| onError | `#EEF4F6` | `#3A0B08` |
| errorContainer | `#DCC8C8` | `#684E50` |
| onErrorContainer | `#5C292A` | `#FAE9E9` |

Checks — Light: text on ground 13.66 · text on the high card 11.57 · muted on ground 5.35 · muted on the high card 4.53 · outline on ground 3.03 · primary on ground 6.06 · onPrimary on primary 6.06 · onPrimaryContainer on primaryContainer 7.05 · primaryContainer against ground 1.46 · attention on ground 5.35 · attention on the high card 4.53 · error on ground 6.72 · error on the high card 5.69 · onError on error 6.72 · onErrorContainer on errorContainer 7.28. Dark: text on ground 14.74 · text on the high card 10.74 · muted on ground 6.21 · muted on the high card 4.52 · outline on ground 3.31 · primary on ground 6.97 · onPrimary on primary 6.22 · onPrimaryContainer on primaryContainer 6.08 · primaryContainer against ground 2.36 · attention on ground 6.48 · attention on the high card 4.72 · error on ground 7.22 · error on the high card 5.26 · onError on error 7.64 · onErrorContainer on errorContainer 6.41.

AMOLED ground `#090F11` (dark scheme, ground and surface swapped) — checks: text on ground 17.65 · text on the high card 10.74 · muted on ground 7.44 · muted on the high card 4.52 · outline on ground 3.97 · primary on ground 8.35 · onPrimary on primary 6.22 · onPrimaryContainer on primaryContainer 6.08 · primaryContainer against ground 2.83 · attention on ground 7.76 · attention on the high card 4.72 · error on ground 8.65 · error on the high card 5.26 · onError on error 7.64 · onErrorContainer on errorContainer 6.41.

### 3 · Sangue di bue — Tense · Held Breath (lexicon, moods)

| Role | Light | Dark |
|---|---|---|
| background | `#E9E7E4` | `#0E0F11` |
| surface | `#E9E7E4` | `#0E0F11` |
| surfaceContainer | `#E0DEDB` | `#1A1B1D` |
| surfaceContainerHigh | `#D6D4D2` | `#242526` |
| onSurface | `#17181A` | `#D8D6D2` |
| onSurfaceVariant | `#5C5C5C` | `#8C8C8C` |
| outline | `#858483` | `#60605F` |
| outlineVariant | `#C3C2C0` | `#323334` |
| primary | `#6E241F` | `#D96B5E` |
| onPrimary | `#E9E7E4` | `#311D1C` |
| primaryContainer | `#C9B4B1` | `#633631` |
| onPrimaryContainer | `#471F1D` | `#F1D1CF` |
| attention | `#904823` | `#E0925F` |
| error | `#9E2A25` | `#F09590` |
| onError | `#E9E7E4` | `#3A0B08` |
| errorContainer | `#D8BDBA` | `#644241` |
| onErrorContainer | `#5A2120` | `#F2D8D8` |

Checks — Light: text on ground 14.4 · text on the high card 12.02 · muted on ground 5.42 · muted on the high card 4.52 · outline on ground 3.02 · primary on ground 8.79 · onPrimary on primary 8.79 · onPrimaryContainer on primaryContainer 7.19 · primaryContainer against ground 1.6 · attention on ground 5.42 · attention on the high card 4.53 · error on ground 6.05 · error on the high card 5.05 · onError on error 6.05 · onErrorContainer on errorContainer 7.12. Dark: text on ground 13.21 · text on the high card 10.58 · muted on ground 5.7 · muted on the high card 4.57 · outline on ground 3.05 · primary on ground 5.68 · onPrimary on primary 4.7 · onPrimaryContainer on primaryContainer 7.02 · primaryContainer against ground 1.92 · attention on ground 7.71 · attention on the high card 6.18 · error on ground 8.59 · error on the high card 6.88 · onError on error 7.64 · onErrorContainer on errorContainer 6.5.

AMOLED ground `#0C0C0E` (dark scheme, ground and surface swapped) — checks: text on ground 13.47 · text on the high card 10.58 · muted on ground 5.81 · muted on the high card 4.57 · outline on ground 3.1 · primary on ground 5.78 · onPrimary on primary 4.7 · onPrimaryContainer on primaryContainer 7.02 · primaryContainer against ground 1.96 · attention on ground 7.86 · attention on the high card 6.18 · error on ground 8.75 · error on the high card 6.88 · onError on error 7.64 · onErrorContainer on errorContainer 6.5.

### 4 · Sabbia — Serene · Chalk (lexicon, moods)

| Role | Light | Dark |
|---|---|---|
| background | `#F5F3EF` | `#1B1917` |
| surface | `#F5F3EF` | `#1B1917` |
| surfaceContainer | `#ECEAE6` | `#272522` |
| surfaceContainerHigh | `#E4E2DE` | `#312F2C` |
| onSurface | `#35322D` | `#E4DFD6` |
| onSurfaceVariant | `#696361` | `#999791` |
| outline | `#8F8A84` | `#686561` |
| outlineVariant | `#D2D0CC` | `#3F3D39` |
| primary | `#6F634B` | `#C0AE8E` |
| onPrimary | `#F5F3EF` | `#2E2A23` |
| primaryContainer | `#D2CEC4` | `#605849` |
| onPrimaryContainer | `#413B31` | `#F7ECEA` |
| attention | `#9C4E26` | `#E0925F` |
| error | `#9E2A25` | `#F09590` |
| onError | `#F5F3EF` | `#3A0B08` |
| errorContainer | `#E2C7C3` | `#6C4845` |
| onErrorContainer | `#612925` | `#F9EBE9` |

Checks — Light: text on ground 11.52 · text on the high card 9.86 · muted on ground 5.33 · muted on the high card 4.56 · outline on ground 3.09 · primary on ground 5.32 · onPrimary on primary 5.32 · onPrimaryContainer on primaryContainer 7.05 · primaryContainer against ground 1.42 · attention on ground 5.37 · attention on the high card 4.6 · error on ground 6.74 · error on the high card 5.77 · onError on error 6.74 · onErrorContainer on errorContainer 7.12. Dark: text on ground 13.21 · text on the high card 10.06 · muted on ground 6.0 · muted on the high card 4.57 · outline on ground 3.02 · primary on ground 8.09 · onPrimary on primary 6.58 · onPrimaryContainer on primaryContainer 6.07 · primaryContainer against ground 2.5 · attention on ground 7.05 · attention on the high card 5.37 · error on ground 7.85 · error on the high card 5.98 · onError on error 7.64 · onErrorContainer on errorContainer 6.82.

AMOLED ground `#0E0D0C` (dark scheme, ground and surface swapped) — checks: text on ground 14.63 · text on the high card 10.06 · muted on ground 6.65 · muted on the high card 4.57 · outline on ground 3.35 · primary on ground 8.96 · onPrimary on primary 6.58 · onPrimaryContainer on primaryContainer 6.07 · primaryContainer against ground 2.76 · attention on ground 7.81 · attention on the high card 5.37 · error on ground 8.7 · error on the high card 5.98 · onError on error 7.64 · onErrorContainer on errorContainer 6.82.

## Appendix B (moved)

Moved to `docs/spec-evidence/appendix-b-ux-audit.md`.

## Appendix C (moved)

Moved to `docs/spec-evidence/appendix-c-theme-review.md`.

## Appendix D (moved)

Superseded before the migration; left to git: `git show 82dabce:docs/SPEC.md`.

## Appendix E (moved)

Moved to `docs/spec-evidence/appendix-e-medtimer-diff.md`.

## Appendix F (moved)

Moved to `docs/spec-evidence/appendix-f-cross-app-diff.md`.
