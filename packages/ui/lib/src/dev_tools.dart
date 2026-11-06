/// Developer screens (event console, module list, fake switches) are left out of the app
/// unless it is built with `--dart-define=KUUMA_DEV_TOOLS=true`. Demo and release builds
/// never show them, debug or not.
const bool kuumaDevTools = bool.fromEnvironment('KUUMA_DEV_TOOLS');
