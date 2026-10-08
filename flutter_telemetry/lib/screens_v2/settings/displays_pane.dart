import 'package:flutter/material.dart';

import '../../core/app_experience_controller.dart';
import '../../core/car_settings_source.dart';
import '../../core/efficiency_unit.dart';
import '../../l10n/app_localizations.dart';
import 'package:capy_ui/capy_ui.dart';

/// Displays: everything about how the app looks on the head unit.
class DisplaysPane extends StatefulWidget {
  const DisplaysPane({super.key});

  @override
  State<DisplaysPane> createState() => _DisplaysPaneState();
}

class _DisplaysPaneState extends State<DisplaysPane> {
  late final CarSettingsSource _source = CarSettingsSource();
  late final SettingsBodyController _controller = SettingsBodyController(
    source: _source,
  );

  @override
  void dispose() {
    _controller.dispose();
    _source.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final loc = AppLocalizations.of(context)!;
    return SettingsSections(
      children: [
        AppCard(
          title: loc.v2SettingsAppearance,
          child: SettingsRows(
            children: [
              // GeelyTools deviation: the Capy theme picker («Оформление») is
              // not duplicated here — the theme is owned by the shared
              // GeelyTools settings menu and mirrored into this module. The
              // reduce-motion toggle (not present in the shared menu) keeps
              // the exact Capy row from SettingsBody.
              AnimatedBuilder(
                animation: _controller,
                builder: (context, _) {
                  final uiL10n =
                      Localizations.of<CapyUiL10n>(context, CapyUiL10n) ??
                      lookupCapyUiL10n(const Locale('en'));
                  return SettingToggleRow(
                    label: uiL10n.settingsReduceMotionLabel,
                    description: uiL10n.settingsReduceMotionDesc,
                    value: _controller.reduceMotion,
                    onChanged: _controller.setReduceMotion,
                  );
                },
              ),
              SettingsEntry(
                title: loc.settingsEfficiencyUnit,
                description: loc.settingsEfficiencyUnitDesc,
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: ListenableBuilder(
                    listenable: EfficiencyUnitController.instance,
                    builder: (context, _) =>
                        TrackSegmentedControl<EfficiencyUnit>(
                          items: [
                            for (final unit in EfficiencyUnit.values)
                              TabItem(
                                value: unit,
                                label: efficiencyUnitSuffix(unit, loc),
                              ),
                          ],
                          selected: EfficiencyUnitController.instance.unit,
                          onSelected: (unit) =>
                              EfficiencyUnitController.instance.setUnit(unit),
                        ),
                  ),
                ),
              ),
              // GeelyTools deviation: the «Полный экран» (immersive) and
              // «Полоса климата» (climate bar) toggles are not duplicated
              // here — both live in the shared GeelyTools settings menu and
              // are mirrored into this module's preference store.
              // Only in the windowed mode. Immersive hides both bars already,
              // so the row would be a control with nothing to do.
              if (!AppExperienceController.instance.immersiveEnabled)
                SettingToggleRow(
                  label: loc.v2SettingsHideStatusBar,
                  description: loc.v2SettingsHideStatusBarDesc,
                  value: AppExperienceController.instance.statusBarHidden,
                  onChanged: (hidden) async {
                    await AppExperienceController.instance.setStatusBarHidden(
                      hidden,
                    );
                    if (mounted) setState(() {});
                  },
                ),
            ],
          ),
        ),
      ],
    );
  }
}
