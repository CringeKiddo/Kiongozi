// =============================================================================
// Zakura - Crash Log & Post-Mortem Diagnostic Reporter
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

class CrashLog {
  static const MethodChannel _channel = MethodChannel('com.zakura/media');

  static final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();
  static Directory? _dir;
  static final Stopwatch _clock = Stopwatch();
  static bool _active = false;

  static String? pendingReport;
  static bool lastCrashWasSigill = false;
  static String lastInterruptedOp = '';
  static String lastInterruptedTrail = '';

  static File get _trail => File('${_dir!.path}/trail.txt');
  static File get _marker => File('${_dir!.path}/running.marker');
  static File get _seen => File('${_dir!.path}/last_seen_exit.txt');
  static File get _lastReportFile => File('${_dir!.path}/last_report.txt');

  static Future<void> init() async {
    try {
      final base = await getApplicationSupportDirectory();
      _dir = Directory('${base.path}/crash_log');
      if (!_dir!.existsSync()) _dir!.createSync(recursive: true);
      await _buildPendingReport();
    } catch (e) {
      debugPrint('CrashLog.init failed: $e');
    }
  }

  static void install() {
    final previous = FlutterError.onError;
    FlutterError.onError = (FlutterErrorDetails d) {
      error(d.exception, d.stack);
      if (previous != null) {
        previous(d);
      } else {
        FlutterError.presentError(d);
      }
    };
    ui.PlatformDispatcher.instance.onError = (Object e, StackTrace s) {
      error(e, s);
      return false;
    };
  }

  static void scheduleReport() {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await Future<void>.delayed(const Duration(milliseconds: 700));
      await showPendingReport();
    });
  }

  static void begin(String operation, [Map<String, Object?> info = const {}]) {
    if (_dir == null) return;
    try {
      _clock..reset()..start();
      _active = true;
      final b = StringBuffer()
        ..writeln('OPERATION: $operation')
        ..writeln('started: ${DateTime.now().toIso8601String()}')
        ..writeln('platform: ${Platform.operatingSystemVersion}');
      info.forEach((k, v) => b.writeln('$k: $v'));
      b.writeln('--- steps ---');
      _trail.writeAsStringSync(b.toString(), flush: true);
      _marker.writeAsStringSync(operation, flush: true);
      note('begin');
    } catch (e) {
      debugPrint('CrashLog.begin failed: $e');
    }
  }

  static void note(String message) {
    if (_dir == null || !_active) return;
    try {
      String mem = '';
      try {
        mem = ' rss=${ProcessInfo.currentRss ~/ (1024 * 1024)}MB';
      } catch (_) {}
      _trail.writeAsStringSync(
        '[${_clock.elapsedMilliseconds}ms$mem] $message\n',
        mode: FileMode.append,
        flush: true,
      );
    } catch (_) {}
  }

  static void error(Object e, StackTrace? st) {
    if (_dir == null) return;
    try {
      final s = (st ?? StackTrace.empty).toString().split('\n').take(6).join('\n');
      _trail.writeAsStringSync('[ERROR] $e\n$s\n', mode: FileMode.append, flush: true);
    } catch (_) {}
  }

  static void end([String result = 'finished normally']) {
    if (_dir == null) return;
    try {
      note('end: $result');
      _active = false;
      if (_marker.existsSync()) _marker.deleteSync();
    } catch (_) {}
  }

  static Future<void> _buildPendingReport() async {
    final bool interrupted = _marker.existsSync();
    String interruptedOp = '';
    String trailText = '';
    if (interrupted) {
      try { interruptedOp = _marker.readAsStringSync(); } catch (_) {}
      try { trailText = _trail.existsSync() ? _trail.readAsStringSync() : ''; } catch (_) {}
      try { _marker.deleteSync(); } catch (_) {}
    }

    int lastSeen = 0;
    try {
      if (_seen.existsSync()) lastSeen = int.tryParse(_seen.readAsStringSync().trim()) ?? 0;
    } catch (_) {}

    List<Map<String, Object?>> exits = [];
    try {
      final raw = await _channel.invokeMethod<List<dynamic>>('getExitReasons');
      for (final r in raw ?? const <dynamic>[]) {
        if (r is Map) exits.add(r.map((k, v) => MapEntry(k.toString(), v)));
      }
    } catch (_) {}

    final fresh = exits.where((e) => ((e['timestamp'] as num?) ?? 0).toInt() > lastSeen).toList();
    int newest = lastSeen;
    for (final e in fresh) {
      final t = ((e['timestamp'] as num?) ?? 0).toInt();
      if (t > newest) newest = t;
    }
    try { _seen.writeAsStringSync('$newest', flush: true); } catch (_) {}

    lastInterruptedOp = interruptedOp;
    lastInterruptedTrail = trailText;
    lastCrashWasSigill = interrupted && fresh.any((e) => ((e['reason'] as num?) ?? -1).toInt() == 5 && ((e['status'] as num?) ?? 0).toInt() == 4);

    if (!interrupted && fresh.isEmpty) return;

    final b = StringBuffer()
      ..writeln('ZAKURA DIAGNOSTIC REPORT')
      ..writeln('Date: ${DateTime.now().toIso8601String()}\n');

    if (interrupted) {
      b.writeln('App closed abruptly during: $interruptedOp\n');
    }

    if (trailText.isNotEmpty) {
      b.writeln('--- Execution Trail ---');
      b.writeln(trailText);
    }

    pendingReport = b.toString();
    try { _lastReportFile.writeAsStringSync(pendingReport!, flush: true); } catch (_) {}
  }

  static Future<void> showPendingReport() async {
    final report = pendingReport;
    if (report == null) return;
    final ctx = navigatorKey.currentState?.overlay?.context;
    if (ctx == null) {
      Future.delayed(const Duration(milliseconds: 500), showPendingReport);
      return;
    }
    pendingReport = null;

    await showDialog<void>(
      context: ctx,
      builder: (dCtx) => AlertDialog(
        backgroundColor: const Color(0xFF14141E),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: const Text('Zakura Closed Unexpectedly', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        content: SingleChildScrollView(
          child: SelectableText(report, style: const TextStyle(color: Colors.white70, fontSize: 11, fontFamily: 'monospace')),
        ),
        actions: [
          TextButton(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: report));
              Navigator.pop(dCtx);
            },
            child: const Text('Copy', style: TextStyle(color: Color(0xFFE040FB))),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dCtx),
            child: const Text('Close', style: TextStyle(color: Colors.white54)),
          ),
        ],
      ),
    );
  }
}

class StageTimer {
  static final Map<String, int> _us = {};
  static final Stopwatch _wall = Stopwatch();

  static void reset() {
    _us.clear();
    _wall..reset()..start();
  }

  static Future<T> time<T>(String stage, FutureOr<T> Function() fn) async {
    final sw = Stopwatch()..start();
    try {
      return await fn();
    } finally {
      _us[stage] = (_us[stage] ?? 0) + sw.elapsedMicroseconds;
    }
  }
}
