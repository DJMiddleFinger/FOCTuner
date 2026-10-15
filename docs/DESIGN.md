# Design

FOCTuner should read like a bench instrument (a good multimeter or scope), not a fitness dashboard. Code: `app/src/main/kotlin/app/foctuner/ui/theme/`.

## Color

Dark is the hero; light is equally deliberate. Follows the system setting; no dynamic color. All roles of Material's `ColorScheme` are mapped (`FocTheme.kt`) so stock purple never appears.

| Role | Dark | Light | Use |
|---|---|---|---|
| background | `#131517` | `#F3F4F5` | page (graphite, never pure black/white) |
| surface | `#1A1D20` | `#FAFAFB` | bars, inputs, selected row |
| surfaceHigh | `#23272B` | `#E5E8EB` | pressed, chips, menus |
| hairline | `#2B3034` | `#D7DBDF` | dividers (decorative) |
| outline | `#68707A` | `#878E96` | control boundaries (≥3:1) |
| text | `#E4E7EA` | `#15181B` | 14.7:1 / 16.2:1 |
| textSecondary | `#A2A9B0` | `#454D55` | labels; 7.7:1 / 7.8:1 |
| textTertiary | `#8A929A` | `#5F6770` | units, hints; 5.8:1 / 5.2:1 |
| accent | `#4CC2DA` | `#006F88` | live data and the active selection only; 8.8:1 / 5.3:1 |
| ok / warning / fault | `#5DBE7E` `#E2A23B` `#F2695C` | `#1A7340` `#8F5A00` `#B42A20` | meaning only |

Ratios are against `background`; every text role is AA (4.5:1) on every surface it is used on. Actions (buttons) are text-colored, not accent.

## Type

IBM Plex Sans (UI) and IBM Plex Mono (every number), bundled from the official IBM/plex release (`res/font/`, OFL in `assets/licenses/IBM-Plex-OFL.txt`). Mono gives fixed-width digits so readouts don't jitter. Numbers use fixed decimals and a real minus sign (`components/Numbers.kt`).

| Style | Font | Size/line | Use |
|---|---|---|---|
| title | Sans SemiBold | 20/28 | screen title, left aligned |
| heading | Sans Medium, +0.08em, caps | 12/16 | section labels |
| body / bodyStrong | Sans | 16/24 | rows |
| supporting | Sans | 14/20 | explanations |
| readoutLarge | Mono Medium | 44/48 | primary live values (arm's length) |
| readout | Mono | 28/36 | secondary values |
| value / code | Mono | 16/24, 13/20 | lists, terminal |
| unit | Sans, textTertiary | 14 (18 next to readoutLarge) | always smaller and dimmer than its number |

## Layout

- 8dp grid (`Spacing`); 4dp only inside a component. 16dp gutters. Touch targets ≥48dp, rows 56/72dp.
- Group by whitespace plus a section label; separate rows with hairlines. No cards, no cards inside cards.
- Labels left, numbers right, units after the number on its baseline.
- Panel-meter cells: label top left, reading bottom right.
- Corners 2–8dp, used on controls only.
- Edge-to-edge; each screen pads for system bars itself. ≥600dp wide: navigation rail and two-pane overview.

## Components (`ui/components/`)

`Readout`, `Sparkline` (Canvas, fixed sample spacing, minimum span so noise stays flat, zero line when the range crosses zero), `SectionHeader`, `Hairline`, `Notice` (neutral / warning / fault band with a 3dp bar; title, why, what to check, one action), `OutlineButton`, `PlainButton`. Icons are our own 24dp, 1.75 stroke vectors in `res/drawable/ic_*`; only navigation and a few controls get icons.

## Copy

Short and specific: "Bluetooth is off", "No controllers found" plus what to check. No exclamation marks, no "Oops", no emoji. Every `FaultCode` has a plain-language title and what to check (`res/values/strings.xml`, `fault_*`). All user-visible text is in `strings.xml`.

## Don'ts

Gradients, glows, glass, shadows for depth, emoji, decorative icons on every row, rounded cards everywhere, centered hero text, filler copy, chart libraries, accent on things that are neither live nor selected.
