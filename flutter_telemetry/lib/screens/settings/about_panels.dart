part of '../settings_screen.dart';

// GeelyTools deviation: the Capy in-app updater panels are removed —
// application updates live in the shared GeelyTools settings menu.

const _developerHandle = '@timoteohss';

class _AboutPanel extends StatelessWidget {
  const _AboutPanel();

  static final _random = math.Random();

  static const _confettiColors = <Color>[
    Color(0xFF3BB8FF),
    Color(0xFF30E3A2),
    Color(0xFFFFD447),
    Color(0xFFFF4B55),
  ];

  double _randomInRange(double min, double max) {
    return min + _random.nextDouble() * (max - min);
  }

  // Easter egg: launch several randomized bursts across the whole screen every
  // time the credit card is tapped.
  void _celebrate(BuildContext context) {
    HapticFeedback.mediumImpact();
    for (var burst = 0; burst < 6; burst++) {
      Confetti.launch(
        context,
        options: ConfettiOptions(
          angle: _randomInRange(55, 125),
          spread: _randomInRange(50, 70),
          particleCount: _randomInRange(35, 60).toInt(),
          startVelocity: _randomInRange(30, 50),
          x: _randomInRange(0.05, 0.95),
          y: _randomInRange(0.05, 0.95),
          colors: _confettiColors,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context)!;
    final colorScheme = _colors(context);
    return Container(
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainer,
        border: Border.all(color: colorScheme.outlineVariant),
        borderRadius: AutomotiveRadii.baseRadius,
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () {
            DeveloperToolsGate.instance.registerEasterEggTap();
            _celebrate(context);
          },
          child: Padding(
            padding: AutomotiveSpacing.panelPadding,
            child: Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: colorScheme.surfaceContainerHigh,
                    borderRadius: AutomotiveRadii.fullRadius,
                  ),
                  child: Icon(
                    Icons.code,
                    color: colorScheme.secondary,
                    size: 22,
                  ),
                ),
                const SizedBox(width: AutomotiveSpacing.x3),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        loc.settingsAboutDeveloper(_developerHandle),
                        style: AutomotiveTextStyles.bodyMd.copyWith(
                          color: colorScheme.onSurface,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: AutomotiveSpacing.x0_5),
                      Text(
                        loc.settingsAboutTagline,
                        style: AutomotiveTextStyles.bodyMd.copyWith(
                          color: colorScheme.onSurfaceVariant,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _AppShellPanel extends StatelessWidget {
  const _AppShellPanel();

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context)!;
    final colorScheme = _colors(context);
    return AnimatedBuilder(
      animation: AppExperienceController.instance,
      builder: (context, _) {
        final selected = <bool>{AppExperienceController.instance.newUiEnabled};
        return TechnicalPanel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                loc.settingsAppShellTitle,
                style: AutomotiveTextStyles.bodyLg.copyWith(
                  color: colorScheme.onSurface,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: AutomotiveSpacing.x1),
              Text(
                loc.settingsAppShellDescription,
                style: AutomotiveTextStyles.bodyMd.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: AutomotiveSpacing.x2),
              SegmentedButton<bool>(
                segments: [
                  ButtonSegment(
                    value: true,
                    icon: const Icon(Icons.auto_awesome),
                    label: Text(loc.settingsAppShellNew),
                  ),
                  ButtonSegment(
                    value: false,
                    icon: const Icon(Icons.history),
                    label: Text(loc.settingsAppShellPrevious),
                  ),
                ],
                selected: selected,
                showSelectedIcon: false,
                onSelectionChanged: (selection) {
                  HapticFeedback.selectionClick();
                  AppExperienceController.instance.setNewUiEnabled(
                    selection.first,
                  );
                },
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ClearDatabaseDialog extends StatelessWidget {
  const _ClearDatabaseDialog();

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context)!;
    return AlertDialog(
      backgroundColor: _colors(context).surfaceContainer,
      shape: RoundedRectangleBorder(borderRadius: AutomotiveRadii.lgRadius),
      title: Text(
        loc.settingsWipeDialogTitle,
        style: AutomotiveTextStyles.headlineMd.copyWith(
          color: _colors(context).error,
        ),
      ),
      content: Text(
        loc.settingsWipeDialogContent,
        style: AutomotiveTextStyles.bodyMd.copyWith(
          color: _colors(context).onSurfaceVariant,
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(loc.settingsCancel),
        ),
        FilledButton.icon(
          onPressed: () => Navigator.of(context).pop(true),
          icon: Icon(Icons.delete_forever),
          label: Text(loc.settingsWipeAll),
          style: FilledButton.styleFrom(
            backgroundColor: _colors(context).errorContainer,
            foregroundColor: _colors(context).onErrorContainer,
            shape: RoundedRectangleBorder(
              borderRadius: AutomotiveRadii.baseRadius,
            ),
          ),
        ),
      ],
    );
  }
}
