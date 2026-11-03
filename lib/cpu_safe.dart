// =============================================================================
// Zakura - CPU-Safe Encoders & Architecture Probe
// Prevents SIGILL crashes by running pre-render probes and stepping down ASM
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'dart:convert';
import 'dart:io';

import 'package:ffmpeg_kit_extended_flutter/ffmpeg_kit_extended_flutter.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import 'crash_log.dart';

class CpuSafe {
  static final Map<String, int> _levels = {};
  static final Set<String> _verified = {};
  static bool _loaded = false;
  static File? _file;

  static String? encoderForCodec(String codec) {
    if (codec.contains('HEVC') || codec.contains('H.265')) return 'x265';
    if (codec.contains('H.264')) return 'x264';
    if (codec.contains('AV1')) return 'svtav1';
    if (codec.contains('VP9')) return 'vpx';
    return null;
  }

  static bool _hasCpuOverride(String enc) => enc == 'x265' || enc == 'x264' || enc == 'ffcore';

  static int level(String enc) => _levels[enc] ?? 0;

  static String x265AsmSuffix() {
    switch (level('x265')) {
      case 0: return '';
      case 1: return ':asm=2';
      default: return ':asm=0';
    }
  }

  static String ffArgs() {
    switch (level('ffcore')) {
      case 0: return '';
      case 1: return '-cpuflags -sve2-sve-i8mm-dotprod ';
      default: return '-cpuflags 0 ';
    }
  }

  static String x264Args() {
    switch (level('x264')) {
      case 0: return '';
      case 1: return '-x264-params asm=2';
      default: return '-x264-params asm=0';
    }
  }

  static Future<void> ensureLoaded() async {
    if (_loaded) return;
    _loaded = true;
    try {
      final base = await getApplicationSupportDirectory();
      final dir = Directory('${base.path}/crash_log');
      if (!dir.existsSync()) dir.createSync(recursive: true);
      _file = File('${dir.path}/cpu_safe.json');
      if (_file!.existsSync()) {
        final m = jsonDecode(_file!.readAsStringSync());
        if (m is Map) {
          final lv = m['levels'];
          if (lv is Map) lv.forEach((k, v) { if (v is num) _levels[k.toString()] = v.toInt(); });
          final ve = m['verified'];
          if (ve is List) _verified.addAll(ve.map((e) => e.toString()));
        }
      }
      _absorbLastCrash();
    } catch (e) {
      debugPrint('CpuSafe.ensureLoaded failed: $e');
    }
  }

  static void _save() {
    try {
      _file?.writeAsStringSync(jsonEncode({'levels': _levels, 'verified': _verified.toList()}), flush: true);
    } catch (_) {}
  }

  static void _absorbLastCrash() {
    if (!CrashLog.lastCrashWasSigill) return;
    if (!CrashLog.lastInterruptedOp.contains('Video export')) return;
    final enc = _encoderInTrail(CrashLog.lastInterruptedTrail);
    CrashLog.lastCrashWasSigill = false;
    if (enc == null) return;
    final int next = _hasCpuOverride(enc) ? (level(enc) + 1).clamp(0, 3).toInt() : 3;
    _levels[enc] = next;
    _verified.removeWhere((k) => k.startsWith('$enc|'));
    _save();
    debugPrint('CpuSafe: $enc crashed with SIGILL -> level $next');
  }

  static String? _encoderInTrail(String trail) {
    final lines = trail.split('\n');
    for (int i = lines.length - 1; i >= 0; i--) {
      final l = lines[i];
      if (l.contains('RETURNED')) return null;
      if (l.contains('START')) {
        if (l.contains('probe START (convert')) return 'ffcore';
        if (l.contains('libx265')) return 'x265';
        if (l.contains('libx264')) return 'x264';
        if (l.contains('libsvtav1')) return 'svtav1';
        if (l.contains('libvpx')) return 'vpx';
        return null;
      }
    }
    return null;
  }

  static String _name(String enc) {
    switch (enc) {
      case 'x265': return 'HEVC (x265)';
      case 'x264': return 'H.264 (x264)';
      case 'svtav1': return 'AV1 (SVT-AV1)';
      case 'ffcore': return 'colour conversion (FFmpeg core)';
      default: return 'VP9 (libvpx)';
    }
  }

  static Future<void> verifyConversion({required bool tenBit}) async {
    await ensureLoaded();
    final lv = level('ffcore');
    if (lv >= 3) {
      throw Exception('FFmpeg colour conversion failed on this hardware architecture.');
    }
    final key = 'ffcore|$lv|${tenBit ? 10 : 8}';
    if (_verified.contains(key)) return;
    final pix = tenBit ? 'yuv420p10le' : 'yuv420p';
    final matrix = tenBit ? 'bt2020' : 'bt709';
    final stages = <String, String>{
      'convert-noresize': 'format=bgr0,scale=out_color_matrix=$matrix:out_range=tv:flags=accurate_rnd,format=$pix',
      'convert-resize': 'format=rgba,scale=1920:1080:flags=lanczos+accurate_rnd:out_color_matrix=$matrix:out_range=tv,format=$pix',
    };
    bool allOk = true;
    for (final e in stages.entries) {
      final cmd = '-y ${ffArgs()}-f lavfi -i testsrc2=size=1280x720:rate=25 -frames:v 10 -vf ${e.value} -f null -';
      CrashLog.note('probe START (${e.key}, level $lv): ffmpeg $cmd');
      try {
        final session = await FFmpegKit.execute(cmd);
        final rc = await session.getReturnCode();
        CrashLog.note('probe RETURNED rc=$rc');
        if (!ReturnCode.isSuccess(rc)) allOk = false;
      } catch (err) {
        CrashLog.note('probe RETURNED with Dart exception: $err');
        allOk = false;
      }
    }
    if (allOk) {
      _verified.add(key);
      _save();
    }
  }

  static Future<void> verifyEncoder(String enc, {required bool tenBit}) async {
    await ensureLoaded();
    await verifyConversion(tenBit: tenBit);
    final lv = level(enc);
    if (lv >= 3) {
      throw Exception('${_name(enc)} crashes on this phone CPU even in compatibility mode.');
    }
    final key = '$enc|$lv|${tenBit ? 10 : 8}';
    if (_verified.contains(key)) return;

    final pix = tenBit ? 'yuv420p10le' : 'yuv420p';
    String codecArgs;
    switch (enc) {
      case 'x265':
        codecArgs = '-c:v libx265 -preset veryfast -x265-params log-level=error:pools=none:frame-threads=1:bframes=3:rc-lookahead=20${x265AsmSuffix()} -b:v 8M';
        break;
      case 'x264':
        codecArgs = '-c:v libx264 -preset fast ${x264Args()} -b:v 8M';
        break;
      case 'svtav1':
        codecArgs = '-c:v libsvtav1 -preset 6 -b:v 8M';
        break;
      default:
        codecArgs = '-c:v libvpx-vp9 -deadline good -cpu-used 4 -row-mt 1 -b:v 8M';
    }
    final cmd = '-y ${ffArgs()}-f lavfi -i testsrc2=size=1280x720:rate=25 -frames:v 14 -vf format=$pix $codecArgs -f null -';
    CrashLog.note('probe START (${_name(enc)}, level $lv): ffmpeg $cmd');
    try {
      final session = await FFmpegKit.execute(cmd);
      final rc = await session.getReturnCode();
      CrashLog.note('probe RETURNED rc=$rc');
      if (ReturnCode.isSuccess(rc)) {
        _verified.add(key);
        _save();
      }
    } catch (e) {
      CrashLog.note('probe RETURNED with exception: $e');
    }
  }
}
