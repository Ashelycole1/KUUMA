/// Casefile screens: incident history, case timeline, evidence check, export and share.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/cases_screen.dart';

export 'src/case_screen.dart';
export 'src/cases_screen.dart';
export 'src/placeholder_screen.dart';

class CasefileFlutterModule implements KuumaFlutterModule {
  @override
  String get prefix => 'casefile';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, {
      'en': {
        'casefile.tab.title': 'My cases',
        'casefile.cases.empty_title': 'Nothing saved yet',
        'casefile.cases.empty_body': 'When Kuuma spots a harmful message, a safe copy is kept here.',
        'casefile.case.incidents': '{n} incidents',
        'casefile.case.incident_one': '1 incident',
        'casefile.case.timeline': 'What happened',
        'casefile.case.tap_to_show': 'Hidden message. Tap to show.',
        'casefile.case.flagged': 'Kuuma flagged this',
        'casefile.case.you': 'You',
        'casefile.case.machine_translation': 'Machine translation, not checked by a person',
        'casefile.share.sent_to': 'Shared with: {recipient}',
        'casefile.share.parent_reported': 'Your parent reported this to: {recipient}',
        'casefile.share.anonymous': 'Your name, your replies and your friends were left out.',
        'casefile.cases.intro_title': 'Your saved evidence',
        'casefile.cases.intro_body':
            'Kuuma keeps a sealed copy of harmful messages. Nobody can change it, and only you decide who sees it.',
        'casefile.action.check': 'Check evidence',
        'casefile.action.export': 'Save as PDF',
        'casefile.action.share': 'Share',
        'casefile.check.ok': 'Evidence is intact: nothing has been changed.',
        'casefile.check.bad': 'Warning: some evidence was changed or removed.',
        'casefile.export.done': 'Saved the case file.',
        'casefile.share.title': 'Share with',
        'casefile.share.waiting': 'Waiting for your parent to approve.',
        'casefile.share.ready': 'Ready to share. Send this link:',
        'casefile.share.offline': 'Prepared. It will be uploaded when you are online.',
        'casefile.recipient.school': 'School',
        'casefile.recipient.police': 'Police',
        'casefile.recipient.platform': 'The app or website',
        'casefile.recipient.lawyer': 'Lawyer',
      },
      'sw': {
        'casefile.tab.title': 'Kesi zangu',
        'casefile.recipient.school': 'Shule',
        'casefile.recipient.police': 'Polisi'
      },
    });
    slots.register(
        prefix,
        ScreenEntry(
          id: 'casefile.cases',
          slot: KuumaSlot.childTab,
          titleKey: 'casefile.tab.title',
          icon: Icons.folder_outlined,
          order: 20,
          builder: (_) => CasesScreen(vault: ModuleChannel('casefile')),
        ));
  }
}
