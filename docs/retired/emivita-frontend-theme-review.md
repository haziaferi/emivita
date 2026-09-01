# Emivita — Frontend Layout & Theme Palette Review

Reviewed against: `color-optimization-core`, `accessibility-color-auditor`,
`typography-spacing-core`, `layout-architecture-core` / `app-register-layout`,
and `visual-craft` (design-lexicon).

**What's actually reviewable right now:** only `ui/theme/{Color,Theme,Type}.kt`
and `SettingsScreen.kt` are built. Today/Medicines/History are placeholder
screens per the spec's own §7/§8 status — so those are reviewed against
§6's *described* layout, not shipped code.

---

## 1. Palette provenance — better-grounded than it looks

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

## 2. Harmony analysis (`color-optimization-core`)

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

## 3. Accessibility audit (`accessibility-color-auditor`, script-verified)

Ran `scripts/audit_colors.py` against the two Settings-screen color usages
that are actually shipped in `SettingsScreen.kt` today.

### 3a. Card border / divider (`MaterialTheme.colorScheme.outline` on white `surface`)

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

### 3b. Row description text (`onSurface.copy(alpha = 0.6f)` on white)

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

Notably, this is the *exact* problem `docs/ux-audit.md` already flagged
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

## 4. Semantic state-token system (§6) — designed further than it's wired

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

## 5. Typography (`typography-spacing-core`)

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

## 6. Layout & motivational register (`app-register-layout`)

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

## 7. Prioritized action list

1. 🔴 **Outline/border contrast** — every palette's `LightOutline` fails
   3:1 against `LightSurface` (1.48–1.68:1). Affects the shipped
   `SettingsScreen.kt` card borders and dividers today. Fix as a rule
   ("outline must clear 3:1 against Surface, not just Background"), not
   four one-off hex edits.
2. 🔴 **Row description text contrast** — the `onSurface.copy(alpha=0.6f)`
   technique fails AA normal text on all four palettes (3.38–3.97:1).
   Replace with a real audited `mutedText` token per palette — this is
   the fix `ux-audit.md` finding #8 already called for; the opacity
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
