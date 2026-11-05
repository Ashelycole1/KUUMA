import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_lang/kuuma_lang.dart';
import 'package:test/test.dart';

/// Fails if kuuma-lang values and the generated contract enums ever differ.
void main() {
  test('values equal contract enums', () {
    expect(KuumaLang.ids('harm-categories'), HarmCategory.values.map((v) => v.wire).toList());
    expect(KuumaLang.ids('severity'), Severity.values.map((v) => v.wire).toList());
    expect(KuumaLang.ids('grooming-stages'), GroomingStage.values.map((v) => v.wire).toList());
    expect(KuumaLang.ids('age-brackets'), AgeBracket.values.map((v) => v.wire).toList());
    expect(KuumaLang.ids('recipient-types'), RecipientType.values.map((v) => v.wire).toList());
    expect(KuumaLang.ids('languages'), Language.values.map((v) => v.wire).toList());
  });

  test('advice keys match the contract pattern', () {
    for (final id in KuumaLang.ids('advice-keys')) {
      expect(ContractPatterns.adviceKey.hasMatch(id), isTrue, reason: id);
    }
  });

  test('labels fall back to English', () {
    expect(KuumaLang.adviceText('advice.threat.tell_adult', 'nyn'), startsWith('Please tell'));
  });
}
