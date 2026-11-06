import 'package:flutter/foundation.dart';
import 'package:flutter/cupertino.dart' show CupertinoLocalizations;
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

/// Kuuma's four languages, as contract `Language` wire values and Flutter locales.
const kuumaLocales = [Locale('en'), Locale('lg'), Locale('nyn'), Locale('sw')];

/// Translation registry.
///
/// Key-naming rule: every key is `<prefix>.<area>.<name>`, where the prefix is the
/// owner: `core`, `sentinel`, `companion`, `casefile` or `guardian`. A package may only
/// register keys under its own prefix, so builds never overwrite each other's strings.
///
///     KuumaL10n.instance.register('companion', {
///       'en': {'companion.tab.title': 'Help'},
///       'sw': {'companion.tab.title': 'Msaada'},
///     });
///     Text(context.t('companion.tab.title'));
///
/// Missing translations fall back to English, then to the key itself (so a gap is visible).
class KuumaL10n {
  KuumaL10n._() {
    register('core', _coreStrings);
  }

  static final KuumaL10n instance = KuumaL10n._();

  static const prefixes = {'core', 'sentinel', 'companion', 'casefile', 'guardian'};
  static final _keyPattern = RegExp(r'^[a-z]+(\.[a-z0-9_]+){2,}$');

  final Map<String, Map<String, String>> _byLanguage = {for (final l in kuumaLocales) l.languageCode: {}};
  final Set<String> _registered = {};

  void register(String prefix, Map<String, Map<String, String>> strings) {
    if (!prefixes.contains(prefix)) throw ArgumentError('unknown translation prefix $prefix');
    for (final MapEntry(key: lang, value: entries) in strings.entries) {
      final table = _byLanguage[lang];
      if (table == null) throw ArgumentError('unsupported language $lang');
      for (final key in entries.keys) {
        if (!key.startsWith('$prefix.') || !_keyPattern.hasMatch(key)) {
          throw ArgumentError('key $key must look like $prefix.<area>.<name>');
        }
      }
      table.addAll(entries);
    }
    _registered.add(prefix);
  }

  String translate(String key, String languageCode, [Map<String, String> args = const {}]) {
    var text = _byLanguage[languageCode]?[key] ?? _byLanguage['en']![key];
    if (text == null) {
      if (kDebugMode) debugPrint('kuuma_ui: missing translation key'); // never print values
      return key;
    }
    args.forEach((k, v) => text = text!.replaceAll('{$k}', v));
    return text!;
  }

  /// Keys present in English but missing in [languageCode] (for translators).
  List<String> missing(String languageCode) =>
      _byLanguage['en']!.keys.where((k) => !_byLanguage[languageCode]!.containsKey(k)).toList()..sort();
}

extension KuumaTranslate on BuildContext {
  String t(String key, [Map<String, String> args = const {}]) {
    final code = Localizations.maybeLocaleOf(this)?.languageCode ?? 'en';
    return KuumaL10n.instance.translate(key, code, args);
  }
}

/// Delegates for MaterialApp. Flutter ships Material/Cupertino strings for English and
/// Swahili but not Luganda or Runyankole; for those we fall back to English so the
/// framework widgets still work.
const kuumaLocalizationsDelegates = <LocalizationsDelegate<dynamic>>[
  _FallbackMaterial(),
  _FallbackCupertino(),
  GlobalWidgetsLocalizations.delegate,
];

bool _flutterSupports(Locale l) => l.languageCode == 'en' || l.languageCode == 'sw';

class _FallbackMaterial extends LocalizationsDelegate<MaterialLocalizations> {
  const _FallbackMaterial();
  @override
  bool isSupported(Locale locale) => kuumaLocales.any((l) => l.languageCode == locale.languageCode);
  @override
  Future<MaterialLocalizations> load(Locale locale) =>
      GlobalMaterialLocalizations.delegate.load(_flutterSupports(locale) ? locale : const Locale('en'));
  @override
  bool shouldReload(covariant LocalizationsDelegate<MaterialLocalizations> old) => false;
}

class _FallbackCupertino extends LocalizationsDelegate<CupertinoLocalizations> {
  const _FallbackCupertino();
  @override
  bool isSupported(Locale locale) => kuumaLocales.any((l) => l.languageCode == locale.languageCode);
  @override
  Future<CupertinoLocalizations> load(Locale locale) =>
      GlobalCupertinoLocalizations.delegate.load(_flutterSupports(locale) ? locale : const Locale('en'));
  @override
  bool shouldReload(covariant LocalizationsDelegate<CupertinoLocalizations> old) => false;
}

// English is the source. lg / nyn / sw are added by native speakers (see CONTRIBUTING.md).
const _coreStrings = <String, Map<String, String>>{
  'en': {
    'core.severity.low': 'Low',
    'core.severity.medium': 'Medium',
    'core.severity.high': 'High',
    'core.lock.title': 'Enter your PIN',
    'core.lock.wrong_pin': 'That PIN is not right. Try again.',
    'core.lock.use_fingerprint': 'Use fingerprint',
    'core.lock.delete': 'Delete',
    'core.lock.fingerprint_reason': 'Unlock Kuuma',
    'core.discreet.decoy_title': 'Notes',
    'core.discreet.decoy_body': 'No notes yet.',
    'core.discreet.hide': 'Hide',
    'core.notify.neutral_title': 'Reminder',
    'core.notify.neutral_body': 'You have a new update.',
    'core.nav.modules': 'Modules',
    'core.nav.settings': 'Settings',
    'core.nav.debug': 'Debug',
    'core.placeholder.title': '{module} is coming soon',
    'core.placeholder.body': 'This screen is a placeholder. The {module} team will build it here.',
  },
  'sw': {
    'core.severity.low': 'Chini',
    'core.severity.medium': 'Wastani',
    'core.severity.high': 'Juu',
    'core.discreet.decoy_title': 'Maelezo',
    'core.notify.neutral_title': 'Kikumbusho',
    'core.nav.settings': 'Mipangilio',
  },
  'lg': {},
  'nyn': {},
};
