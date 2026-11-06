import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_lang/kuuma_lang.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'children_screen.dart';
import 'parent_gate.dart';
import 'report_screen.dart';
import 'strings.dart';

String alertTitle(BuildContext context, Alert a) {
  final kind = a.kind ?? AlertKind.flag;
  if (kind == AlertKind.flag && a.category != null) {
    return KuumaLang.entries('harm-categories')
        .firstWhere((e) => e.id == a.category!.wire)
        .label(Localizations.localeOf(context).languageCode);
  }
  return context.t('guardian.alerts.kind_${kind.wire}');
}

DateTime? alertTime(Alert a) => DateTime.tryParse(a.occurredAt ?? a.createdAt);

/// Alerts for every child (or one), newest first, with a 7-day severity trend on top.
class AlertsScreen extends StatefulWidget {
  const AlertsScreen({super.key, this.now});

  final DateTime Function()? now;

  @override
  State<AlertsScreen> createState() => _AlertsScreenState();
}

class _AlertsScreenState extends State<AlertsScreen> {
  String? _childId;

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    final alerts = session.alerts.where((a) => _childId == null || a.childId == _childId).toList();
    final now = (widget.now ?? DateTime.now)();
    return RefreshIndicator(
      onRefresh: session.refresh,
      child: ListView(padding: const EdgeInsets.all(8), children: [
        const OfflineBanner(),
        if (session.children.length > 1)
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              ChoiceChip(
                label: Text(context.t('guardian.common.all_children')),
                selected: _childId == null,
                onSelected: (_) => setState(() => _childId = null),
              ),
              for (final c in session.children)
                Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: ChoiceChip(
                    label: Text(childName(context, c)),
                    selected: _childId == c.childId,
                    onSelected: (_) => setState(() => _childId = c.childId),
                  ),
                ),
            ]),
          ),
        if (alerts.isEmpty)
          SizedBox(
            height: 360,
            child: EmptyState(
              icon: Icons.notifications_none,
              title: context.t('guardian.alerts.empty_title'),
              message: context.t('guardian.alerts.empty_body'),
            ),
          )
        else ...[
          SeverityTrend(alerts: alerts, now: now),
          for (final a in alerts)
            KuumaCard(
              key: Key('alert-${a.alertId}'),
              leading: Icon(switch (a.kind) {
                AlertKind.sos => Icons.sos,
                AlertKind.tamper => Icons.gpp_bad_outlined,
                AlertKind.escalation => Icons.trending_up,
                _ => Icons.chat_bubble_outline,
              }),
              title: alertTitle(context, a),
              subtitle: [
                if (session.children.length > 1) childName(context, session.child(a.childId)),
                if (a.appSource != null) context.t('guardian.alerts.on', {'app': appLabel(a.appSource!)}),
                shortTime(context, a.occurredAt ?? a.createdAt),
              ].join(' · '),
              trailing: SeverityBadge(a.severity),
              onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => ParentScope(session: session, child: AlertDetailScreen(alert: a)),
              )),
            ),
        ],
      ]),
    );
  }
}

/// One column per day for the last 7 days, stacked by severity.
class SeverityTrend extends StatelessWidget {
  const SeverityTrend({super.key, required this.alerts, required this.now});

  final List<Alert> alerts;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final today = DateTime(now.year, now.month, now.day);
    final days = [for (var i = 6; i >= 0; i--) today.subtract(Duration(days: i))];
    final counts = [for (final _ in days) <Severity, int>{}];
    var total = 0;
    for (final a in alerts) {
      final t = alertTime(a)?.toLocal();
      if (t == null) continue;
      final i = days.indexOf(DateTime(t.year, t.month, t.day));
      if (i < 0) continue;
      counts[i][a.severity] = (counts[i][a.severity] ?? 0) + 1;
      total++;
    }
    final most = counts.map((c) => c.values.fold(0, (a, b) => a + b)).fold(1, (a, b) => a > b ? a : b);
    final theme = Theme.of(context);
    final weekday = MaterialLocalizations.of(context).narrowWeekdays;
    return Semantics(
      label: context.t('guardian.alerts.trend_label', {'n': '$total'}),
      excludeSemantics: true,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(context.t('guardian.alerts.trend'), style: theme.textTheme.titleSmall),
            const SizedBox(height: 12),
            SizedBox(
              height: 96,
              child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                for (var i = 0; i < days.length; i++)
                  Expanded(
                    child: Column(children: [
                      // The bars get whatever height the day label leaves, so large system
                      // font sizes shrink the bars instead of overflowing the card.
                      Expanded(
                        child: LayoutBuilder(
                          builder: (context, box) => Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                            for (final s in Severity.values.reversed)
                              if ((counts[i][s] ?? 0) > 0)
                                Container(
                                  height: (box.maxHeight * counts[i][s]! / most).floorToDouble(),
                                  margin: const EdgeInsets.symmetric(horizontal: 6),
                                  color: KuumaColors.of(context).severity(s),
                                ),
                          ]),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(weekday[days[i].weekday % 7],
                          style: theme.textTheme.labelSmall, maxLines: 1, overflow: TextOverflow.clip),
                    ]),
                  ),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

/// What happened, what to do, and the message itself only when the parent asks for it.
class AlertDetailScreen extends StatefulWidget {
  const AlertDetailScreen({super.key, required this.alert});

  final Alert alert;

  @override
  State<AlertDetailScreen> createState() => _AlertDetailScreenState();
}

class _AlertDetailScreenState extends State<AlertDetailScreen> {
  String? _message;
  bool _shown = false;

  Future<void> _toggle() async {
    if (_shown) {
      setState(() {
        _shown = false;
        _message = null; // keep the text in memory only while it is on screen
      });
      return;
    }
    final text = await ParentScope.of(context).openExcerpt(widget.alert);
    if (mounted) {
      setState(() {
        _shown = true;
        _message = text;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final a = widget.alert;
    final theme = Theme.of(context);
    final session = ParentScope.of(context);
    final guide = guideKey(a.kind, a.category);
    Widget steps(String titleKey, String key, IconData icon) => KuumaCard(
          leading: Icon(icon),
          title: context.t(titleKey),
          child: KuumaSteps(context.t(key).split('\n'), bullets: titleKey.endsWith('avoid')),
        );
    return Scaffold(
      appBar: AppBar(title: Text(alertTitle(context, a))),
      body: ListView(padding: const EdgeInsets.all(8), children: [
        KuumaCard(
          title: childName(context, session.child(a.childId)),
          subtitle: [
            if (a.appSource != null) context.t('guardian.alerts.on', {'app': appLabel(a.appSource!)}),
            shortTime(context, a.occurredAt ?? a.createdAt),
          ].join(' · '),
          trailing: SeverityBadge(a.severity),
        ),
        if (a.sealedExcerpt != null)
          KuumaCard(
            leading: const Icon(Icons.visibility_outlined),
            title: context.t(_shown ? 'guardian.alerts.hide_message' : 'guardian.alerts.show_message'),
            onTap: _toggle,
            child: !_shown
                ? null
                : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_message ?? context.t('guardian.alerts.no_message'),
                        key: const Key('excerpt'), style: theme.textTheme.bodyLarge),
                    const SizedBox(height: 8),
                    Text(context.t('guardian.alerts.message_note'), style: theme.textTheme.bodySmall),
                  ]),
          ),
        steps('guardian.alerts.talk', 'guardian.guide.$guide.talk', Icons.forum_outlined),
        steps('guardian.alerts.avoid', 'guardian.guide.$guide.avoid', Icons.block),
        // Reporting needs a real case on the child's phone (not a tamper alert).
        if (a.childId != null && a.kind != AlertKind.tamper)
          Padding(
            padding: const EdgeInsets.all(KuumaSpace.lg),
            child: KuumaButton(
              key: const Key('report-case'),
              icon: Icons.outgoing_mail,
              label: context.t('guardian.report.button'),
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<bool>(
                builder: (_) => ParentScope(
                  session: session,
                  child: ReportScreen(childId: a.childId!, caseId: a.caseId),
                ),
              )),
            ),
          ),
      ]),
    );
  }
}
