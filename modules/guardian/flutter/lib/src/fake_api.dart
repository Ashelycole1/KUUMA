import 'dart:math';

import 'package:kuuma_contracts/kuuma_contracts.dart';

import 'api.dart';

String _uuid(Random r) {
  String hex(int n) => List.generate(n, (_) => r.nextInt(16).toRadixString(16)).join();
  return '${hex(8)}-${hex(4)}-4${hex(3)}-${'89ab'[r.nextInt(4)]}${hex(3)}-${hex(12)}';
}

String _iso(DateTime t) => '${t.toUtc().toIso8601String().split('.').first}Z';

/// An in-memory `/v1/guardian` for tests and for running the parent app with no server.
/// It behaves like the service: one session, signed policies only, decisions once.
class FakeGuardianParentApi implements GuardianParentApi {
  FakeGuardianParentApi({Random? random, DateTime Function()? clock})
      : _random = random ?? Random(7),
        _clock = clock ?? DateTime.now;

  final Random _random;
  final DateTime Function() _clock;
  final Map<String, ChildStatus> _children = {};
  final List<Alert> _alerts = [];
  final List<Approval> _approvals = [];
  final Map<String, Policy> policies = {};
  final List<String> calls = [];
  bool online = true;
  String? _session;

  @override
  String? token;

  void _check([String? auth]) {
    if (!online) throw GuardianApiException(0, 'offline');
    if (auth != null && auth != _session) throw GuardianApiException(401, 'unauthorized');
  }

  @override
  Future<ParentRegistrationResponse> register(String devicePublicKey) async {
    _check();
    calls.add('register');
    _session = 'session-${_uuid(_random)}';
    return ParentRegistrationResponse(parentId: _uuid(_random), sessionToken: _session!);
  }

  @override
  Future<PairingCodeResponse> createPairingCode(Policy signedPolicy) async {
    _check(token);
    if (signedPolicy.signature == null) throw GuardianApiException(422, 'unsigned_policy');
    calls.add('createPairingCode');
    final id = _uuid(_random);
    _children[id] = ChildStatus(
        childId: id,
        paired: false,
        ageBracket: signedPolicy.ageBracket,
        policyVersion: signedPolicy.policyVersion,
        missingPermissions: const []);
    policies[id] = signedPolicy;
    return PairingCodeResponse(
        childId: id,
        pairingCode: _random.nextInt(1000000).toString().padLeft(6, '0'),
        expiresAt: _iso(_clock().add(const Duration(minutes: 15))));
  }

  @override
  Future<List<ChildStatus>> children(String token) async {
    _check(token);
    return _children.values.toList();
  }

  @override
  Future<void> putPolicy(String token, String childId, Policy signedPolicy) async {
    _check(token);
    final c = _children[childId] ?? (throw GuardianApiException(404, 'not_found'));
    if (signedPolicy.signature == null) throw GuardianApiException(422, 'unsigned_policy');
    if (signedPolicy.policyVersion <= c.policyVersion) throw GuardianApiException(409, 'stale_policy');
    calls.add('putPolicy');
    policies[childId] = signedPolicy;
    _children[childId] = ChildStatus(
        childId: childId,
        paired: c.paired,
        ageBracket: signedPolicy.ageBracket,
        policyVersion: signedPolicy.policyVersion,
        lastHeartbeatAt: c.lastHeartbeatAt,
        protectionActive: c.protectionActive,
        missingPermissions: c.missingPermissions);
  }

  @override
  Future<List<Alert>> alerts(String token) async {
    _check(token);
    return _alerts.reversed.toList();
  }

  @override
  Future<List<Approval>> approvals(String token) async {
    _check(token);
    return _approvals.reversed.toList();
  }

  @override
  Future<ApprovalResult> decide(String token, String approvalId, ApprovalDecision decision, String signature) async {
    _check(token);
    final i = _approvals.indexWhere((a) => a.approvalId == approvalId);
    if (i < 0) throw GuardianApiException(404, 'not_found');
    final a = _approvals[i];
    if (a.status != ApprovalStatus.pending) throw GuardianApiException(409, 'already_decided');
    calls.add('decide:${decision.wire}:$signature');
    final at = _iso(_clock());
    _approvals[i] = Approval(
        approvalId: a.approvalId,
        requestId: a.requestId,
        caseId: a.caseId,
        kind: a.kind,
        recipientType: a.recipientType,
        status: decision == ApprovalDecision.approve ? ApprovalStatus.approved : ApprovalStatus.declined,
        createdAt: a.createdAt,
        decidedAt: at);
    return ApprovalResult(
        approvalId: a.approvalId,
        requestId: a.requestId,
        caseId: a.caseId,
        decision: decision,
        decidedAt: at,
        kind: a.kind,
        signature: signature);
  }

  // ------------------------------------------------------------------ what the child's phone would do

  /// The child's phone scanned the code and checked in.
  void pairChild(String childId, {bool protectionActive = true, List<String> missing = const []}) {
    final c = _children[childId]!;
    _children[childId] = ChildStatus(
        childId: childId,
        paired: true,
        ageBracket: c.ageBracket,
        policyVersion: c.policyVersion,
        lastHeartbeatAt: _iso(_clock()),
        protectionActive: protectionActive,
        missingPermissions: missing);
  }

  Alert addAlert(String childId,
      {AlertKind kind = AlertKind.flag,
      Severity severity = Severity.high,
      HarmCategory? category = HarmCategory.threat,
      AppSource? appSource = AppSource.whatsapp,
      String? sealedExcerpt,
      DateTime? at}) {
    final when = _iso(at ?? _clock());
    final a = Alert(
        alertId: _uuid(_random),
        caseId: _uuid(_random),
        severity: severity,
        category: kind == AlertKind.flag ? category : null,
        createdAt: when,
        read: false,
        childId: childId,
        kind: kind,
        appSource: kind == AlertKind.flag ? appSource : null,
        occurredAt: when,
        sealedExcerpt: sealedExcerpt);
    _alerts.add(a);
    return a;
  }

  Approval addApproval(String childId, {RecipientType recipient = RecipientType.school}) {
    final a = Approval(
        approvalId: _uuid(_random),
        requestId: _uuid(_random),
        caseId: _uuid(_random),
        kind: ApprovalKind.share,
        recipientType: recipient,
        status: ApprovalStatus.pending,
        createdAt: _iso(_clock()));
    _approvals.add(a);
    return a;
  }
}
