# UX Audit — Reference App Screens

Based on all screenshots sent. Organized as: what's genuinely good and should carry over as-is, what's confusing and why, and a proposed fix for each. This becomes the spec for the Settings and Add-medicine rebuild.

---

## Keep, unchanged in spirit

- **Linked/timeline Overview** — already agreed, becomes Today's core pattern.
- **Full-card per-medicine color** — already agreed.
- **Icon-per-medicine-type** (Rx bottle, shield-check, gender symbols, radiation, etc.) — genuinely useful at-a-glance differentiation, keep the icon set.
- **Tags as colored chips** on the medicine card — clear, keep.
- **The 5 reminder types themselves** (time-based, continuous-interval, interval-with-daily-window, stock-threshold, expiration) — the *underlying* flexibility is real power, the problem is entirely in how they're presented (see below).
- **Contextual inline hints** — e.g. "color change only affects future events" shown right where the color is set. Good pattern, underused elsewhere.
- **Existing privacy controls** — screenshot capture block, hide-name-in-notification, app-lock, disable-widgets-on-lockscreen. These already exist in the source app; we're not inventing them, just making them easier to find (see Settings restructure below).

---

## What's confusing, and why

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

## Proposed fixes

### Settings: collapse three scopes into one mental model

- **App Settings** (global): grouped into visible sections with icons and one-line plain-language descriptions on *every* row, not just some — Notifications & Alerts, Alarm Behavior, Snooze, Weekend Mode, Display & Theme, Privacy & Security. Six destinations, but presented as 6 cards under 2–3 section headers instead of one flat list.
- **Medicine Settings** (per-medicine): renamed in-app to just live inline on the medicine's own screen — color, icon, notification priority, tags, notes — no separate "Impostazioni farmaco" screen at all. If it's about *this medicine*, it's on *this medicine's* screen, full stop.
- **Reminder Settings** (per-reminder): the advanced fields that are genuinely reminder-specific (threshold, interval unit, start-behavior) stay attached to that reminder, but inline/expandable on the same card rather than a second modal on top of a first.

### Add-medicine: labeled cards, not icon soup

Reminder-type picker becomes 5 cards, each with icon **+ name + one-line description already visible** (no separate help screen needed) — "Fixed times", "Repeating interval", "Daily window", "Low-stock alert", "Expiration alert". Tapping one goes straight to its config, not a second disambiguation step.

### Medicine screen: kill the 9-item overflow

Notes and Tags become visible inline sections on the medicine screen itself (like Color already is), not overflow items. Overflow menu keeps only what's genuinely rare: Duplicate, Duplicate-with-reminders, Delete — 3 items instead of 9.

### Stock & expiration: one screen, clearly zoned

Three visually separated zones with icons and headers from the start: **Current stock** (quantity, unit, "runs out ~date" computed inline), **Low-stock alert** (threshold, reminder frequency), **Expiration** (dates, reminder lead time) — one entry point, no duplication.

### Text hierarchy fix

This falls out of the theme audit we already did — real weight/color separation between heading (bold, full-contrast text), value (medium weight, full-contrast), and helper text (regular weight, secondary/muted tone) — applied consistently across every settings screen, not just some.

---

## One thing I'd flag, not fix silently

The per-medicine "reminders can only be dismissed by marking taken" override and the notification-priority levels (Standard / High / Alarm) are genuinely good power-user features worth keeping *exactly as capable* — the fix here is discoverability and labeling, not removing capability. Personal-use app for real medication adherence shouldn't lose power in the name of simplicity.
