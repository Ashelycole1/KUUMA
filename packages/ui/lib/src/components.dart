import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';

import 'l10n.dart';
import 'theme.dart';

enum KuumaButtonKind { primary, secondary }

/// The one button style. Pill-shaped, at least 52 dp tall.
class KuumaButton extends StatelessWidget {
  const KuumaButton(
      {super.key, required this.label, required this.onPressed, this.kind = KuumaButtonKind.primary, this.icon});

  final String label;
  final VoidCallback? onPressed;
  final KuumaButtonKind kind;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final child = icon == null
        ? Text(label, textAlign: TextAlign.center)
        : Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 20),
            const SizedBox(width: KuumaSpace.sm),
            Flexible(child: Text(label, textAlign: TextAlign.center)),
          ]);
    return kind == KuumaButtonKind.primary
        ? FilledButton(onPressed: onPressed, child: child)
        : OutlinedButton(onPressed: onPressed, child: child);
  }
}

/// An icon in a soft tinted circle: used at the start of cards and in empty states.
class KuumaIconBadge extends StatelessWidget {
  const KuumaIconBadge(this.icon, {super.key, this.color, this.size = 44});

  final IconData icon;
  final Color? color;
  final double size;

  @override
  Widget build(BuildContext context) {
    final c = color ?? KuumaColors.of(context).brand;
    return ExcludeSemantics(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: c.withValues(alpha: 0.14), shape: BoxShape.circle),
        child: Icon(icon, color: c, size: size * 0.5),
      ),
    );
  }
}

class KuumaCard extends StatelessWidget {
  const KuumaCard({super.key, required this.title, this.subtitle, this.leading, this.trailing, this.onTap, this.child});

  final String title;
  final String? subtitle;
  final Widget? leading;
  final Widget? trailing;
  final VoidCallback? onTap;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final lead = leading is Icon ? KuumaIconBadge((leading! as Icon).icon!, color: (leading! as Icon).color) : leading;
    return Card(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(KuumaSpace.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                if (lead != null) ...[lead, const SizedBox(width: KuumaSpace.md)],
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(title, style: theme.textTheme.titleMedium),
                    if (subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(subtitle!, style: theme.textTheme.bodySmall),
                    ],
                  ]),
                ),
                if (trailing != null) ...[const SizedBox(width: KuumaSpace.sm), trailing!],
              ]),
              if (child != null) ...[const SizedBox(height: KuumaSpace.md), child!],
            ],
          ),
        ),
      ),
    );
  }
}

/// A small badge for a contract [Severity]. Labels come from the core translations; colour is
/// never the only signal.
class SeverityBadge extends StatelessWidget {
  const SeverityBadge(this.severity, {super.key});

  final Severity severity;

  @override
  Widget build(BuildContext context) {
    final color = KuumaColors.of(context).severity(severity);
    final label = context.t('core.severity.${severity.wire}');
    return KuumaPill(label: label, color: color, semanticsLabel: label);
  }
}

/// A tinted pill with a text label: severity, status ("Protected"), counts.
class KuumaPill extends StatelessWidget {
  const KuumaPill({super.key, required this.label, required this.color, this.icon, this.semanticsLabel});

  final String label;
  final Color color;
  final IconData? icon;
  final String? semanticsLabel;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Semantics(
      label: semanticsLabel ?? label,
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: color.withValues(alpha: dark ? 0.16 : 0.12),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          if (icon != null) ...[Icon(icon, size: 14, color: color), const SizedBox(width: 4)],
          Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 12, height: 16 / 12)),
        ]),
      ),
    );
  }
}

/// A heading above a group of cards.
class KuumaSectionHeader extends StatelessWidget {
  const KuumaSectionHeader(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(KuumaSpace.xl, KuumaSpace.xl, KuumaSpace.lg, KuumaSpace.sm),
        child: Row(children: [
          Expanded(
            child: Semantics(
              header: true,
              child: Text(title,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: KuumaColors.of(context).muted,
                        letterSpacing: 0.4,
                      )),
            ),
          ),
          if (trailing != null) trailing!,
        ]),
      );
}

/// The calm, friendly intro at the top of a screen: icon, title and one or two sentences.
class KuumaHero extends StatelessWidget {
  const KuumaHero({super.key, required this.icon, required this.title, this.message, this.color, this.action});

  final IconData icon;
  final String title;
  final String? message;
  final Color? color;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final p = KuumaColors.of(context);
    final c = color ?? p.brand;
    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.fromLTRB(KuumaSpace.lg, KuumaSpace.sm, KuumaSpace.lg, KuumaSpace.sm),
      padding: const EdgeInsets.all(KuumaSpace.xl),
      decoration: BoxDecoration(
        color: c.withValues(alpha: theme.brightness == Brightness.dark ? 0.14 : 0.09),
        borderRadius: BorderRadius.circular(KuumaRadius.lg),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        KuumaIconBadge(icon, color: c, size: 48),
        const SizedBox(height: KuumaSpace.lg),
        Semantics(header: true, child: Text(title, style: theme.textTheme.headlineSmall)),
        if (message != null) ...[
          const SizedBox(height: KuumaSpace.sm),
          Text(message!, style: theme.textTheme.bodyLarge?.copyWith(color: p.muted)),
        ],
        if (action != null) ...[const SizedBox(height: KuumaSpace.lg), action!],
      ]),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.title, this.message, this.icon = Icons.inbox_outlined, this.action});

  final String title;
  final String? message;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(KuumaSpace.xxl),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            KuumaIconBadge(icon, size: 72),
            const SizedBox(height: KuumaSpace.xl),
            Text(title, style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
            if (message != null) ...[
              const SizedBox(height: KuumaSpace.sm),
              Text(message!,
                  style: theme.textTheme.bodyMedium?.copyWith(color: KuumaColors.of(context).muted),
                  textAlign: TextAlign.center),
            ],
            if (action != null) ...[const SizedBox(height: KuumaSpace.xl), action!],
          ]),
        ),
      ),
    );
  }
}

/// Numbered steps (advice, guidance): a soft numbered circle and the text beside it.
/// [bullets] shows dots instead of numbers, for "what to avoid" style lists.
class KuumaSteps extends StatelessWidget {
  const KuumaSteps(this.steps, {super.key, this.bullets = false});

  final List<String> steps;
  final bool bullets;

  @override
  Widget build(BuildContext context) {
    final p = KuumaColors.of(context);
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (i, s) in steps.indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: KuumaSpace.md),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              ExcludeSemantics(
                child: Container(
                  width: 26,
                  height: 26,
                  margin: const EdgeInsets.only(right: KuumaSpace.md),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: p.surfaceMuted, shape: BoxShape.circle),
                  child: bullets
                      ? Container(
                          width: 6, height: 6, decoration: BoxDecoration(color: p.muted, shape: BoxShape.circle))
                      : Text('${i + 1}',
                          style: theme.textTheme.labelMedium?.copyWith(color: p.primary, fontWeight: FontWeight.w700)),
                ),
              ),
              Expanded(
                child: Semantics(
                  label: bullets ? null : '${i + 1}.',
                  child: Padding(
                      padding: const EdgeInsets.only(top: 2), child: Text(s, style: theme.textTheme.bodyMedium)),
                ),
              ),
            ]),
          ),
      ],
    );
  }
}
