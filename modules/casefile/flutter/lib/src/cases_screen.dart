import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'case_screen.dart';

/// Incident history: one card per offender case, newest first.
class CasesScreen extends StatefulWidget {
  const CasesScreen({super.key, required this.vault});

  final ModuleChannel vault;

  @override
  State<CasesScreen> createState() => _CasesScreenState();
}

class _CasesScreenState extends State<CasesScreen> {
  List<Map<Object?, Object?>>? _cases;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final list = await widget.vault.call<List<Object?>>('listCases') ?? const [];
    if (mounted) setState(() => _cases = list.cast<Map<Object?, Object?>>());
  }

  @override
  Widget build(BuildContext context) {
    final cases = _cases;
    if (cases == null) return const Center(child: CircularProgressIndicator());
    if (cases.isEmpty) {
      return EmptyState(
        icon: Icons.folder_open_outlined,
        title: context.t('casefile.cases.empty_title'),
        message: context.t('casefile.cases.empty_body'),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.only(bottom: KuumaSpace.xl), children: [
        KuumaHero(
          icon: Icons.lock_person_outlined,
          title: context.t('casefile.cases.intro_title'),
          message: context.t('casefile.cases.intro_body'),
        ),
        for (final c in cases)
          KuumaCard(
            leading: const Icon(Icons.person_outline),
            title: senderTitle(c),
            subtitle: [
              ...senderExtras(c),
              c['incidentCount'] == 1
                  ? context.t('casefile.case.incident_one')
                  : context.t('casefile.case.incidents', {'n': '${c['incidentCount']}'}),
            ].join(' · '),
            trailing: SeverityBadge(Severity.fromWire(c['topSeverity'] as String)),
            onTap: () async {
              await Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => CaseScreen(vault: widget.vault, caseId: c['caseId'] as String, label: senderTitle(c)),
              ));
              _load();
            },
          ),
      ]),
    );
  }
}

List<String> _strings(Object? v) => (v as List<Object?>? ?? const []).whereType<String>().toList();

/// The sender as the child's messaging app showed them (saved name, else number), else "Person A".
String senderTitle(Map<Object?, Object?> c) {
  final names = _strings(c['senderNames']);
  final numbers = _strings(c['senderNumbers']);
  return names.firstOrNull ?? numbers.firstOrNull ?? c['offenderLabel'] as String;
}

/// Other names and numbers the sender used or wrote, after the one in the title.
List<String> senderExtras(Map<Object?, Object?> c) {
  final all = {..._strings(c['senderNames']), ..._strings(c['senderNumbers'])}.toList();
  return all.isEmpty ? const [] : all.sublist(1);
}
