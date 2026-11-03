// =============================================================================
// Zakura - Master Application Models
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'package:flutter/material.dart';

enum GeneratorMode {
  null3D,           // Mode 1: 3D Null (starts full frame, zooms out into orbit)
  flip2D,           // Mode 2: 2D Flip Layer (green screen intro, dual row media)
  flip3D,           // Mode 3: 3D Flip Layer (green screen intro, dynamic 3D camera)
  flip3DPlusNull,   // Mode 4: 3D Flip Layer + Null combo
  flip2DPlusNull3D, // Mode 5: 2D Flip Layer + 3D Null combo
}

enum BeatTiming {
  rapid300ms, // 0.30 seconds per beat
  pace1000ms, // 1.00 second per beat
}

enum EdgeEffectType {
  none,
  edgeHaloAura,      // 16-tap spiral outward spill
  sapphireEdgeGlow,  // Crisp lit contour rays
  halftoneDots,      // Comic halftone print boundary
  boundaryParticles, // Embers outlining edges
}

enum PreviewQuality {
  p360,
  p720,
  p1080,
  pNative,
}

class ZakuraSettings {
  final bool enableSpaceGame;
  final Color themeColor;
  final Color particleColor;
  final PreviewQuality previewQuality;

  const ZakuraSettings({
    this.enableSpaceGame = true,
    this.themeColor = const Color(0xFFE040FB), // Zakura Purple
    this.particleColor = const Color(0xFFFF4081), // Pink Accent
    this.previewQuality = PreviewQuality.p720,
  });

  ZakuraSettings copyWith({
    bool? enableSpaceGame,
    Color? themeColor,
    Color? particleColor,
    PreviewQuality? previewQuality,
  }) {
    return ZakuraSettings(
      enableSpaceGame: enableSpaceGame ?? this.enableSpaceGame,
      themeColor: themeColor ?? this.themeColor,
      particleColor: particleColor ?? this.particleColor,
      previewQuality: previewQuality ?? this.previewQuality,
    );
  }
}

class MotionBlurConfig {
  final double blurIntensity;  // 0.0 to 2.0
  final double shutterAngle;   // 180.0 to 720.0
  final int smearSamples;      // 6 to 16 samples
  final bool preserveLineArt;  // Pull anime contours harder
  final double edgeSensitivity;

  const MotionBlurConfig({
    this.blurIntensity = 1.0,
    this.shutterAngle = 360.0,
    this.smearSamples = 8,
    this.preserveLineArt = true,
    this.edgeSensitivity = 0.85,
  });

  MotionBlurConfig copyWith({
    double? blurIntensity,
    double? shutterAngle,
    int? smearSamples,
    bool? preserveLineArt,
    double? edgeSensitivity,
  }) {
    return MotionBlurConfig(
      blurIntensity: blurIntensity ?? this.blurIntensity,
      shutterAngle: shutterAngle ?? this.shutterAngle,
      smearSamples: smearSamples ?? this.smearSamples,
      preserveLineArt: preserveLineArt ?? this.preserveLineArt,
      edgeSensitivity: edgeSensitivity ?? this.edgeSensitivity,
    );
  }
}

class BeatMediaSlot {
  final int index;
  final String? mediaPath;
  final bool isVideo;
  final bool isIntro;

  const BeatMediaSlot({
    required this.index,
    this.mediaPath,
    this.isVideo = false,
    this.isIntro = false,
  });

  BeatMediaSlot copyWith({
    String? mediaPath,
    bool? isVideo,
    bool? isIntro,
  }) {
    return BeatMediaSlot(
      index: index,
      mediaPath: mediaPath ?? this.mediaPath,
      isVideo: isVideo ?? this.isVideo,
      isIntro: isIntro ?? this.isIntro,
    );
  }
}

class GeneratorConfig {
  final GeneratorMode mode;
  final BeatTiming timing;
  final int totalBeats; // 5 (1 intro + 4 beats) or 9 (1 intro + 8 beats)
  final int seed;
  final String speedCurveId;
  final EdgeEffectType edgeEffect;
  final double edgeGlowStrength;
  final Color backgroundColor;
  final bool enableParticles;
  final Color particleColor;
  final int particleCount;
  final double particleSize;

  const GeneratorConfig({
    this.mode = GeneratorMode.null3D,
    this.timing = BeatTiming.rapid300ms,
    this.totalBeats = 5,
    this.seed = 1337,
    this.speedCurveId = 'akaza_snap',
    this.edgeEffect = EdgeEffectType.edgeHaloAura,
    this.edgeGlowStrength = 1.2,
    this.backgroundColor = Colors.black,
    this.enableParticles = true,
    this.particleColor = const Color(0xFF00E5FF),
    this.particleCount = 48,
    this.particleSize = 3.5,
  });

  GeneratorConfig copyWith({
    GeneratorMode? mode,
    BeatTiming? timing,
    int? totalBeats,
    int? seed,
    String? speedCurveId,
    EdgeEffectType? edgeEffect,
    double? edgeGlowStrength,
    Color? backgroundColor,
    bool? enableParticles,
    Color? particleColor,
    int? particleCount,
    double? particleSize,
  }) {
    return GeneratorConfig(
      mode: mode ?? this.mode,
      timing: timing ?? this.timing,
      totalBeats: totalBeats ?? this.totalBeats,
      seed: seed ?? this.seed,
      speedCurveId: speedCurveId ?? this.speedCurveId,
      edgeEffect: edgeEffect ?? this.edgeEffect,
      edgeGlowStrength: edgeGlowStrength ?? this.edgeGlowStrength,
      backgroundColor: backgroundColor ?? this.backgroundColor,
      enableParticles: enableParticles ?? this.enableParticles,
      particleColor: particleColor ?? this.particleColor,
      particleCount: particleCount ?? this.particleCount,
      particleSize: particleSize ?? this.particleSize,
    );
  }
}

class ProjectData {
  final String id;
  final String title;
  final bool isImage;
  final String? videoPath;
  final double durationMs;
  final double fps;
  final int width;
  final int height;

  const ProjectData({
    required this.id,
    required this.title,
    this.isImage = false,
    this.videoPath,
    this.durationMs = 5000.0,
    this.fps = 30.0,
    this.width = 1080,
    this.height = 1920,
  });
}
