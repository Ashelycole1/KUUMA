import 'dart:async';

import 'package:flutter/services.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';

/// One advice card, already in the child's language and age bracket.
class AdviceCardView {
  const AdviceCardView({required this.key, required this.text, this.steps = const []});

  AdviceCardView.fromMap(Map<dynamic, dynamic> m)
      : key = m['key'] as String,
        text = m['text'] as String,
        steps = _strings(m['steps']);

  final String key;
  final String text;
  final List<String> steps;
}

/// Everything shown for one flag. Built by the Kotlin CompanionModule from the content pack;
/// it holds IDs, enums and advice text only, never the message itself.
class AdvicePlanView {
  const AdvicePlanView({
    required this.flagId,
    required this.category,
    required this.severity,
    required this.appSource,
    required this.crisis,
    required this.cards,
    this.blockSteps = const [],
    this.reportSteps = const [],
  });

  AdvicePlanView.fromMap(Map<dynamic, dynamic> m)
      : flagId = m['flagId'] as String,
        category = HarmCategory.fromWire(m['category'] as String),
        severity = Severity.fromWire(m['severity'] as String),
        appSource = AppSource.fromWire(m['appSource'] as String),
        crisis = m['crisis'] as bool,
        cards = [for (final c in m['cards'] as List<dynamic>) AdviceCardView.fromMap(c as Map<dynamic, dynamic>)],
        blockSteps = _strings(m['blockSteps']),
        reportSteps = _strings(m['reportSteps']);

  final String flagId;
  final HarmCategory category;
  final Severity severity;
  final AppSource appSource;

  /// Self-harm signals: show the crisis screens at once.
  final bool crisis;
  final List<AdviceCardView> cards;
  final List<String> blockSteps;
  final List<String> reportSteps;
}

class Helpline {
  const Helpline({required this.id, required this.number, required this.name, this.hours = '', this.free = false});

  Helpline.fromMap(Map<dynamic, dynamic> m)
      : id = m['id'] as String,
        number = m['number'] as String,
        name = m['name'] as String,
        hours = m['hours'] as String? ?? '',
        free = m['free'] as bool? ?? false;

  final String id;
  final String number;
  final String name;
  final String hours;
  final bool free;
}

/// The calm screens for self-harm signals, from the content pack.
class CrisisContent {
  const CrisisContent({
    required this.title,
    required this.body,
    required this.breathe,
    required this.callPrompt,
    required this.helplines,
  });

  CrisisContent.fromMap(Map<dynamic, dynamic> m)
      : title = m['title'] as String,
        body = m['body'] as String,
        breathe = m['breathe'] as String,
        callPrompt = m['callPrompt'] as String,
        helplines = [for (final h in m['helplines'] as List<dynamic>) Helpline.fromMap(h as Map<dynamic, dynamic>)];

  final String title;
  final String body;
  final String breathe;
  final String callPrompt;
  final List<Helpline> helplines;
}

/// The Flutter screens' only way to reach Companion's Kotlin half (CompanionModule).
///
/// Kotlin publishes every event; these calls only ask it to. Nothing here stores anything.
abstract class CompanionBridge {
  /// New or revised advice, as flags arrive.
  Stream<AdvicePlanView> get advice;

  /// Advice for the most recent flags, newest first (in memory only; empty after a restart).
  Future<List<AdvicePlanView>> recent();

  Future<CrisisContent> crisis();

  /// Publishes companion.feedback.submitted.
  Future<void> submitFeedback(String flagId, FeedbackVerdict verdict, {required bool consentToTrain});

  /// Publishes companion.advice.shown (once per flag and card; Kotlin removes repeats).
  Future<void> adviceShown(String flagId, String adviceKey);

  /// Publishes companion.capture.requested for the open watched chat. False if none is open.
  Future<bool> requestCapture();

  /// Publishes companion.sos.triggered. Returns the case id used.
  Future<String> triggerSos();

  /// Opens the phone's dialler with [number] filled in (the child still presses call).
  Future<void> dial(String number);
}

/// The real bridge, over platform channels. The Android host connects these channel names to
/// ug.kuuma.companion.CompanionBridge (see modules/companion/README.md).
class ChannelCompanionBridge implements CompanionBridge {
  ChannelCompanionBridge({MethodChannel? methods, EventChannel? events})
      : _methods = methods ?? const MethodChannel(methodChannel),
        _events = events ?? const EventChannel(eventChannel);

  static const methodChannel = 'ug.kuuma.companion/bridge';
  static const eventChannel = 'ug.kuuma.companion/advice';

  final MethodChannel _methods;
  final EventChannel _events;

  @override
  late final Stream<AdvicePlanView> advice = _events
      .receiveBroadcastStream()
      .map((e) => AdvicePlanView.fromMap(e as Map<dynamic, dynamic>))
      .handleError((Object _) {}, test: (e) => e is MissingPluginException);

  @override
  Future<List<AdvicePlanView>> recent() async {
    try {
      final list = await _methods.invokeMethod<List<dynamic>>('recent') ?? const [];
      return [for (final m in list) AdvicePlanView.fromMap(m as Map<dynamic, dynamic>)];
    } on MissingPluginException {
      return const [];
    }
  }

  @override
  Future<CrisisContent> crisis() async =>
      CrisisContent.fromMap((await _methods.invokeMethod<Map<dynamic, dynamic>>('crisis'))!);

  @override
  Future<void> submitFeedback(String flagId, FeedbackVerdict verdict, {required bool consentToTrain}) => _methods
      .invokeMethod<void>('feedback', {'flagId': flagId, 'verdict': verdict.wire, 'consentToTrain': consentToTrain});

  @override
  Future<void> adviceShown(String flagId, String adviceKey) =>
      _methods.invokeMethod<void>('adviceShown', {'flagId': flagId, 'adviceKey': adviceKey});

  @override
  Future<bool> requestCapture() async => await _methods.invokeMethod<bool>('capture') ?? false;

  @override
  Future<String> triggerSos() async => (await _methods.invokeMethod<String>('sos'))!;

  @override
  Future<void> dial(String number) => _methods.invokeMethod<void>('dial', {'number': number});
}

List<String> _strings(Object? v) => v == null ? const [] : [for (final s in v as List<dynamic>) s as String];
