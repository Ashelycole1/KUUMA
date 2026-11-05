import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

/// Hello-world screen shown in the app until the Casefile owner builds the real one.
class PlaceholderScreen extends StatelessWidget {
  const PlaceholderScreen({super.key, required this.moduleName, required this.screenId});

  final String moduleName;
  final String screenId;

  @override
  Widget build(BuildContext context) => EmptyState(
        icon: Icons.construction_outlined,
        title: context.t('core.placeholder.title', {'module': moduleName}),
        message: '${context.t('core.placeholder.body', {'module': moduleName})}\n($screenId)',
      );
}
