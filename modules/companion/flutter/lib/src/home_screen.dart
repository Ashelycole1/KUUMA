import 'dart:async';

import 'package:flutter/material.dart';
import 'package:kuuma_lang/kuuma_lang.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'advice_screen.dart';
import 'bridge.dart';
import 'crisis_screen.dart';

/// The child's Help tab: calm when nothing is wrong, advice for each flag when something is.
/// A self-harm flag opens the crisis screens at once.
class CompanionHomeScreen extends StatefulWidget {
  const CompanionHomeScreen({super.key, required this.bridge});

  final CompanionBridge bridge;

  @override
  State<CompanionHomeScreen> createState() => _CompanionHomeScreenState();
}

class _CompanionHomeScreenState extends State<CompanionHomeScreen> {
  List<AdvicePlanView> _plans = const [];
  StreamSubscription<AdvicePlanView>? _sub;
  final Set<String> _crisisShown = {};

  @override
  void initState() {
    super.initState();
    widget.bridge.recent().then((plans) {
      if (mounted) setState(() => _plans = plans);
    });
    _sub = widget.bridge.advice.listen(_onAdvice);
  }

  void _onAdvice(AdvicePlanView plan) {
    if (!mounted) return;
    setState(() => _plans = [plan, ..._plans.where((p) => p.flagId != plan.flagId)]);
    if (plan.crisis && _crisisShown.add(plan.flagId)) {
      Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => CrisisScreen(bridge: widget.bridge),
        fullscreenDialog: true,
      ));
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _open(AdvicePlanView plan) => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) =>
            plan.crisis ? CrisisScreen(bridge: widget.bridge) : AdviceScreen(plan: plan, bridge: widget.bridge),
      ));

  @override
  Widget build(BuildContext context) {
    final lang = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    final talk = Padding(
      padding: const EdgeInsets.all(16),
      child: KuumaButton(
        kind: KuumaButtonKind.secondary,
        icon: Icons.favorite_outline,
        label: context.t('companion.home.talk'),
        onPressed: () => Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => CrisisScreen(bridge: widget.bridge)),
        ),
      ),
    );
    if (_plans.isEmpty) {
      return EmptyState(
        icon: Icons.spa_outlined,
        title: context.t('companion.home.calm_title'),
        message: context.t('companion.home.calm_body'),
        action: talk,
      );
    }
    return ListView(
      children: [
        KuumaSectionHeader(context.t('companion.home.recent').toUpperCase()),
        for (final plan in _plans)
          KuumaCard(
            key: ValueKey(plan.flagId),
            title: _categoryLabel(plan, lang),
            subtitle: context.t('companion.home.noticed'),
            leading: Icon(plan.crisis ? Icons.favorite : Icons.shield_outlined),
            trailing: SeverityBadge(plan.severity),
            onTap: () => _open(plan),
          ),
        talk,
      ],
    );
  }

  static String _categoryLabel(AdvicePlanView plan, String lang) {
    for (final e in KuumaLang.entries('harm-categories')) {
      if (e.id == plan.category.wire) return e.label(lang);
    }
    return plan.category.wire;
  }
}
