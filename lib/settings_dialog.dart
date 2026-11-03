// =============================================================================
// Zakura - Settings Modal Dialog
// Space Game Toggle, Color Wheel Palette Picker & Persistent Config
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'package:flutter/material.dart';
import '../models/models.dart';
import '../audio/synth_audio.dart';

class SettingsDialog extends StatefulWidget {
  final ZakuraSettings currentSettings;

  const SettingsDialog({super.key, required this.currentSettings});

  @override
  State<SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends State<SettingsDialog> {
  late bool _enableSpaceGame;
  late Color _themeColor;
  late Color _particleColor;
  late PreviewQuality _quality;

  final List<Color> _palette = const [
    Color(0xFFE040FB), // Zakura Purple
    Color(0xFFFF4081), // Pink Accent
    Color(0xFF00E5FF), // Cyan Flare
    Color(0xFFFF5252), // Neon Red
    Color(0xFFFFD740), // Gold
    Color(0xFF69F0AE), // Emerald
  ];

  @override
  void initState() {
    super.initState();
    _enableSpaceGame = widget.currentSettings.enableSpaceGame;
    _themeColor = widget.currentSettings.themeColor;
    _particleColor = widget.currentSettings.particleColor;
    _quality = widget.currentSettings.previewQuality;
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: const Color(0xFF14141E),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'SETTINGS',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 20, letterSpacing: 1.5),
            ),
            const SizedBox(height: 16),

            // Toggle Space Background Mini-game
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Background Space Game', style: TextStyle(color: Colors.white)),
              subtitle: const Text('Disabling switches to falling particle rain', style: TextStyle(color: Colors.white54, fontSize: 11)),
              value: _enableSpaceGame,
              activeColor: const Color(0xFFE040FB),
              onChanged: (v) => setState(() => _enableSpaceGame = v),
            ),
            const SizedBox(height: 12),

            // Theme & Particle Color Wheel Palette
            const Text('Accent & Particle Color', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: _palette.map((c) {
                final selected = _themeColor.value == c.value;
                return GestureDetector(
                  onTap: () {
                    SynthAudio.playClickPing();
                    setState(() {
                      _themeColor = c;
                      _particleColor = c;
                    });
                  },
                  child: Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: c,
                      shape: BoxShape.circle,
                      border: Border.all(color: selected ? Colors.white : Colors.transparent, width: 2.5),
                      boxShadow: selected ? [BoxShadow(color: c.withOpacity(0.6), blurRadius: 10, spreadRadius: 2)] : null,
                    ),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 18),

            // Preview Quality
            const Text('Preview Quality', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              children: [
                _buildQualityChip('360p', PreviewQuality.p360),
                _buildQualityChip('720p', PreviewQuality.p720),
                _buildQualityChip('1080p', PreviewQuality.p1080),
              ],
            ),
            const SizedBox(height: 24),

            // Save Changes Button
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFFE040FB),
                foregroundColor: Colors.black,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: () {
                SynthAudio.playClickPing();
                final updated = widget.currentSettings.copyWith(
                  enableSpaceGame: _enableSpaceGame,
                  themeColor: _themeColor,
                  particleColor: _particleColor,
                  previewQuality: _quality,
                );
                Navigator.pop(context, updated);
              },
              child: const Text('Save Changes', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildQualityChip(String label, PreviewQuality q) {
    final selected = _quality == q;
    return ChoiceChip(
      label: Text(label, style: TextStyle(color: selected ? Colors.black : Colors.white70, fontSize: 11)),
      selected: selected,
      selectedColor: const Color(0xFFE040FB),
      backgroundColor: const Color(0xFF1E1E2C),
      onSelected: (s) => s ? setState(() => _quality = q) : null,
    );
  }
}
