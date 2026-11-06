# Kuuma design system (MASTER)

Global source of truth for how Kuuma looks and behaves. Page-specific overrides go in
`design-system/kuuma/pages/<page>.md`; without one, these rules apply. Built with the
[UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) skill (Nov 6, 2026),
then adjusted for contrast and for Kuuma's safety rules. Code: `packages/ui/lib/src/theme.dart`.

## Who it is for

Children and teenagers in Uganda receiving harmful messages, and their parents. Often on small,
low-end Android phones, sometimes shared, sometimes looked at over a shoulder. The app must feel
**calm and safe**, never alarming, and must not reveal much at a glance.

## Direction

- **Style:** Minimalism, soft and rounded (skill: "Minimalism & Swiss Style", low variance 3/10,
  subtle motion 3/10, standard density 4/10). Generous white space, one idea per card.
- **Palette:** Kuuma Violet, Warm Peach and Cloud White, sampled from the current pitch deck.
- **Typography:** "Soft Rounded" pairing: **Varela Round** for headings, **Nunito Sans** for text
  (skill: best for children's products, friendly, wellness). Bundled in `packages/ui/fonts` (SIL OFL),
  never fetched at runtime (offline phones; no requests to third parties).

## Colour tokens

The three core brand colours are Kuuma Violet, Warm Peach and Cloud White. Light and dark UI tokens are defined in packages/ui/lib/src/theme.dart. Use these tokens in screens rather than raw hex values.

| Name | Hex | Role |
| --- | --- | --- |
| Kuuma Violet | #5D40A4 | Primary brand colour, headings, and primary actions |
| Warm Peach | #EDB390 | Accent and highlights |
| Cloud White | #F8FAFD | Main light background and text on violet |

Text on Kuuma Violet uses Cloud White for contrast. Use dark foreground text on Warm Peach. Secondary and severity colours remain separate semantic tokens.

### Severity (kept muted on purpose)

| Severity | Light | Dark |
| --- | --- | --- |
| low | `#256A51` | `#7FCFAE` |
| medium | `#7F560B` | `#E3B866` |
| high | `#A8433A` | `#F09A8F` |

Shown as a tinted pill with a text label, never colour alone. All pass 4.5:1 on their own tint.

## Type scale (Nunito Sans body, Varela Round headings)

| Style | Size / line | Font |
| --- | --- | --- |
| headlineSmall | 24 / 32 | Varela Round |
| titleLarge | 20 / 28 | Varela Round |
| titleMedium | 16 / 24, 600 | Nunito Sans |
| bodyLarge | 16 / 24 | Nunito Sans |
| bodyMedium | 15 / 22 | Nunito Sans |
| labelLarge | 15 / 20, 700 | Nunito Sans (buttons) |
| bodySmall / labelSmall | 13 / 18 | Nunito Sans (never smaller than 12) |

Sizes scale with the phone's text-size setting; never fix heights around text.

## Space, shape, motion

- Spacing scale (`KuumaSpace`): 4, 8, 12, 16, 24, 32, 48. Screen padding 16; cards 16 inside, 12 apart.
- Radius (`KuumaRadius`): 12 small (chips, fields), 16 cards, 24 sheets, full for pills and buttons.
- Elevation: none; cards are separated by a 1px border and white space (cheap to draw on low-end phones).
- Motion: 200 ms, ease-out, fades and small slides only; none when the phone asks for reduced motion.

## Rules (from the skill's priority list, applied to Kuuma)

1. **Accessibility:** 4.5:1 text contrast; every icon-only button has a tooltip/label; `Semantics` on
   custom widgets; respect large text.
2. **Touch:** every target at least 48×48 dp, 8 dp apart; feedback on every press; loading states.
3. **Style:** Material icons only (no emoji as icons); one style across both apps.
4. **Layout:** single column on phones; nothing scrolls sideways except chip rows.
5. **Colour:** tokens only, never raw hex in screens; severity always has a text label.
6. **Navigation:** bottom bar with at most 5 tabs; back always goes back.
7. **Forms:** visible labels (not placeholders), errors next to the field, helper text.
8. **Kuuma safety:** calm wording, no red full-screen alarms; message text hidden until tapped;
   quick-hide and lock screen look plain.

## Pre-delivery checklist

- [ ] No raw colours in screens (use `Theme.of(context)` / `KuumaColors`)
- [ ] Contrast 4.5:1 in light and dark
- [ ] Touch targets ≥ 48 dp
- [ ] Works at 200% text size without clipping
- [ ] Icon-only buttons have tooltips
- [ ] Reduced motion respected
- [ ] Checked at 360 dp wide (small phone)
