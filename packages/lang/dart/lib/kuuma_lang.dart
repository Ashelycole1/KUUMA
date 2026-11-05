/// Loader for kuuma-lang data (bundled from packages/lang/data by `make gen`).
library;

import 'dart:convert';

part 'src/lang_data.g.dart';

/// One value from a kuuma-lang file, e.g. one harm category.
class LangEntry {
  const LangEntry(this.id, this.labels, this.extra);

  final String id;
  final Map<String, String> labels;
  final Map<String, dynamic> extra;

  /// Label in [language] (a contract Language wire value), falling back to English.
  String label(String language) => labels[language] ?? labels['en']!;
}

abstract final class KuumaLang {
  static const files = [
    'harm-categories',
    'severity',
    'grooming-stages',
    'age-brackets',
    'recipient-types',
    'languages',
    'advice-keys',
  ];

  static final Map<String, dynamic> _root = jsonDecode(_kuumaLangJson) as Map<String, dynamic>;

  static List<LangEntry> entries(String file) {
    if (!files.contains(file)) throw ArgumentError('unknown kuuma-lang file $file');
    final values = (_root[file] as Map<String, dynamic>)['values'] as List<dynamic>;
    return values.map((v) {
      final m = v as Map<String, dynamic>;
      final labels = (m['label'] ?? m['text'] ?? <String, dynamic>{}) as Map<String, dynamic>;
      return LangEntry(
        m['id'] as String,
        labels.map((k, val) => MapEntry(k, val as String)),
        Map.of(m)
          ..remove('id')
          ..remove('label'),
      );
    }).toList();
  }

  static List<String> ids(String file) => entries(file).map((e) => e.id).toList();

  static String adviceText(String key, [String language = 'en']) =>
      entries('advice-keys').firstWhere((e) => e.id == key).label(language);
}
