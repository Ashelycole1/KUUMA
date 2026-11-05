import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:kuuma_contracts/kuuma_contracts.dart';

/// A failed `/v1/guardian` call. [code] is the server's error code; never message content.
class GuardianApiException implements Exception {
  GuardianApiException(this.status, this.code);

  final int status;
  final String code;

  bool get offline => status == 0;

  @override
  String toString() => 'GuardianApiException($status, $code)';
}

/// The `/v1/guardian` calls the parent's phone makes.
abstract class GuardianParentApi {
  /// Creates the account for this phone. Returns the session token.
  Future<ParentRegistrationResponse> register(String devicePublicKey);

  /// Starts adding a child with the first, signed policy.
  Future<PairingCodeResponse> createPairingCode(Policy signedPolicy);

  Future<List<ChildStatus>> children(String token);

  Future<void> putPolicy(String token, String childId, Policy signedPolicy);

  Future<List<Alert>> alerts(String token);

  Future<List<Approval>> approvals(String token);

  Future<ApprovalResult> decide(String token, String approvalId, ApprovalDecision decision, String signature);

  /// The token [createPairingCode] uses. Set by the session after sign-in.
  String? token;
}

/// The real API over HTTP. Debug builds point at the local stack (`make dev`).
class HttpGuardianParentApi implements GuardianParentApi {
  HttpGuardianParentApi({String? baseUrl, http.Client? client})
      : baseUrl = baseUrl ?? const String.fromEnvironment('KUUMA_API_URL', defaultValue: 'http://10.0.2.2:8080'),
        _client = client ?? http.Client();

  final String baseUrl;
  final http.Client _client;

  @override
  String? token;

  Future<dynamic> _call(String method, String path, {Object? body, String? auth}) async {
    final uri = Uri.parse('${baseUrl.replaceAll(RegExp(r'/$'), '')}$path');
    final headers = {
      'Content-Type': 'application/json',
      if (auth != null) 'Authorization': 'Bearer $auth',
    };
    http.Response r;
    try {
      final req = http.Request(method, uri)..headers.addAll(headers);
      if (body != null) req.body = jsonEncode(body);
      r = await http.Response.fromStream(await _client.send(req).timeout(const Duration(seconds: 20)));
    } catch (_) {
      throw GuardianApiException(0, 'offline');
    }
    if (r.statusCode < 200 || r.statusCode > 299) {
      var code = 'http_${r.statusCode}';
      try {
        code = ((jsonDecode(r.body) as Map<String, dynamic>)['error'] as Map<String, dynamic>)['code'] as String;
      } catch (_) {}
      throw GuardianApiException(r.statusCode, code);
    }
    return r.body.isEmpty ? null : jsonDecode(r.body);
  }

  String get _token => token ?? (throw GuardianApiException(401, 'signed_out'));

  @override
  Future<ParentRegistrationResponse> register(String devicePublicKey) async => ParentRegistrationResponse.fromJson(
      await _call('POST', '/v1/guardian/parents', body: {'devicePublicKey': devicePublicKey}));

  @override
  Future<PairingCodeResponse> createPairingCode(Policy signedPolicy) async => PairingCodeResponse.fromJson(
      await _call('POST', '/v1/guardian/pairing-codes', body: {'policy': signedPolicy.toJson()}, auth: _token));

  @override
  Future<List<ChildStatus>> children(String token) async {
    final json = await _call('GET', '/v1/guardian/children', auth: token) as Map<String, dynamic>;
    return [for (final c in json['children'] as List<dynamic>) ChildStatus.fromJson(c as Map<String, dynamic>)];
  }

  @override
  Future<void> putPolicy(String token, String childId, Policy signedPolicy) =>
      _call('PUT', '/v1/guardian/children/$childId/policy', body: signedPolicy.toJson(), auth: token);

  @override
  Future<List<Alert>> alerts(String token) async {
    final json = await _call('GET', '/v1/guardian/alerts', auth: token) as Map<String, dynamic>;
    return [for (final a in json['alerts'] as List<dynamic>) Alert.fromJson(a as Map<String, dynamic>)];
  }

  @override
  Future<List<Approval>> approvals(String token) async {
    final json = await _call('GET', '/v1/guardian/approvals', auth: token) as Map<String, dynamic>;
    return [for (final a in json['approvals'] as List<dynamic>) Approval.fromJson(a as Map<String, dynamic>)];
  }

  @override
  Future<ApprovalResult> decide(String token, String approvalId, ApprovalDecision decision, String signature) async =>
      ApprovalResult.fromJson(await _call('POST', '/v1/guardian/approvals/$approvalId',
          body: {'decision': decision.wire, 'signature': signature}, auth: token));
}
