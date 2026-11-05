import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';

import 'l10n.dart';
import 'theme.dart';

enum KuumaButtonKind { primary, secondary }

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
        ? Text(label)
        : Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 20), const SizedBox(width: 8), Text(label)]);
    return kind == KuumaButtonKind.primary
        ? FilledButton(onPressed: onPressed, child: child)
        : OutlinedButton(onPressed: onPressed, child: child);
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
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                if (leading != null) ...[leading!, const SizedBox(width: 12)],
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
                    if (subtitle != null) Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
                  ]),
                ),
                if (trailing != null) trailing!,
              ]),
              if (child != null) ...[const SizedBox(height: 12), child!],
            ],
          ),
        ),
      ),
    );
  }
}

/// A small badge for a contract [Severity]. Labels come from the core translations.
class SeverityBadge extends StatelessWidget {
  const SeverityBadge(this.severity, {super.key});

  final Severity severity;

  @override
  Widget build(BuildContext context) {
    final color = KuumaColors.severity(severity);
    return Semantics(
      label: context.t('core.severity.${severity.wire}'),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: color),
        ),
        child: Text(context.t('core.severity.${severity.wire}'),
            style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12)),
      ),
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
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 56, color: theme.colorScheme.outline),
          const SizedBox(height: 16),
          Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
          if (message != null) ...[
            const SizedBox(height: 8),
            Text(message!, style: theme.textTheme.bodyMedium, textAlign: TextAlign.center),
          ],
          if (action != null) ...[const SizedBox(height: 16), action!],
        ]),
      ),
    );
  }
}
