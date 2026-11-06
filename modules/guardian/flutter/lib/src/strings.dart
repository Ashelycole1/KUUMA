import 'package:kuuma_contracts/kuuma_contracts.dart';

/// Guardian's translations. English is the source; lg / nyn / sw are added by native speakers.
const guardianStrings = <String, Map<String, String>>{
  'en': {
    // Tabs and slots
    'guardian.pairing.title': 'Parent and pairing',
    'guardian.parent.alerts.title': 'Alerts',
    'guardian.parent.children.title': 'Children',
    'guardian.parent.approvals.title': 'Requests',
    // Account
    'guardian.account.welcome_title': 'Nilinde for parents',
    'guardian.account.welcome_body':
        'Your account is tied to this phone only: no name, email or phone number. Choose a PIN to protect it.',
    'guardian.account.choose_pin': 'Choose a 4-digit PIN',
    'guardian.account.confirm_pin': 'Enter the PIN again',
    'guardian.account.pin_mismatch': 'The PINs did not match. Try again.',
    'guardian.account.create': 'Create account',
    'guardian.account.offline': 'Could not reach Nilinde. Check your internet connection and try again.',
    'guardian.account.lock': 'Lock',
    // Common
    'guardian.common.offline_banner': 'Offline: showing what was last loaded.',
    'guardian.common.retry': 'Try again',
    'guardian.common.save': 'Save',
    'guardian.common.cancel': 'Cancel',
    'guardian.common.all_children': 'All children',
    'guardian.common.unnamed': 'Child',
    // Children
    'guardian.children.empty_title': 'No children yet',
    'guardian.children.empty_body': "Add a child, then scan the code with Nilinde on the child's phone.",
    'guardian.children.add': 'Add a child',
    'guardian.status.active': 'Protected',
    'guardian.status.waiting': 'Waiting to pair',
    'guardian.status.missing': 'Protection turned off',
    'guardian.status.silent': 'Not checked in since {time}',
    'guardian.status.never': 'Not checked in yet',
    'guardian.child.nickname': 'Nickname (stays on this phone)',
    'guardian.child.age': 'Age group',
    'guardian.child.apps': 'Apps to watch',
    'guardian.child.alert_level': 'Tell me about',
    'guardian.child.alert_low': 'Everything, even mild messages',
    'guardian.child.alert_medium': 'Harmful messages',
    'guardian.child.alert_high': 'Only serious messages',
    'guardian.child.high_always': 'Serious messages and SOS always reach you.',
    'guardian.child.next': 'Show pairing code',
    'guardian.child.policy_title': 'Settings for {name}',
    'guardian.child.policy_saved': "Saved. The child's phone picks it up at its next check-in.",
    'guardian.child.missing_list': 'Turned off on the phone:',
    'guardian.child.policy_version': 'Settings version {n}',
    'guardian.qr.title': 'Scan with the child\'s phone',
    'guardian.qr.steps':
        "1. Install Nilinde on the child's phone.\n2. Open Settings, then Parent and pairing.\n3. Scan this code.",
    'guardian.qr.code': 'Code {code}',
    'guardian.qr.expires': 'This code works once, until {time}.',
    'guardian.qr.private': 'Do not share or photograph this code: it holds the key to your alerts.',
    'guardian.qr.done': 'Done',
    // Alerts
    'guardian.alerts.empty_title': 'No alerts',
    'guardian.alerts.empty_body': 'When Nilinde sees something you should know about, it shows up here.',
    'guardian.alerts.trend': 'Last 7 days',
    'guardian.alerts.trend_label': '{n} alerts in the last 7 days',
    'guardian.alerts.kind_flag': 'Harmful message',
    'guardian.alerts.kind_escalation': 'It is getting worse',
    'guardian.alerts.kind_sos': 'SOS',
    'guardian.alerts.kind_tamper': 'Protection changed',
    'guardian.alerts.on': 'on {app}',
    'guardian.alerts.show_message': 'Show the message',
    'guardian.alerts.hide_message': 'Hide the message',
    'guardian.alerts.no_message': 'The message is not available.',
    'guardian.alerts.message_note': 'Only you can read this. It is not saved on this phone.',
    'guardian.alerts.talk': 'How to talk about it',
    'guardian.alerts.avoid': 'What to avoid',
    // Approvals
    'guardian.approvals.empty_title': 'No requests',
    'guardian.approvals.empty_body': 'Reports you send to a school, the police or a lawyer appear here.',
    'guardian.approvals.share': 'Wants to share a case with: {recipient}',
    'guardian.approvals.approve': 'Approve',
    'guardian.approvals.decline': 'Decline',
    'guardian.approvals.approved': 'Approved',
    'guardian.approvals.declined': 'Declined',
    'guardian.approvals.expired': 'Expired',
    'guardian.recipient.school': 'School',
    'guardian.recipient.police': 'Police',
    'guardian.recipient.platform': 'The app or website',
    'guardian.recipient.lawyer': 'Lawyer',
    // Parent reports
    'guardian.qr.copy': 'Copy pairing code',
    'guardian.qr.copied': 'Copied. On the child\'s phone, choose "Paste a pairing code instead".',
    'guardian.report.button': 'Report this case',
    'guardian.report.title': 'Report this case',
    'guardian.report.hero_title': 'Send the evidence safely',
    'guardian.report.hero_body':
        "Nilinde builds a sealed case file on your child's phone and gives you a private link to send.",
    'guardian.report.to': 'Send to',
    'guardian.report.privacy': "Your child's privacy",
    'guardian.report.anonymous': 'Keep my child anonymous',
    'guardian.report.anonymous_on':
        "Only the sender's messages are included. Your child's name, replies and friends are left out.",
    'guardian.report.anonymous_off': "The file includes your child's replies. Their name may appear in messages.",
    'guardian.report.anonymous_short': 'Anonymous',
    'guardian.report.what_happens':
        "Your child's phone builds the case file next time it checks in.\nThe link appears here, under Requests.\nCopy it and send it to the school, police, app or lawyer.",
    'guardian.report.send': 'Send report request',
    'guardian.report.sent': "Sent to your child's phone. The link appears in Requests when it is ready.",
    'guardian.report.yours': 'Your reports',
    'guardian.report.card_title': '{name} to {recipient}',
    'guardian.report.status_requested': 'Waiting',
    'guardian.report.status_ready': 'Ready',
    'guardian.report.status_failed': 'Not sent',
    'guardian.report.waiting': "Waiting for your child's phone to check in.",
    'guardian.report.failed':
        "Your child's phone could not build the file (it may have been offline). Try again later.",
    'guardian.report.ready_body': 'The link opens the sealed case file. Send it only to the person you chose.',
    'guardian.report.copy': 'Copy the link',
    'guardian.report.copied': 'Link copied. Paste it into a message or email to the recipient.',
    'guardian.report.cannot_open': 'This link could not be opened on this phone.',
    // Child's phone: setup wizard
    'guardian.wizard.intro_title': 'Set up Nilinde with a parent',
    'guardian.wizard.intro_body': 'A parent or guardian does this together with the child. It takes a few minutes.',
    'guardian.wizard.start': 'Start',
    'guardian.wizard.consent_title': 'What Nilinde does',
    'guardian.wizard.consent_body':
        'Nilinde reads incoming messages on this phone to spot bullying, threats and grooming. '
            'Messages are checked on the phone. Your parent is told when something harmful arrives, '
            'and can read only that message. Nothing else is shared.',
    'guardian.wizard.consent_agree': 'We agree',
    'guardian.wizard.scan_title': 'Scan the pairing code',
    'guardian.wizard.scan_body': 'On the parent\'s phone: Children, then Add a child.',
    'guardian.wizard.bad_code': 'That is not a Nilinde pairing code.',
    'guardian.wizard.expired_code': 'That code has expired or was used. Make a new one.',
    'guardian.wizard.bad_policy': 'The settings could not be checked. Make a new code.',
    'guardian.wizard.offline': 'Could not reach Nilinde. Check the internet connection and scan again.',
    'guardian.wizard.permissions_title': 'Turn on protection',
    'guardian.wizard.permissions_body': 'Nilinde needs these to work. Tap each one to turn it on.',
    'guardian.wizard.granted': 'On',
    'guardian.wizard.turn_on': 'Turn on',
    'guardian.wizard.optional': 'Optional',
    'guardian.wizard.finish': 'Finish',
    'guardian.wizard.done_title': 'All set',
    'guardian.wizard.done_body': 'Nilinde is protecting this phone.',
    'guardian.wizard.managed_title': 'Managed by your parent',
    'guardian.wizard.managed_body': 'Your parent chooses these settings. Ask them if something should change.',
    'guardian.wizard.watching': 'Watching: {apps}',
    'guardian.wizard.check_permissions': 'Check protection',
    'guardian.wizard.pending': '{n} alerts waiting to be sent',
    'guardian.wizard.paste': 'Paste a pairing code instead',
    'guardian.wizard.paste_hint': 'Pairing code text',
    // Permission reasons, for the permissions this module declares
    'guardian.permission.notifications.reason': 'So Nilinde can tell you about what it finds.',
    'guardian.permission.camera.reason': 'To scan the pairing code from the parent\'s phone.',
    // Guidance: what to do after an alert, per kind of harm
    'guardian.guide.insult.talk':
        'Pick a calm, private moment.\nSay you noticed something and want to help, not punish.\n'
            'Ask how they feel about it and listen.\nAgree together on blocking or reporting the sender.',
    'guardian.guide.insult.avoid':
        'Taking the phone away: it teaches them not to tell you.\nTelling them to ignore it if it hurts.\n'
            'Contacting the sender yourself in anger.',
    'guardian.guide.harassment.talk': 'Tell them it is not their fault.\nAsk how long it has been going on.\n'
        'Keep the evidence: Nilinde already saves it.\nIf it involves classmates, plan together before telling the school.',
    'guardian.guide.harassment.avoid':
        'Blaming them for what they posted or who they talk to.\nActing without telling them first.',
    'guardian.guide.threat.talk': 'Talk today. Ask if they feel safe at school and on the way home.\n'
        'Make a safety plan: who they walk with, who to call.\n'
        'If the threat is specific, report it to the police with the case file.',
    'guardian.guide.threat.avoid':
        'Waiting to see if it happens again.\nConfronting the sender, which can make it worse.',
    'guardian.guide.sexual_harassment.talk':
        'Stay calm and thank them for trusting you.\nTell them clearly it is not their fault.\n'
            'Block and report the sender together.\nConsider talking to a counsellor.',
    'guardian.guide.sexual_harassment.avoid':
        'Showing shock or anger at the child.\nAsking them to repeat details many times.',
    'guardian.guide.sextortion.talk':
        'Act today. Tell them they are not in trouble and you will handle this together.\n'
            'Do not pay or send anything.\nKeep the evidence and report to the police and the app.',
    'guardian.guide.sextortion.avoid':
        'Paying: demands usually continue.\nDeleting the messages: they are evidence.\nPunishing the child.',
    'guardian.guide.grooming.talk': 'Ask gently about the person: how they met, what they talk about.\n'
        'Explain that adults who ask for secrets or gifts are a warning sign.\n'
        'Report to the police with the case file.',
    'guardian.guide.grooming.avoid':
        'Warning the person: they may disappear or move to another app.\nBlaming the child for the relationship.',
    'guardian.guide.doxxing.talk': 'Find out what was shared and where.\nReport it to the app so it is removed.\n'
        'Check your child is safe if an address or school was shared.',
    'guardian.guide.doxxing.avoid': 'Sharing the post further, even to complain about it.',
    'guardian.guide.hate.talk': 'Tell them nobody deserves to be attacked for who they are.\nReport it to the app.\n'
        'Ask whether it is happening at school as well.',
    'guardian.guide.hate.avoid': 'Telling them to toughen up.',
    'guardian.guide.self_harm_encouragement.talk':
        'Talk today, calmly. Ask directly how they are feeling: asking does not put ideas in their head.\n'
            'Stay close and listen.\nGet help from a health worker or counsellor.',
    'guardian.guide.self_harm_encouragement.avoid':
        'Leaving them alone if they are upset.\nDismissing it as attention-seeking.',
    'guardian.guide.sos.talk': 'Call or find your child now.\nAsk where they are and if they are safe.\n'
        'If they are in danger, call the police.',
    'guardian.guide.sos.avoid': 'Waiting for a reply before acting.',
    'guardian.guide.tamper.talk': "Nilinde's protection was turned off, or the phone stopped checking in.\n"
        'It may just be a flat battery or no data.\n'
        'Ask your child about it calmly and turn protection back on together.',
    'guardian.guide.tamper.avoid': 'Assuming the worst before you talk.',
    'guardian.guide.escalation.talk': 'The same person keeps sending harmful messages and it is getting worse.\n'
        'Talk today and consider sharing the case with the school or police.',
    'guardian.guide.escalation.avoid': 'Waiting for it to stop on its own.',
  },
  'sw': {
    'guardian.parent.alerts.title': 'Tahadhari',
    'guardian.parent.children.title': 'Watoto',
    'guardian.recipient.school': 'Shule',
    'guardian.recipient.police': 'Polisi',
  },
};

/// App names are brand names and are not translated.
String appLabel(AppSource a) => switch (a) {
      AppSource.whatsapp => 'WhatsApp',
      AppSource.sms => 'SMS',
      AppSource.messenger => 'Messenger',
      AppSource.instagram => 'Instagram',
      AppSource.tiktok => 'TikTok',
      AppSource.x => 'X',
    };

/// The guidance key for an alert: by category for flags, by kind otherwise.
String guideKey(AlertKind? kind, HarmCategory? category) => switch (kind) {
      AlertKind.sos => 'sos',
      AlertKind.tamper => 'tamper',
      AlertKind.escalation => category?.wire ?? 'escalation',
      _ => category?.wire ?? 'harassment',
    };
