import 'package:flutter/material.dart';

import 'l10n.dart';

/// Quick-hide: if someone looks over the child's shoulder, one tap (or a shake, wired by
/// the shell) swaps the whole app for a dull "Notes" screen. Unhiding needs the lock screen.
class QuickHideController extends ChangeNotifier {
  bool _hidden = false;
  bool get hidden => _hidden;

  void hide() {
    if (!_hidden) {
      _hidden = true;
      notifyListeners();
    }
  }

  /// Call only after the lock screen succeeded.
  void reveal() {
    if (_hidden) {
      _hidden = false;
      notifyListeners();
    }
  }
}

class QuickHide extends StatelessWidget {
  const QuickHide({super.key, required this.controller, required this.child, required this.lockScreen});

  final QuickHideController controller;
  final Widget child;

  /// Shown when the user taps the decoy to come back; must call controller.reveal() on success.
  final WidgetBuilder lockScreen;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => controller.hidden ? DecoyScreen(onLongPress: () => _unlock(context)) : child,
    );
  }

  void _unlock(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: lockScreen));
  }
}

/// A neutral, boring screen. Long-press the title to unlock.
class DecoyScreen extends StatelessWidget {
  const DecoyScreen({super.key, this.onLongPress});

  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar:
          AppBar(title: GestureDetector(onLongPress: onLongPress, child: Text(context.t('core.discreet.decoy_title')))),
      body: Center(child: Text(context.t('core.discreet.decoy_body'))),
    );
  }
}

/// A floating "hide" button the shell places on every screen in discreet mode.
class QuickHideButton extends StatelessWidget {
  const QuickHideButton({super.key, required this.controller});

  final QuickHideController controller;

  @override
  Widget build(BuildContext context) => IconButton(
        tooltip: context.t('core.discreet.hide'),
        icon: const Icon(Icons.visibility_off_outlined),
        onPressed: controller.hide,
      );
}

/// Notification text that reveals nothing. Modules ask for a [NeutralNotification] instead of
/// writing their own titles, so a notification on the lock screen never says "harassment".
class NeutralNotification {
  const NeutralNotification({required this.title, required this.body});

  final String title;
  final String body;

  static NeutralNotification build(String languageCode) => NeutralNotification(
        title: KuumaL10n.instance.translate('core.notify.neutral_title', languageCode),
        body: KuumaL10n.instance.translate('core.notify.neutral_body', languageCode),
      );
}
