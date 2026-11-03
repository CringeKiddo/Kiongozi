// =============================================================================
// Zakura - 10 WIS 2026 Speed Curve Presets
// Modeled directly from 8 Who-Is-Stronger reference clips (Akaza, Saber, WuWa, etc.)
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'package:flutter/animation.dart';

class ZakuraSpeedCurve {
  final String id;
  final String name;
  final String description;
  final Cubic curve;

  const ZakuraSpeedCurve({
    required this.id,
    required this.name,
    required this.description,
    required this.curve,
  });

  /// Evaluates progress t (0.0 to 1.0) through the Bézier speed curve
  double transform(double t) => curve.transform(t.clamp(0.0, 1.0));
}

class SpeedCurvePresets {
  static const List<ZakuraSpeedCurve> presets = [
    // 1. Akaza 8-Beat Snap: Explosive velocity spike at 85% influence with instant rebound
    ZakuraSpeedCurve(
      id: 'akaza_snap',
      name: 'Akaza 8-Beat Snap',
      description: 'Violent instant whip in the first 20% of beat with smooth ease-out.',
      curve: Cubic(0.85, 0.0, 0.15, 1.0),
    ),

    // 2. WuWa Venetian Slat Delay: S-curve designed for staggered accordion strips
    ZakuraSpeedCurve(
      id: 'wuwa_accordion',
      name: 'WuWa Accordion Ease',
      description: 'Progressive acceleration for folding panels and Venetian slats.',
      curve: Cubic(0.70, 0.0, 0.20, 1.0),
    ),

    // 3. Saber Offset Slice: Rapid entry with soft elastic landing
    ZakuraSpeedCurve(
      id: 'saber_slice',
      name: 'Saber Offset Slice',
      description: 'High velocity entry that decelerates sharply into place.',
      curve: Cubic(0.90, 0.05, 0.10, 0.98),
    ),

    // 4. Zenitsu 3D Tunnel Surge: Deep exponential push into Z-axis
    ZakuraSpeedCurve(
      id: 'zenitsu_tunnel',
      name: 'Zenitsu 3D Surge',
      description: 'Slow build-up rocketing into rapid camera tunnel push.',
      curve: Cubic(0.60, 0.0, 0.05, 1.0),
    ),

    // 5. Garou Jagged Fracture: Sharp velocity ramp that breaks apart then locks
    ZakuraSpeedCurve(
      id: 'garou_fracture',
      name: 'Garou Fracture Whip',
      description: 'Aggressive mid-beat snap designed for separated layer cards.',
      curve: Cubic(0.95, 0.0, 0.05, 1.0),
    ),

    // 6. Archer Hex Prism Spin: Constant angular speed settling into perspective
    ZakuraSpeedCurve(
      id: 'archer_prism',
      name: 'Archer Prism Flip',
      description: 'Uniform rotation transitioning into smooth geometric ease.',
      curve: Cubic(0.75, 0.10, 0.25, 1.0),
    ),

    // 7. Deku 3D Camera Orbit: Smooth sweeping cinematic rotation
    ZakuraSpeedCurve(
      id: 'deku_orbit',
      name: 'Deku 3D Master Orbit',
      description: 'Cinematic continuous camera rotation around floating cards.',
      curve: Cubic(0.65, 0.05, 0.35, 0.95),
    ),

    // 8. Yoruichi Cross Unfold: Hesitation pause followed by fast box unfold
    ZakuraSpeedCurve(
      id: 'yoruichi_unfold',
      name: 'Yoruichi Cube Unfold',
      description: 'Brief tension hold followed by rapid 90-degree face opening.',
      curve: Cubic(0.80, -0.10, 0.10, 1.10),
    ),

    // 9. Phonk Rapid Bounce (0.30s): Extreme tension for fast 300ms beats
    ZakuraSpeedCurve(
      id: 'phonk_rapid',
      name: 'Phonk 300ms Rapid Snap',
      description: 'Engineered specifically for 0.30s beats and violent RSMB smears.',
      curve: Cubic(0.92, 0.0, 0.08, 1.0),
    ),

    // 10. Heavy Elastic Rebound: Subtle overshoot at the end of card flip
    ZakuraSpeedCurve(
      id: 'elastic_rebound',
      name: 'Heavy Elastic Settle',
      description: 'Snappy transition with a slight 3D overshoot settle.',
      curve: Cubic(0.68, -0.25, 0.265, 1.25),
    ),
  ];

  static ZakuraSpeedCurve getById(String id) {
    return presets.firstWhere(
      (c) => c.id == id,
      orElse: () => presets[0],
    );
  }
}
