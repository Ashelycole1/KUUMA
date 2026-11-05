import 'dart:async';

import 'package:flutter/services.dart';

/// A module as the Kotlin module registry describes it.
class NativeModule {
  NativeModule.fromMap(Map<dynamic, dynamic> m)
      : namespace = m['namespace'] as String,
        displayName = m['displayName'] as String,
        isFake = m['isFake'] as bool,
        enabled = m['enabled'] as bool? ?? true,
        screens = [for (final s in m['screens'] as List<dynamic>) NativeScreen.fromMap(s as Map<dynamic, dynamic>)],
        permissions = [
          for (final p in m['permissions'] as List<dynamic>) (p as Map<dynamic, dynamic>)['permission'] as String
        ];

  final String namespace;
  final String displayName;
  final bool isFake;
  final bool enabled;
  final List<NativeScreen> screens;
  final List<String> permissions;
}

class NativeScreen {
  NativeScreen.fromMap(Map<dynamic, dynamic> m)
      : id = m['id'] as String,
        slot = m['slot'] as String,
        titleKey = m['titleKey'] as String;

  final String id;
  final String slot;
  final String titleKey;
}

/// Bridge to the Android host (KuumaChannels.kt). Off-device (tests) it reports nothing.
class NativeRegistry {
  NativeRegistry({MethodChannel? registry, EventChannel? bus})
      : _registry = registry ?? const MethodChannel('ug.kuuma/registry'),
        _bus = bus ?? const EventChannel('ug.kuuma/bus');

  final MethodChannel _registry;
  final EventChannel _bus;

  Future<List<NativeModule>> modules() async {
    try {
      final list = await _registry.invokeMethod<List<dynamic>>('modules') ?? const [];
      return [for (final m in list) NativeModule.fromMap(m as Map<dynamic, dynamic>)];
    } on MissingPluginException {
      return const [];
    }
  }

  Future<List<String>> scenarios() async {
    try {
      return (await _registry.invokeMethod<List<dynamic>>('scenarios') ?? const []).cast<String>();
    } on MissingPluginException {
      return const [];
    }
  }

  Future<void> playScenario(String name) => _registry.invokeMethod<void>('playScenario', {'name': name});

  Future<void> setFakeEnabled(String namespace, bool enabled) =>
      _registry.invokeMethod<void>('setFakeEnabled', {'namespace': namespace, 'enabled': enabled});

  /// Every bus event (debug builds only), as JSON maps with message text already removed.
  Stream<Map<dynamic, dynamic>> events() => _bus
      .receiveBroadcastStream()
      .map((e) => e as Map<dynamic, dynamic>)
      .handleError((Object _) {}, test: (e) => e is MissingPluginException);
}
