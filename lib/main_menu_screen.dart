// =============================================================================
// Zakura - Master Main Menu Screen
// Dual Glowing Modules (Blur & Generator), UI-Hide Dogfight Toggle & Settings
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import '../audio/synth_audio.dart';
import '../models/models.dart';
import 'space_game_painter.dart';
import 'motion_blur_screen.dart';
import 'null_generator_screen.dart';
import 'settings_dialog.dart';

class MainMenuScreen extends StatefulWidget {
  const MainMenuScreen({super.key});

  @override
  State<MainMenuScreen> createState() => _MainMenuScreenState();
}

class _MainMenuScreenState extends State<MainMenuScreen> with SingleTickerProviderStateMixin {
  late final SpaceGameController _gameController;
  late final Ticker _ticker;
  Duration _lastElapsed = Duration.zero;

  ZakuraSettings _settings = const ZakuraSettings();

  bool _isBlurHovered = false;
  bool _isGenHovered = false;

  @override
  void initState() {
    super.initState();
    _gameController = SpaceGameController();
    _ticker = createTicker((elapsed) {
      if (_lastElapsed != Duration.zero) {
        final double dt = (elapsed - _lastElapsed).inMicroseconds / 1000000.0;
        _gameController.update(dt.clamp(0.001, 0.05));
      }
      _lastElapsed = elapsed;
    })..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _gameController.dispose();
    super.dispose();
  }

  void _openSettings() async {
    SynthAudio.playClickPing();
    final updated = await showDialog<ZakuraSettings>(
      context: context,
      builder: (ctx) => SettingsDialog(currentSettings: _settings),
    );

    if (updated != null) {
      setState(() {
        _settings = updated;
        _gameController.isGameEnabled = updated.enableSpaceGame;
        _gameController.customParticleColor = updated.particleColor;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // 1. Full-screen Space Dogfight / Particle Canvas
          GestureDetector(
            onPanUpdate: (details) {
              _gameController.movePlayer(details.localPosition.dx, details.localPosition.dy);
            },
            child: SizedBox.expand(
              child: CustomPaint(
                painter: SpaceGamePainter(controller: _gameController),
              ),
            ),
          ),

          // 2. Main Menu Interactive UI Overlay
          AnimatedOpacity(
            opacity: _gameController.isDogfightActive ? 0.0 : 1.0,
            duration: const Duration(milliseconds: 320),
            child: IgnorePointer(
              ignoring: _gameController.isDogfightActive,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 20.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // Header: Title & Settings Button
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'ZAKURA',
                                style: TextStyle(
                                  color: Colors.white,
                                  fontSize: 28,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: 3.5,
                                  shadows: [
                                    Shadow(
                                      color: _settings.themeColor.withOpacity(0.85),
                                      blurRadius: 16.0,
                                    ),
                                  ],
                                ),
                              ),
                              const Text(
                                'VULKAN 3D & OPTICAL SMEAR',
                                style: TextStyle(
                                  color: Colors.white54,
                                  fontSize: 10,
                                  letterSpacing: 2.0,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ],
                          ),
                          IconButton(
                            icon: const Icon(Icons.settings_outlined, color: Colors.white70, size: 26),
                            onPressed: _openSettings,
                          ),
                        ],
                      ),

                      const Spacer(),

                      // =======================================================
                      // BUTTON 1: PINK ZAKURA BLUR (MOTION SMEAR)
                      // =======================================================
                      GestureDetector(
                        onTapDown: (_) {
                          setState(() => _isBlurHovered = true);
                          SynthAudio.playClickPing();
                        },
                        onTapUp: (_) {
                          setState(() => _isBlurHovered = false);
                          Navigator.push(
                            context,
                            MaterialPageRoute(builder: (_) => const MotionBlurScreen()),
                          );
                        },
                        onTapCancel: () => setState(() => _isBlurHovered = false),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 180),
                          height: 110,
                          transform: Matrix4.identity()..scale(_isBlurHovered ? 1.03 : 1.0),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(20),
                            gradient: const LinearGradient(
                              colors: [Color(0xFFFF4081), Color(0xFFC51162)],
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                            ),
                            boxShadow: [
                              BoxShadow(
                                color: const Color(0xFFFF4081).withOpacity(_isBlurHovered ? 0.65 : 0.35),
                                blurRadius: _isBlurHovered ? 28.0 : 14.0,
                                spreadRadius: _isBlurHovered ? 4.0 : 0.0,
                                offset: const Offset(0, 6),
                              ),
                            ],
                          ),
                          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 18.0),
                          child: const Row(
                            children: [
                              Icon(Icons.blur_on_rounded, color: Colors.white, size: 48),
                              SizedBox(width: 20),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Text(
                                      'ZAKURA BLUR',
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontSize: 20,
                                        fontWeight: FontWeight.w900,
                                        letterSpacing: 1.5,
                                      ),
                                    ),
                                    SizedBox(height: 4),
                                    Text(
                                      'Adevob Vector Contour Motion Smear',
                                      style: TextStyle(
                                        color: Colors.white70,
                                        fontSize: 12,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              Icon(Icons.arrow_forward_ios_rounded, color: Colors.white70, size: 20),
                            ],
                          ),
                        ),
                      ),

                      const SizedBox(height: 20),

                      // =======================================================
                      // BUTTON 2: PURPLE ZAKURA GENERATOR (3D NULL & CUBES)
                      // =======================================================
                      GestureDetector(
                        onTapDown: (_) {
                          setState(() => _isGenHovered = true);
                          SynthAudio.playClickPing();
                        },
                        onTapUp: (_) {
                          setState(() => _isGenHovered = false);
                          Navigator.push(
                            context,
                            MaterialPageRoute(builder: (_) => const NullGeneratorScreen()),
                          );
                        },
                        onTapCancel: () => setState(() => _isGenHovered = false),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 180),
                          height: 110,
                          transform: Matrix4.identity()..scale(_isGenHovered ? 1.03 : 1.0),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(20),
                            gradient: const LinearGradient(
                              colors: [Color(0xFFE040FB), Color(0xFF7B1FA2)],
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                            ),
                            boxShadow: [
                              BoxShadow(
                                color: const Color(0xFFE040FB).withOpacity(_isGenHovered ? 0.65 : 0.35),
                                blurRadius: _isGenHovered ? 28.0 : 14.0,
                                spreadRadius: _isGenHovered ? 4.0 : 0.0,
                                offset: const Offset(0, 6),
                              ),
                            ],
                          ),
                          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 18.0),
                          child: const Row(
                            children: [
                              Icon(Icons.view_in_ar_rounded, color: Colors.white, size: 48),
                              SizedBox(width: 20),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Text(
                                      'ZAKURA GENERATOR',
                                      style: TextStyle(
                                        color: Colors.white,
                                        fontSize: 20,
                                        fontWeight: FontWeight.w900,
                                        letterSpacing: 1.5,
                                      ),
                                    ),
                                    SizedBox(height: 4),
                                    Text(
                                      '3D Nulls, Origami Boxes & Sliced Flips',
                                      style: TextStyle(
                                        color: Colors.white70,
                                        fontSize: 12,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              Icon(Icons.arrow_forward_ios_rounded, color: Colors.white70, size: 20),
                            ],
                          ),
                        ),
                      ),

                      const Spacer(),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // 3. Bottom Right Floating Button: Hide UI / Play Space Dogfight
          Positioned(
            right: 20,
            bottom: 24,
            child: FloatingActionButton.extended(
              backgroundColor: _gameController.isDogfightActive
                  ? const Color(0xFFFF1744)
                  : const Color(0xFF1E1E2C),
              foregroundColor: Colors.white,
              elevation: 8,
              onPressed: () {
                SynthAudio.playClickPing();
                setState(() {
                  _gameController.isDogfightActive = !_gameController.isDogfightActive;
                });
              },
              icon: Icon(
                _gameController.isDogfightActive ? Icons.close : Icons.sports_esports_outlined,
                size: 20,
              ),
              label: Text(
                _gameController.isDogfightActive ? 'Exit Dogfight' : 'Hide UI / Play',
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
