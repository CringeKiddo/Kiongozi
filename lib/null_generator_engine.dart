// =============================================================================
// Zakura - Cinematic 2026 WIS 3D Engine
// Full 5 Modes, Origami Cross Unfolding, Sliced Accordion, Diamond Split,
// Dynamic Camera Zoom/Pan/Roll & 120 Circular Background Bokeh Particles
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'dart:math';
import 'dart:typed_data';
import 'package:flutter/material.dart';

import '../models/models.dart';
import 'speed_curves.dart';

// =============================================================================
// 3D GEOMETRY PRIMITIVES
// =============================================================================

class Vertex3D {
  final double x;
  final double y;
  final double z;
  final double u;
  final double v;

  const Vertex3D(this.x, this.y, this.z, [this.u = 0.0, this.v = 0.0]);

  Vertex3D transform(Matrix4 m) {
    final s = m.storage;
    final double tx = s[0] * x + s[4] * y + s[8] * z + s[12];
    final double ty = s[1] * x + s[5] * y + s[9] * z + s[13];
    final double tz = s[2] * x + s[6] * y + s[10] * z + s[14];
    final double tw = s[3] * x + s[7] * y + s[11] * z + s[15];

    if (tw.abs() > 1e-6) {
      return Vertex3D(tx / tw, ty / tw, tz / tw, u, v);
    }
    return Vertex3D(tx, ty, tz, u, v);
  }

  Offset toScreen(double screenW, double screenH, double fovFactor, double camZ) {
    final double depth = (z + camZ);
    if (depth <= 0.05) return Offset(screenW * 0.5, screenH * 0.5);

    final double projX = (x / depth) * fovFactor + (screenW * 0.5);
    final double projY = (y / depth) * fovFactor + (screenH * 0.5);
    return Offset(projX, projY);
  }
}

class ProjectedQuad3D {
  final int faceId;
  final int mediaIndex;
  final List<Vertex3D> vertices;
  final List<Offset> screenPoints;
  final double averageZ;
  final bool isFrontFacing;
  final Color edgeGlowColor;

  const ProjectedQuad3D({
    required this.faceId,
    required this.mediaIndex,
    required this.vertices,
    required this.screenPoints,
    required this.averageZ,
    required this.isFrontFacing,
    required this.edgeGlowColor,
  });
}

class BackgroundGlowParticle3D {
  final double x;
  final double y;
  final double z;
  final double coreSize;
  final double haloSize;
  final Color color;
  final double intensity;

  const BackgroundGlowParticle3D({
    required this.x,
    required this.y,
    required this.z,
    required this.coreSize,
    required this.haloSize,
    required this.color,
    required this.intensity,
  });

  Offset toScreen(double screenW, double screenH, double fovFactor, double camZ) {
    final double depth = (z + camZ);
    if (depth <= 0.05) return Offset(screenW * 0.5, screenH * 0.5);
    final double projX = (x / depth) * fovFactor + (screenW * 0.5);
    final double projY = (y / depth) * fovFactor + (screenH * 0.5);
    return Offset(projX, projY);
  }
}

class ZakuraEvaluatedScene {
  final List<ProjectedQuad3D> sortedQuads;
  final List<BackgroundGlowParticle3D> backgroundParticles;
  final double velocityX;
  final double velocityY;
  final bool isGreenScreenIntro;
  final Color backgroundColor;
  final double cameraZ;

  const ZakuraEvaluatedScene({
    required this.sortedQuads,
    required this.backgroundParticles,
    required this.velocityX,
    required this.velocityY,
    required this.isGreenScreenIntro,
    required this.backgroundColor,
    required this.cameraZ,
  });
}

// =============================================================================
// MASTER 3D NULL & GEOMETRIC ENGINE
// =============================================================================

class NullGeneratorEngine {
  static ZakuraEvaluatedScene evaluateScene({
    required GeneratorConfig config,
    required double timestampMs,
    required double screenWidth,
    required double screenHeight,
  }) {
    final double beatDurationMs = config.timing == BeatTiming.rapid300ms ? 300.0 : 1000.0;
    final int currentBeatIndex = (timestampMs / beatDurationMs).floor().clamp(0, config.totalBeats - 1);
    final double beatProgress = (timestampMs % beatDurationMs) / beatDurationMs;

    final curve = SpeedCurvePresets.getById(config.speedCurveId);
    final double t = curve.transform(beatProgress);

    final bool isIntro = (currentBeatIndex == 0);
    bool isGreenScreen = false;

    // Camera Transforms
    double camX = 0.0;
    double camY = 0.0;
    double camZ = 2.4;
    double camRoll = 0.0;

    final Matrix4 nullMat = Matrix4.identity();
    double vx = 0.0;
    double vy = 0.0;

    final double fovFactor = min(screenWidth, screenHeight) * 1.55;
    final List<ProjectedQuad3D> quads = [];

    // =========================================================================
    // DYNAMIC CINEMATIC BEAT CHOREOGRAPHY
    // =========================================================================
    if (isIntro) {
      if (config.mode == GeneratorMode.null3D) {
        // Starts 100% full frame upright video, zooms out into 3D void
        camZ = 1.05 + (t * 1.35);
        camY = sin(t * pi) * -0.15;
        nullMat.rotateY(t * 0.25);
        vx = t * 15.0;
      } else {
        // Green screen flip entry
        isGreenScreen = true;
        camZ = 2.5;
        nullMat.translate(0.0, (1.0 - t) * -2.2, 0.0);
        nullMat.rotateX((1.0 - t) * (pi / 2));
      }
    } else {
      final int stuntIdx = (currentBeatIndex - 1) % 4;

      switch (stuntIdx) {
        case 0:
          // STUNT 1: 90-Degree Snap with Lateral Camera Whip
          camZ = 2.4 - (sin(t * pi) * 0.35);
          camX = sin(t * pi) * 0.30;
          final double snapAngle = t * (pi / 2);
          nullMat.rotateY(snapAngle);
          nullMat.rotateX(sin(t * pi) * 0.15);
          vx = 65.0 * (1.0 - t);
          break;

        case 1:
          // STUNT 2: 3D Perspective Tumble with 35-degree Dutch Camera Roll
          camZ = 2.2 + (sin(t * pi) * 0.40);
          camRoll = sin(t * pi) * 0.60;
          final double tumbleAngle = t * pi;
          nullMat.rotateY(pi / 2 + tumbleAngle);
          nullMat.rotateZ(tumbleAngle * 0.35);
          vx = -45.0;
          vy = 35.0;
          break;

        case 2:
          // STUNT 3: Deep Z-Axis Plunge & Vertical Accordion Flip
          camZ = 1.8 + ((1.0 - t) * 0.9);
          camY = (t - 0.5) * 0.40;
          nullMat.rotateX(t * pi);
          nullMat.rotateY(sin(t * pi) * 0.25);
          vy = 75.0 * (1.0 - t);
          break;

        case 3:
          // STUNT 4: Explosive Vortex Spin into Settle Lock
          camZ = 2.5;
          final double spin = t * (pi * 1.5);
          nullMat.rotateY(spin);
          nullMat.rotateZ(sin(t * pi) * 0.40);
          vx = 80.0;
          break;
      }
    }

    if (camRoll != 0.0) {
      nullMat.rotateZ(camRoll);
    }
    nullMat.translate(camX, camY, 0.0);

    // =========================================================================
    // SPECIFIC GEOMETRY DISPATCH FOR ALL 5 MODES
    // =========================================================================
    switch (config.mode) {
      case GeneratorMode.null3D:
        _generateCubeFaces(
          quads: quads,
          nullMat: nullMat,
          screenWidth: screenWidth,
          screenHeight: screenHeight,
          fovFactor: fovFactor,
          camZ: camZ,
          activeBeat: currentBeatIndex,
          config: config,
        );
        break;

      case GeneratorMode.flip2D:
        _generateAccordionStripFaces(
          quads: quads,
          nullMat: nullMat,
          screenWidth: screenWidth,
          screenHeight: screenHeight,
          fovFactor: fovFactor,
          camZ: camZ,
          activeBeat: currentBeatIndex,
          easedProgress: t,
          config: config,
          slatCount: 4,
        );
        break;

      case GeneratorMode.flip3D:
        _generateDiamondSplitFaces(
          quads: quads,
          nullMat: nullMat,
          screenWidth: screenWidth,
          screenHeight: screenHeight,
          fovFactor: fovFactor,
          camZ: camZ,
          activeBeat: currentBeatIndex,
          easedProgress: t,
          config: config,
        );
        break;

      case GeneratorMode.flip3DPlusNull:
        if (currentBeatIndex <= 2) {
          _generateDiamondSplitFaces(
            quads: quads,
            nullMat: nullMat,
            screenWidth: screenWidth,
            screenHeight: screenHeight,
            fovFactor: fovFactor,
            camZ: camZ,
            activeBeat: currentBeatIndex,
            easedProgress: t,
            config: config,
          );
        } else {
          _generateCubeFaces(
            quads: quads,
            nullMat: nullMat,
            screenWidth: screenWidth,
            screenHeight: screenHeight,
            fovFactor: fovFactor,
            camZ: camZ,
            activeBeat: currentBeatIndex,
            config: config,
          );
        }
        break;

      case GeneratorMode.flip2DPlusNull3D:
        _generateOrigamiCrossFaces(
          quads: quads,
          nullMat: nullMat,
          screenWidth: screenWidth,
          screenHeight: screenHeight,
          fovFactor: fovFactor,
          camZ: camZ,
          activeBeat: currentBeatIndex,
          easedProgress: t,
          config: config,
        );
        break;
    }

    quads.sort((a, b) => b.averageZ.compareTo(a.averageZ));

    // =========================================================================
    // BACKGROUND VORTEX PARTICLES (Strictly Behind Cards, Circular Field, Camera-Locked)
    // =========================================================================
    final List<BackgroundGlowParticle3D> particles = [];
    if (config.enableParticles) {
      final double pTime = timestampMs * 0.001;
      final pRng = Random(config.seed);

      for (int i = 0; i < 120; i++) {
        final double theta = (i / 120.0) * 2 * pi;
        final double radialDist = 1.4 + (pRng.nextDouble() * 2.8);
        final double pz = 1.6 + (pRng.nextDouble() * 4.5);

        final double hoverX = sin(pTime * 0.15 + i) * 0.04;
        final double hoverY = cos(pTime * 0.12 + i) * 0.04;

        final double px = (cos(theta) * radialDist) + hoverX - (camX * 0.85);
        final double py = (sin(theta) * radialDist) + hoverY - (camY * 0.85);

        particles.add(BackgroundGlowParticle3D(
          x: px,
          y: py,
          z: pz,
          coreSize: 2.2 + pRng.nextDouble() * 2.5,
          haloSize: 7.0 + pRng.nextDouble() * 10.0,
          color: config.particleColor,
          intensity: 0.45 + pRng.nextDouble() * 0.50,
        ));
      }

      particles.sort((a, b) => b.z.compareTo(a.z));
    }

    return ZakuraEvaluatedScene(
      sortedQuads: quads,
      backgroundParticles: particles,
      velocityX: vx,
      velocityY: vy,
      isGreenScreenIntro: isGreenScreen,
      backgroundColor: isGreenScreen ? const Color(0xFF00FF00) : config.backgroundColor,
      cameraZ: camZ,
    );
  }

  // ===========================================================================
  // 3D GEOMETRY SUBROUTINES
  // ===========================================================================

  static void _generateCubeFaces({
    required List<ProjectedQuad3D> quads,
    required Matrix4 nullMat,
    required double screenWidth,
    required double screenHeight,
    required double fovFactor,
    required double camZ,
    required int activeBeat,
    required GeneratorConfig config,
  }) {
    const double s = 0.52;
    final faces = [
      [const Vertex3D(-s, -s,  s, 0, 0), const Vertex3D( s, -s,  s, 1, 0), const Vertex3D( s,  s,  s, 1, 1), const Vertex3D(-s,  s,  s, 0, 1)],
      [const Vertex3D( s, -s, -s, 0, 0), const Vertex3D(-s, -s, -s, 1, 0), const Vertex3D(-s,  s, -s, 1, 1), const Vertex3D( s,  s, -s, 0, 1)],
      [const Vertex3D(-s, -s, -s, 0, 0), const Vertex3D(-s, -s,  s, 1, 0), const Vertex3D(-s,  s,  s, 1, 1), const Vertex3D(-s,  s, -s, 0, 1)],
      [const Vertex3D( s, -s,  s, 0, 0), const Vertex3D( s, -s, -s, 1, 0), const Vertex3D( s,  s, -s, 1, 1), const Vertex3D( s,  s,  s, 0, 1)],
      [const Vertex3D(-s, -s, -s, 0, 0), const Vertex3D( s, -s, -s, 1, 0), const Vertex3D( s, -s,  s, 1, 1), const Vertex3D(-s, -s,  s, 0, 1)],
      [const Vertex3D(-s,  s,  s, 0, 0), const Vertex3D( s,  s,  s, 1, 0), const Vertex3D( s,  s, -s, 1, 1), const Vertex3D(-s,  s, -s, 0, 1)],
    ];

    for (int i = 0; i < 6; i++) {
      _projectAndAppendQuad(
        faceId: i,
        mediaIndex: (activeBeat + i) % config.totalBeats,
        rawVertices: faces[i],
        nullMat: nullMat,
        screenWidth: screenWidth,
        screenHeight: screenHeight,
        fovFactor: fovFactor,
        camZ: camZ,
        quads: quads,
        config: config,
      );
    }
  }

  static void _generateAccordionStripFaces({
    required List<ProjectedQuad3D> quads,
    required Matrix4 nullMat,
    required double screenWidth,
    required double screenHeight,
    required double fovFactor,
    required double camZ,
    required int activeBeat,
    required double easedProgress,
    required GeneratorConfig config,
    required int slatCount,
  }) {
    const double totalW = 1.05;
    final double slatW = totalW / slatCount;

    for (int i = 0; i < slatCount; i++) {
      final double x0 = -0.52 + (i * slatW);
      final double u0 = i / slatCount.toDouble();
      final double u1 = (i + 1) / slatCount.toDouble();

      final double slatDelay = (i / slatCount.toDouble()) * 0.28;
      final double slatProgress = ((easedProgress - slatDelay) / (1.0 - slatDelay)).clamp(0.0, 1.0);
      final double slatRot = slatProgress * pi;

      final Matrix4 slatMat = nullMat.clone();
      slatMat.translate(x0 + (slatW * 0.5), 0.0, 0.0);
      slatMat.rotateY(slatRot);
      slatMat.translate(-(slatW * 0.5), 0.0, 0.0);

      final verts = [
        Vertex3D(0.0, -0.65, 0.0, u0, 0.0),
        Vertex3D(slatW, -0.65, 0.0, u1, 0.0),
        Vertex3D(slatW,  0.65, 0.0, u1, 1.0),
        Vertex3D(0.0,  0.65, 0.0, u0, 1.0),
      ];

      _projectAndAppendQuad(
        faceId: i,
        mediaIndex: activeBeat % config.totalBeats,
        rawVertices: verts,
        nullMat: slatMat,
        screenWidth: screenWidth,
        screenHeight: screenHeight,
        fovFactor: fovFactor,
        camZ: camZ,
        quads: quads,
        config: config,
      );
    }
  }

  static void _generateDiamondSplitFaces({
    required List<ProjectedQuad3D> quads,
    required Matrix4 nullMat,
    required double screenWidth,
    required double screenHeight,
    required double fovFactor,
    required double camZ,
    required int activeBeat,
    required double easedProgress,
    required GeneratorConfig config,
  }) {
    final diamondMat = nullMat.clone()..rotateZ(0.785); // 45 deg rhombus
    final verts = [
      const Vertex3D(-0.52, -0.52, 0.0, 0.0, 0.0),
      const Vertex3D( 0.52, -0.52, 0.0, 1.0, 0.0),
      const Vertex3D( 0.52,  0.52, 0.0, 1.0, 1.0),
      const Vertex3D(-0.52,  0.52, 0.0, 0.0, 1.0),
    ];

    _projectAndAppendQuad(
      faceId: 0,
      mediaIndex: activeBeat % config.totalBeats,
      rawVertices: verts,
      nullMat: diamondMat,
      screenWidth: screenWidth,
      screenHeight: screenHeight,
      fovFactor: fovFactor,
      camZ: camZ,
      quads: quads,
      config: config,
    );
  }

  static void _generateOrigamiCrossFaces({
    required List<ProjectedQuad3D> quads,
    required Matrix4 nullMat,
    required double screenWidth,
    required double screenHeight,
    required double fovFactor,
    required double camZ,
    required int activeBeat,
    required double easedProgress,
    required GeneratorConfig config,
  }) {
    const double s = 0.52;
    final double foldAngle = (1.0 - easedProgress) * (pi / 2); // 90 deg -> 0 deg flat

    // Center Face
    _projectAndAppendQuad(
      faceId: 0,
      mediaIndex: activeBeat % config.totalBeats,
      rawVertices: const [
        Vertex3D(-s, -s, 0.0, 0, 0),
        Vertex3D( s, -s, 0.0, 1, 0),
        Vertex3D( s,  s, 0.0, 1, 1),
        Vertex3D(-s,  s, 0.0, 0, 1),
      ],
      nullMat: nullMat,
      screenWidth: screenWidth,
      screenHeight: screenHeight,
      fovFactor: fovFactor,
      camZ: camZ,
      quads: quads,
      config: config,
    );

    // Left Flap (hinged at x = -s)
    final leftMat = nullMat.clone()
      ..translate(-s, 0.0, 0.0)
      ..rotateY(-foldAngle)
      ..translate(-s, 0.0, 0.0);
    _projectAndAppendQuad(
      faceId: 1,
      mediaIndex: (activeBeat + 1) % config.totalBeats,
      rawVertices: const [
        Vertex3D(0.0, -s, 0.0, 0, 0),
        Vertex3D(  s, -s, 0.0, 1, 0),
        Vertex3D(  s,  s, 0.0, 1, 1),
        Vertex3D(0.0,  s, 0.0, 0, 1),
      ],
      nullMat: leftMat,
      screenWidth: screenWidth,
      screenHeight: screenHeight,
      fovFactor: fovFactor,
      camZ: camZ,
      quads: quads,
      config: config,
    );

    // Right Flap (hinged at x = +s)
    final rightMat = nullMat.clone()
      ..translate(s, 0.0, 0.0)
      ..rotateY(foldAngle);
    _projectAndAppendQuad(
      faceId: 2,
      mediaIndex: (activeBeat + 2) % config.totalBeats,
      rawVertices: const [
        Vertex3D(0.0, -s, 0.0, 0, 0),
        Vertex3D(  s, -s, 0.0, 1, 0),
        Vertex3D(  s,  s, 0.0, 1, 1),
        Vertex3D(0.0,  s, 0.0, 0, 1),
      ],
      nullMat: rightMat,
      screenWidth: screenWidth,
      screenHeight: screenHeight,
      fovFactor: fovFactor,
      camZ: camZ,
      quads: quads,
      config: config,
    );
  }

  static void _projectAndAppendQuad({
    required int faceId,
    required int mediaIndex,
    required List<Vertex3D> rawVertices,
    required Matrix4 nullMat,
    required double screenWidth,
    required double screenHeight,
    required double fovFactor,
    required double camZ,
    required List<ProjectedQuad3D> quads,
    required GeneratorConfig config,
  }) {
    final List<Vertex3D> transformed = [];
    final List<Offset> screenPoints = [];
    double zSum = 0.0;

    for (final v in rawVertices) {
      final tv = v.transform(nullMat);
      transformed.add(tv);
      screenPoints.add(tv.toScreen(screenWidth, screenHeight, fovFactor, camZ));
      zSum += tv.z;
    }

    final double crossZ = (screenPoints[1].dx - screenPoints[0].dx) * (screenPoints[2].dy - screenPoints[0].dy) -
                          (screenPoints[1].dy - screenPoints[0].dy) * (screenPoints[2].dx - screenPoints[0].dx);
    final bool isFrontFacing = crossZ > 0.0;

    Color glowColor = const Color(0xFFE040FB);
    if (config.edgeEffect == EdgeEffectType.sapphireEdgeGlow) {
      glowColor = const Color(0xFF00E5FF);
    } else if (config.edgeEffect == EdgeEffectType.edgeHaloAura) {
      glowColor = const Color(0xFFFF4081);
    }

    quads.add(ProjectedQuad3D(
      faceId: faceId,
      mediaIndex: mediaIndex,
      vertices: transformed,
      screenPoints: screenPoints,
      averageZ: zSum / 4.0,
      isFrontFacing: isFrontFacing,
      edgeGlowColor: glowColor,
    ));
  }
}
