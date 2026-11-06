import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';

Future<(ParentSession, FakeGuardianParentApi, MemoryGuardianVault)> signedIn() async {
  final api = FakeGuardianParentApi();
  final vault = MemoryGuardianVault();
  final s = ParentSession(api: api, vault: vault, pinIterations: 10);
  await s.load();
  await s.createAccount('2580');
  return (s, api, vault);
}

void main() {
  test('first run needs an account; later runs start locked and check the PIN', () async {
    final (s, api, vault) = await signedIn();
    expect(s.state, SessionState.unlocked);
    expect(vault.values.containsKey('pin'), isTrue);
    expect(vault.values['pin'], isNot(contains('2580')));

    final again = ParentSession(api: api, vault: vault, pinIterations: 10);
    await again.load();
    expect(again.state, SessionState.locked);
    expect(await again.verifyPin('1111'), isFalse);
    expect(await again.verifyPin('2580'), isTrue);

    final fresh = ParentSession(api: FakeGuardianParentApi(), vault: MemoryGuardianVault());
    await fresh.load();
    expect(fresh.state, SessionState.needsAccount);
  });

  test('adding a child signs the policy and keeps the key and nickname on the phone', () async {
    final (s, api, vault) = await signedIn();
    final pending =
        await s.addChild(nickname: ' Amara ', bracket: AgeBracket.age12Under, watchedApps: [AppSource.whatsapp]);
    final policy = api.policies[pending.childId]!;
    expect(FamilyCrypto(pending.qr.familyKey).verifyPolicy(policy), isTrue);
    expect(policy.thresholds.alertMinSeverity, Severity.low); // bracket default
    expect(PairingQr.parse(pending.qr.text)!.code, pending.qr.code);
    expect(s.children.single.nickname, 'Amara');
    expect(vault.values['child.${pending.childId}.key'], b64url(pending.qr.familyKey));
  });

  test('policy updates bump the version and stay signed', () async {
    final (s, api, _) = await signedIn();
    final pending = await s.addChild(nickname: 'A', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final p = await s.updatePolicy(pending.childId,
        bracket: AgeBracket.age1618, watchedApps: [AppSource.sms, AppSource.tiktok], alertMinSeverity: Severity.high);
    expect(p.policyVersion, 2);
    expect(api.policies[pending.childId]!.policyVersion, 2);
    expect(FamilyCrypto(pending.qr.familyKey).verifyPolicy(p), isTrue);
    expect(s.child(pending.childId)!.policy!.thresholds.alertMinSeverity, Severity.high);
  });

  test('excerpts open with the right child key only', () async {
    final (s, api, _) = await signedIn();
    final a = await s.addChild(nickname: 'A', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final b = await s.addChild(nickname: 'B', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final sealed = FamilyCrypto(a.qr.familyKey).seal('Tujja kukukuba');
    api.addAlert(a.childId, sealedExcerpt: sealed);
    api.addAlert(b.childId, sealedExcerpt: sealed); // wrong key for B
    await s.refresh();
    final byChild = {for (final al in s.alerts) al.childId: al};
    expect(await s.openExcerpt(byChild[a.childId]!), 'Tujja kukukuba');
    expect(await s.openExcerpt(byChild[b.childId]!), isNull);
  });

  test('decisions are signed with the requesting child key', () async {
    final (s, api, _) = await signedIn();
    final p = await s.addChild(nickname: 'A', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final req = api.addApproval(p.childId);
    await s.refresh();
    await s.decide(s.approvals.single, ApprovalDecision.approve);
    final expected = FamilyCrypto(p.qr.familyKey).signDecision(req.approvalId, req.requestId, ApprovalDecision.approve);
    expect(api.calls.last, 'decide:approve:$expected');
    expect(s.approvals.single.status, ApprovalStatus.approved);
  });

  test('offline refresh keeps what was loaded', () async {
    final (s, api, _) = await signedIn();
    await s.addChild(nickname: 'A', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    api.online = false;
    await s.refresh();
    expect(s.offline, isTrue);
    expect(s.children, hasLength(1));
  });

  test('locking clears alerts from memory', () async {
    final (s, api, _) = await signedIn();
    final p = await s.addChild(nickname: 'A', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    api.addAlert(p.childId);
    await s.refresh();
    s.lock();
    expect(s.alerts, isEmpty);
    expect(s.state, SessionState.locked);
  });

  test('a parent report is signed, carries the sealed nickname, and its link opens on this phone', () async {
    final (s, api, _) = await signedIn();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    api.pairChild(p.childId);
    await s.refresh();
    const caseId = 'c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a';
    final r = await s.requestReport(p.childId, caseId, RecipientType.police);
    final sent = api.reportRequests[r.reportId]!;
    final family = FamilyCrypto(p.qr.familyKey);
    expect(sent.anonymous, isTrue);
    expect(family.verify(FamilyCrypto.reportMessage(r.reportId, caseId, 'police', true), sent.signature), isTrue);
    expect(family.open(sent.sealedChildName!), 'Amara'); // only the child's phone can read it
    expect(s.reports.single.status, ReportStatus.requested);

    api.completeReport(r.reportId, family.seal('http://kuuma/verify/s/x#k=secret'));
    await s.refresh();
    expect(await s.openReportLink(s.reports.single), 'http://kuuma/verify/s/x#k=secret');

    // Not anonymous: no name is sent at all.
    final named = await s.requestReport(p.childId, caseId, RecipientType.school, anonymous: false);
    expect(api.reportRequests[named.reportId]!.sealedChildName, isNull);
  });
}
