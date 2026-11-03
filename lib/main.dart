// =============================================================================
// Zakura - Application Entry Point
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ffmpeg_kit_extended_flutter/ffmpeg_kit_extended_flutter.dart';
import 'export/crash_log.dart';
import 'ui/main_menu_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 1. Initialize FFmpegKitExtended (REQUIRED: Fixes "Bad state: not initialized" crash)
  await FFmpegKitExtended.initialize();

  // 2. Install CrashLog breadcrumb listener & post-mortem analyzer
  await CrashLog.init();
  CrashLog.install();

  // 3. Lock to Portrait orientation for mobile editing
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);

  // 4. Set transparent system overlays for edge-to-edge canvas
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Colors.black,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );

  runApp(const ZakuraApp());
  CrashLog.scheduleReport();
}

class ZakuraApp extends StatelessWidget {
  const ZakuraApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Zakura',
      navigatorKey: CrashLog.navigatorKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Colors.black,
        primaryColor: const Color(0xFFE040FB),
        fontFamily: 'Roboto',
      ),
      home: const MainMenuScreen(),
    );
  }
}
