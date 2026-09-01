# Emivita — Palette Candidates

Eight directional candidates — two each in amber/yellow, green, blue, and
pink/blush — each with a light and dark register. Built against
`visual-craft`'s `design-lexicon.md` (inspired-by, not exact-hex-match per
the brief) and adapted for phone screens per `mobile-screen-colors.md`, plus
a reasoned astigmatism/eye-comfort pass beyond what's in the skill files
(flagged inline below).

None of these are meant to replace the existing four (Clinical, Cozy,
Neutral, Playful) outright — they're a wider set of directions to choose
from, since the original four were picked without discussion.

Every hex below is computed, not eyeballed — contrast ratios are WCAG
relative-luminance calculations, verified with a script, not estimates.

---

## How to read each entry

- **bg** — page-level background
- **surface** — elevated card/container background
- **outline** — border/divider color. Solved to clear **3:1 against both
  bg and surface** (WCAG 1.4.11) — this directly fixes the systemic gap
  your audit found in all four existing palettes.
- **primary** — brand/CTA color. Intentionally sits at 3–8:1 against
  surface — **large-element/UI use, not small body text**, consistent with
  how the lexicon's own entries (e.g. Cozy Cocoa) use this role.
- **secondary / accent** — supporting and decorative roles
- **text** — primary reading color, always ≥8.5:1 against surface (AAA)
- **muted** — a real, audited `mutedText` token at ~4.6:1 against surface —
  not `.copy(alpha=)`. This is the fix your audit's finding #8 called for.

**OLED note:** every dark `bg` sits at 4.5–5.9% lightness (phone-target
range) rather than the 8–12% that reads fine on a desktop-tuned dark theme,
while keeping the family's own hue — never neutral black.

**Astigmatism/eye-comfort note (reasoned extension, not in the skill
files):** high-contrast bright elements on a very dark OLED panel can
produce halo/glare artifacts, worse for astigmatic vision; blue hues
scatter more due to chromatic aberration. So across every dark register
here: no pure `#000000`/`#FFFFFF`, peak text-on-surface contrast is kept
around 12–15:1 rather than pushed toward extremes, and the one place the
lexicon's own source material goes furthest (Ming Porcelain's inverted
near-white-on-cobalt) has been deliberately softened. In implementation,
pair this with: no glow/blur box-shadows on bright dark-mode elements
(mimics the real artifact), and regular/medium body-text weight rather
than thin in dark mode.

---

## 1 · Golden Hour (amber) — "warm caregiver"

Inspired by Cozy → Cozy Cocoa. Low-contrast, unhurried, never a cool-toned
black even at its darkest.

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

---

## 2 · Sunlit Focus (amber) — "everyday professional"

Inspired by Extroverted-sunny, pulled slightly less desaturated than the
lexicon's original so it still reads as amber rather than bleached-neutral.
Crisper and less domestic than Golden Hour — daytime, task-focused.

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

---

## 3 · Forest Floor (green) — "wellness-minded"

Inspired by Naturecore → Mossland. Warm yellow-green (H≈75°), forest-floor
register rather than a cold clinical green.

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

---

## 4 · Sage Clinical (green) — "calm clinician"

Inspired by Clinical/Medical's teal register, pulled greener (H≈155°) to
land as green rather than teal — same restrained, low-decoration logic as
Clinical Blue in your existing set, just a different hue family.

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

---

## 5 · Ink & Ledger (blue) — "methodical & disciplined"

You noticed Clinical Blue and Cobalt Porcelain were sitting too close
together (both cool, both monochromatic-blue, both reading as some flavor
of "professional"). This slot is now inspired by Scholastic → **Navy
Scholastic** instead — a genuinely different family, not just a hue shift:
Scholastic is "notebook-paper energy," cold ruled-paper blue as primary,
with a signature thin red margin-rule (`#B33A3A`) carried through as a real
second hue rather than a monochrome accent. For a medication-tracking app
this reads less like "efficient professional" and more like a well-kept
ledger/log — structured, methodical, a little bit studious.

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
safe place to add a second hue without reopening the astigmatism concern.

---

## 6 · Cobalt Porcelain (blue) — "refined & elegant"

Inspired by Ming Dynasty Porcelain (Blue-and-White). Ink & Ledger now
covers the structured/institutional angle, so this one leans further into
its own lane — decorative, curved, luxury-object blue rather than
professional blue.
**This is the one family where the astigmatism pass changed something
visible**: the lexicon's own dark register inverts to near-pure porcelain
white (`#F7F7F8`, 15.5:1) on deep cobalt — the highest-glare pairing in the
whole reference document. Softened here to an off-white at a still-strong
but gentler ~13:1.

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
bg lightness 4.7%, accent/bg 13.2:1 (softened from lexicon's 15.5:1).

---

## 7 · Rose Petal (pink) — "soft & youthful"

Inspired by Coquette/Girly's light register (Rose Petal pairing). Dusty
mauve-rose, dreamiest of the four pink/blue-adjacent options.

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

---

## 8 · Velvet Ribbon (pink) — "confident & editorial"

Inspired by Coquette/Girly's dark/"gothic-girly" register, but used as a
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

## Suggested next step

Pick one (or two, if you want a light/dark-only split like the existing
four palettes give you) and it can be wired into `colorSchemeFor()` the
same way — including filling the `secondary`/`tertiary`/`error`/`onError`
roles your audit flagged as currently unset, so whichever candidate wins
doesn't inherit that same gap.
