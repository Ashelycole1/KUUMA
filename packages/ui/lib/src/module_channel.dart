import 'package:flutter/services.dart';

/// Lets a module's Flutter screens call their own Kotlin half (`KuumaModule.onCall`).
///
///     final vault = ModuleChannel('casefile');
///     final cases = await vault.call<List<Object?>>('listCases');
///
/// Each module gets its own channel, so a screen can only reach its own module.
class ModuleChannel {
  ModuleChannel(this.namespace, {MethodChannel? channel})
      : _channel = channel ?? MethodChannel('ug.kuuma/module/$namespace');

  final String namespace;
  final MethodChannel _channel;

  /// Calls [method]. Returns null when the native side is absent (tests, desktop).
  Future<T?> call<T>(String method, [Map<String, Object?> args = const {}]) async {
    try {
      return await _channel.invokeMethod<T>(method, args);
    } on MissingPluginException {
      return null;
    }
  }
}
