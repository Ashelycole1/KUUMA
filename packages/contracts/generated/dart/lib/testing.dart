/// Contract-test harness for Flutter/Dart builds.
///
///     import 'package:kuuma_contracts/testing.dart';
///     assertPublishes('guardian', event.toJson());
library;

import 'dart:convert';

import 'package:json_schema/json_schema.dart';

import 'kuuma_contracts.dart';

const _idBase = 'https://schemas.kuuma.ug/';

class ContractViolation implements Exception {
  ContractViolation(this.message);
  final String message;
  @override
  String toString() => 'ContractViolation: $message';
}

final Map<String, dynamic> _bundle = jsonDecode(kuumaSchemaBundle) as Map<String, dynamic>;
final Map<String, JsonSchema> _compiled = {};

JsonSchema _schema(String id) => _compiled.putIfAbsent(id, () {
      final raw = _bundle[id];
      if (raw == null) throw ContractViolation('no schema $id');
      return JsonSchema.create(
        raw,
        refProvider: RefProvider.sync((String ref) {
          final key = ref.split('#').first;
          return _bundle[key] as Map<String, dynamic>?;
        }),
      );
    });

void _check(String id, Object? data) {
  final result = _schema(id).validate(data);
  if (!result.isValid) {
    final detail = result.errors.take(10).map((e) => '${e.instancePath}: ${e.message}').join('; ');
    throw ContractViolation('$id rejected the data: $detail');
  }
}

/// Validate one event (as JSON map) against its schema.
EventSpec<EventPayload> validateEvent(Map<String, dynamic> event) {
  final spec = Events.byType(event['type'] as String, event['version'] as int);
  if (spec == null) throw ContractViolation('unknown event ${event['type']}.v${event['version']}');
  _check('${_idBase}events/${spec.type}.v${spec.version}.json', event);
  return spec;
}

/// Assert [namespace] may publish [event] and that it matches the schema.
void assertPublishes(String namespace, Map<String, dynamic> event, {bool leavingPhone = false}) {
  final spec = validateEvent(event);
  if (spec.publisher != namespace) {
    throw ContractViolation('$namespace may not publish ${spec.type}; it belongs to ${spec.publisher}');
  }
  if (leavingPhone && spec.phoneOnly) {
    throw ContractViolation('${spec.type} is phone only and must never leave the phone');
  }
}

/// Validate an API body, e.g. validateBody('api/alert-list.json', body).
void validateBody(String schemaFile, Object? body) => _check('$_idBase$schemaFile', body);
