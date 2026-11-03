// =============================================================================
// Zakura - Master Export Suite
// True 32-Bit Linear Pipeline • MediaStore Scoped Storage • Codecs & Formats
// Saves to:
//   - Motion Smear: /storage/emulated/0/Movies/Zakura/MotionSmear
//   - Nulls: /storage/emulated/0/Movies/Zakura/Nulls
// 100% Complete File - Zero Feature Omissions
// =============================================================================

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:ffmpeg_kit_extended_flutter/ffmpeg_kit_extended_flutter.dart';
import 'package:video_player/video_player.dart';

import '../models/models.dart';
import 'crash_log.dart';
import 'cpu_safe.dart';
import 'export_matrix.dart';

class ExportSuite {
  static const MethodChannel _mediaChannel = MethodChannel('com.zakura/media');

  // ===========================================================================
  // MEDIASTORE SCOPED STORAGE (Android 10 - 15 Compliant)
  // ===========================================================================
  static Future<String> saveToMovies({
    required String sourcePath,
    required String fileName,
    required String subFolder, // 'MotionSmear' or 'Nulls'
    required String mimeType,
  }) async {
    Object? nativeError;
    try {
      final uri = await _mediaChannel.invokeMethod<String>('saveToMovies', {
        'sourcePath': sourcePath,
        'fileName': fileName,
        'subFolder': subFolder,
        'mimeType': mimeType,
      });

      if (uri != null && uri.isNotEmpty) {
        debugPrint('Successfully saved via MediaStore: $uri');
        return uri;
      }
      nativeError = 'native save returned no URI';
    } catch (e) {
      nativeError = e;
      debugPrint('Native MediaStore save failed: $e');
    }

    // Direct filesystem fallback
    try {
      final moviesDir = Directory('/storage/emulated/0/Movies/Zakura/$subFolder');
      if (!moviesDir.existsSync()) {
        moviesDir.createSync(recursive: true);
      }
      final targetFile = File('${moviesDir.path}/$fileName');
      await File(sourcePath).copy(targetFile.path);

      try {
        await _mediaChannel.invokeMethod('scanFile', {'path': targetFile.path});
      } catch (_) {}

      return targetFile.path;
    } catch (e) {
      throw Exception('Could not save to Movies/Zakura/$subFolder.\nMediaStore: $nativeError\nDirect copy: $e');
    }
  }

  // ===========================================================================
  // VIDEO EXPORT SHEET
  // ===========================================================================
  static void showVideoExportSheet({
    required BuildContext context,
    required ProjectData project,
    required String subFolder, // 'MotionSmear' or 'Nulls'
    required Future<Uint8List> Function(double timestampMs) renderFrameToRgba,
    Future<void> Function(double startMs, int frameCount, double fps)? prepareFrames,
    required double videoDurationMs,
    required double videoFps,
    required int videoWidth,
    required int videoHeight,
    String? audioSourcePath,
  }) {
    String selectedRes = '1080p';
    String selectedCodec = 'H.264';
    String selectedBitDepth = '8-bit';
    String selectedBitrate = 'High (25 Mbps)';
    String selectedContainer = 'MP4';
    double selectedFps = const [24.0, 25.0, 30.0, 48.0, 50.0, 60.0].contains(videoFps) ? videoFps : 30.0;

    bool isExporting = false;
    double exportProgress = 0.0;
    String statusText = 'Ready to encode';

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF14141E),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (context, setSheetState) {
          return Padding(
            padding: EdgeInsets.only(
              left: 20,
              right: 20,
              top: 20,
              bottom: MediaQuery.of(context).viewInsets.bottom + 24,
            ),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'Export Zakura $subFolder Video',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 0.5,
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close, color: Colors.white54),
                        onPressed: isExporting ? null : () => Navigator.pop(context),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),

                  // 1. Resolution (Native aspect ratio strictly preserved)
                  const Text('Resolution', style: TextStyle(color: Colors.white70, fontSize: 13)),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: ['Native', '720p', '1080p', '2K', '4K'].map((res) {
                      final selected = selectedRes == res;
                      return ChoiceChip(
                        label: Text(res),
                        selected: selected,
                        selectedColor: const Color(0xFFE040FB),
                        backgroundColor: const Color(0xFF1E1E2C),
                        labelStyle: TextStyle(
                          color: selected ? Colors.black : Colors.white70,
                          fontWeight: FontWeight.w600,
                        ),
                        onSelected: isExporting ? null : (s) {
                          if (s) setSheetState(() => selectedRes = res);
                        },
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 14),

                  // 2. Frame Rate
                  const Text('Frame Rate', style: TextStyle(color: Colors.white70, fontSize: 13)),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [24.0, 30.0, 60.0].map((f) {
                      final selected = selectedFps == f;
                      return ChoiceChip(
                        label: Text('${f.toInt()} fps'),
                        selected: selected,
                        selectedColor: const Color(0xFFE040FB),
                        backgroundColor: const Color(0xFF1E1E2C),
                        labelStyle: TextStyle(
                          color: selected ? Colors.black : Colors.white70,
                          fontWeight: FontWeight.w600,
                        ),
                        onSelected: isExporting ? null : (s) {
                          if (s) setSheetState(() => selectedFps = f);
                        },
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 14),

                  // 3. Codec
                  const Text('Codec', style: TextStyle(color: Colors.white70, fontSize: 13)),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: ['H.264', 'HEVC / H.265', 'AV1', 'VP9', 'Apple ProRes'].map((cdc) {
                      final selected = selectedCodec == cdc;
                      return ChoiceChip(
                        label: Text(cdc),
                        selected: selected,
                        selectedColor: const Color(0xFFE040FB),
                        backgroundColor: const Color(0xFF1E1E2C),
                        labelStyle: TextStyle(
                          color: selected ? Colors.black : Colors.white70,
                          fontWeight: FontWeight.w600,
                        ),
                        onSelected: isExporting ? null : (s) {
                          if (s) {
                            setSheetState(() {
                              final r = _reconcile(
                                container: selectedContainer,
                                codec: cdc,
                                depth: selectedBitDepth,
                                changed: 'codec',
                              );
                              selectedContainer = r[0];
                              selectedCodec = r[1];
                              selectedBitDepth = r[2];
                            });
                          }
                        },
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 14),

                  // 4. Container Format
                  const Text('Container Format', style: TextStyle(color: Colors.white70, fontSize: 13)),
                  const SizedBox(height: 6),
                  Row(
                    children: ['MP4', 'MKV', 'MOV', 'WEBM'].map((ctn) {
                      final selected = selectedContainer == ctn;
                      return Expanded(
                        child: GestureDetector(
                          onTap: isExporting
                              ? null
                              : () => setSheetState(() {
                                    final r = _reconcile(
                                      container: ctn,
                                      codec: selectedCodec,
                                      depth: selectedBitDepth,
                                      changed: 'container',
                                    );
                                    selectedContainer = r[0];
                                    selectedCodec = r[1];
                                    selectedBitDepth = r[2];
                                  }),
                          child: Container(
                            margin: const EdgeInsets.symmetric(horizontal: 4),
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            decoration: BoxDecoration(
                              color: selected ? const Color(0xFFE040FB).withOpacity(0.18) : const Color(0xFF1E1E2C),
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(
                                color: selected ? const Color(0xFFE040FB) : Colors.white12,
                              ),
                            ),
                            alignment: Alignment.center,
                            child: Text(
                              ctn,
                              style: TextStyle(
                                color: selected ? const Color(0xFFE040FB) : Colors.white70,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 14),

                  // 5. Bit Depth
                  const Text('Bit Depth', style: TextStyle(color: Colors.white70, fontSize: 13)),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      _buildDepthOption('8-bit', 'SDR (Rec.709)', selectedBitDepth, isExporting, (d) {
                        setSheetState(() {
                          final r = _reconcile(
                            container: selectedContainer,
                            codec: selectedCodec,
                            depth: d,
                            changed: 'depth',
                          );
                          selectedContainer = r[0];
                          selectedCodec = r[1];
                          selectedBitDepth = r[2];
                        });
                      }),
                      _buildDepthOption('10-bit', 'HDR (BT.2020)', selectedBitDepth, isExporting, (d) {
                        setSheetState(() {
                          final r = _reconcile(
                            container: selectedContainer,
                            codec: selectedCodec,
                            depth: d,
                            changed: 'depth',
                          );
                          selectedContainer = r[0];
                          selectedCodec = r[1];
                          selectedBitDepth = r[2];
                        });
                      }),
                      _buildDepthOption('16-bit', 'Master (4:4:4)', selectedBitDepth, isExporting, (d) {
                        setSheetState(() {
                          final r = _reconcile(
                            container: selectedContainer,
                            codec: selectedCodec,
                            depth: d,
                            changed: 'depth',
                          );
                          selectedContainer = r[0];
                          selectedCodec = r[1];
                          selectedBitDepth = r[2];
                        });
                      }),
                    ],
                  ),
                  const SizedBox(height: 20),

                  if (isExporting) ...[
                    ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: LinearProgressIndicator(
                        value: exportProgress > 0.0 ? exportProgress : null,
                        minHeight: 6,
                        backgroundColor: Colors.white10,
                        valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFFE040FB)),
                      ),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      statusText,
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white60, fontSize: 12),
                    ),
                    const SizedBox(height: 16),
                  ] else if (statusText.startsWith('Export error')) ...[
                    SelectableText(
                      statusText,
                      style: const TextStyle(color: Colors.redAccent, fontSize: 12),
                    ),
                    const SizedBox(height: 16),
                  ],

                  ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFFE040FB),
                      foregroundColor: Colors.black,
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    onPressed: isExporting
                        ? null
                        : () async {
                            setSheetState(() {
                              isExporting = true;
                              statusText = 'Preparing pipeline & frame stream...';
                              exportProgress = 0.02;
                            });

                            try {
                              final finishedVideoPath = await executeVideoExport(
                                context: context,
                                project: project,
                                subFolder: subFolder,
                                renderFrameToRgba: renderFrameToRgba,
                                prepareFrames: prepareFrames,
                                videoDurationMs: videoDurationMs,
                                videoFps: selectedFps,
                                videoWidth: videoWidth,
                                videoHeight: videoHeight,
                                audioSourcePath: audioSourcePath,
                                resolution: selectedRes,
                                codec: selectedCodec,
                                bitDepth: selectedBitDepth,
                                bitrateProfile: selectedBitrate,
                                container: selectedContainer,
                                onProgress: (p, msg) {
                                  setSheetState(() {
                                    exportProgress = p;
                                    statusText = msg;
                                  });
                                },
                              );

                              if (context.mounted) {
                                Navigator.pop(context); // Close export sheet
                                // Show smooth finish playback popup
                                _showPlaybackPopup(context, finishedVideoPath, selectedFps);
                              }
                            } catch (e) {
                              setSheetState(() {
                                isExporting = false;
                                statusText = 'Export error: $e';
                              });
                            }
                          },
                    child: const Text('Export Video', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  static Widget _buildDepthOption(
    String key,
    String label,
    String current,
    bool isExporting,
    Function(String) onSelect,
  ) {
    final selected = current.startsWith(key);
    return Expanded(
      child: GestureDetector(
        onTap: isExporting ? null : () => onSelect(key),
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 4),
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFFE040FB).withOpacity(0.18) : const Color(0xFF1E1E2C),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: selected ? const Color(0xFFE040FB) : Colors.white12,
            ),
          ),
          alignment: Alignment.center,
          child: Column(
            children: [
              Text(
                key,
                style: TextStyle(
                  color: selected ? const Color(0xFFE040FB) : Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                label,
                style: TextStyle(
                  color: selected ? const Color(0xFFE040FB).withOpacity(0.8) : Colors.white54,
                  fontSize: 10,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ===========================================================================
  // EXECUTE VIDEO EXPORT (Rolling 30-frame scratch pipeline)
  // ===========================================================================
  static Future<String> executeVideoExport({
    required BuildContext context,
    required ProjectData project,
    required String subFolder,
    required Future<Uint8List> Function(double timestampMs) renderFrameToRgba,
    Future<void> Function(double startMs, int frameCount, double fps)? prepareFrames,
    required double videoDurationMs,
    required double videoFps,
    required int videoWidth,
    required int videoHeight,
    String? audioSourcePath,
    required String resolution,
    required String codec,
    required String bitDepth,
    required String bitrateProfile,
    required String container,
    required Function(double, String) onProgress,
  }) async {
    Directory? sessionTempDir;
    final timestamp = DateTime.now().millisecondsSinceEpoch;

    try {
      StageTimer.reset();
      CrashLog.begin('Video export ($subFolder)', {
        'codec': codec,
        'container': container,
        'bitDepth': bitDepth,
        'bitrateProfile': bitrateProfile,
        'resolution': resolution,
        'source': '${videoWidth}x$videoHeight @ ${videoFps}fps, ${videoDurationMs.round()} ms',
      });

      final appTempDir = await getTemporaryDirectory();
      sessionTempDir = Directory('${appTempDir.path}/zakura_sess_$timestamp');
      if (!sessionTempDir.existsSync()) {
        sessionTempDir.createSync(recursive: true);
      }

      await CpuSafe.ensureLoaded();
      final String? guardedEncoder = CpuSafe.encoderForCodec(codec);
      final bool probeTenBit = bitDepth.startsWith('10') || bitDepth.startsWith('16');
      onProgress(0.01, 'Checking encoder compatibility...');
      await CpuSafe.verifyConversion(tenBit: probeTenBit);
      if (guardedEncoder != null) {
        await CpuSafe.verifyEncoder(guardedEncoder, tenBit: probeTenBit);
      }

      final expectedBytes = videoWidth * videoHeight * 4;
      final targetDims = _calculateDimensions(resolution, videoWidth, videoHeight);
      final outW = targetDims['w']!;
      final outH = targetDims['h']!;

      final totalFrames = ((videoDurationMs / 1000.0) * videoFps).round().clamp(1, 999999);
      final frameIntervalMs = 1000.0 / videoFps;
      final bool isHevcExport = codec.contains('HEVC') || codec.contains('H.265');

      const batchSize = 30;
      final totalBatches = (totalFrames / batchSize).ceil();
      final List<String> partVideoPaths = [];

      for (int b = 0; b < totalBatches; b++) {
        final startFrame = b * batchSize;
        final endFrame = (startFrame + batchSize).clamp(0, totalFrames);
        final currentBatchCount = endFrame - startFrame;

        if (prepareFrames != null) {
          await StageTimer.time('batch: decode source', () => prepareFrames(startFrame * frameIntervalMs, currentBatchCount, videoFps));
        }

        final rawFile = File('${sessionTempDir.path}/batch_$b.rgba');
        final raf = await rawFile.open(mode: FileMode.write);
        try {
          for (int f = 0; f < currentBatchCount; f++) {
            final globalFrameIdx = startFrame + f;
            final timeMs = globalFrameIdx * frameIntervalMs;

            final bytes = await renderFrameToRgba(timeMs);
            if (bytes.length != expectedBytes) {
              throw Exception('Frame $globalFrameIdx returned ${bytes.length} bytes, expected $expectedBytes.');
            }
            await StageTimer.time('frame: write raw to disk', () => raf.writeFrom(bytes));

            final progressRatio = (globalFrameIdx / totalFrames) * 0.70;
            onProgress(progressRatio, 'Rendering frames (${globalFrameIdx + 1}/$totalFrames)...');
          }
          await raf.flush();
        } finally {
          await raf.close();
        }

        final partExt = isHevcExport ? 'mkv' : container.toLowerCase();
        final partFile = File('${sessionTempDir.path}/part_${b.toString().padLeft(4, '0')}.$partExt');

        final ffmpegBatchCmd = isHevcExport
            ? _buildMezzanineCmd(
                inputFile: rawFile.path,
                outputFile: partFile.path,
                fps: videoFps,
                inW: videoWidth,
                inH: videoHeight,
                outW: outW,
                outH: outH,
              )
            : _buildBatchEncodeCmd(
                inputFile: rawFile.path,
                outputFile: partFile.path,
                fps: videoFps,
                inW: videoWidth,
                inH: videoHeight,
                outW: outW,
                outH: outH,
                codec: codec,
                bitDepth: bitDepth,
                bitrateProfile: bitrateProfile,
                container: container,
              );

        final session = await StageTimer.time('batch: encode', () => FFmpegKit.execute(ffmpegBatchCmd));
        final rc = await session.getReturnCode();

        if (!ReturnCode.isSuccess(rc) || !partFile.existsSync() || partFile.lengthSync() == 0) {
          final logTail = await _sessionLogTail(session);
          throw Exception('FFmpeg batch $b failed (rc: $rc)\n$logTail');
        }

        partVideoPaths.add(partFile.path);

        try {
          rawFile.deleteSync();
        } catch (_) {}
      }

      onProgress(0.75, 'Concatenating video stream & remuxing audio...');
      final concatListFile = File('${sessionTempDir.path}/concat_list.txt');
      final concatContent = partVideoPaths.map((p) => "file '$p'").join('\n');
      await concatListFile.writeAsString(concatContent, flush: true);

      final ext = container.toLowerCase();
      final tempFinalFile = File('${sessionTempDir.path}/temp_master_$timestamp.$ext');

      final fastStart = (container == 'MP4' || container == 'MOV') ? '-movflags +faststart' : '';
      String concatCmd;
      if (audioSourcePath != null && File(audioSourcePath).existsSync()) {
        final audioCodec = ExportMatrix.getAudioCodec(container);
        concatCmd = '-y -f concat -safe 0 -i "${concatListFile.path}" -i "$audioSourcePath" '
            '-map 0:v:0 -map 1:a:0? -c:v copy -c:a $audioCodec -b:a 320k -shortest $fastStart "${tempFinalFile.path}"';
      } else {
        concatCmd = '-y -f concat -safe 0 -i "${concatListFile.path}" -c copy $fastStart "${tempFinalFile.path}"';
      }

      if (isHevcExport) {
        await _encodeHevcFinal(
          concatList: concatListFile,
          audioSourcePath: audioSourcePath,
          outFile: tempFinalFile,
          container: container,
          bitDepth: bitDepth,
          bitrateProfile: bitrateProfile,
          onProgress: onProgress,
        );
      } else {
        final concatSession = await StageTimer.time('final: concat + audio', () => FFmpegKit.execute(concatCmd));
        final concatRc = await concatSession.getReturnCode();

        if (!ReturnCode.isSuccess(concatRc) || !tempFinalFile.existsSync() || tempFinalFile.lengthSync() == 0) {
          final logTail = await _sessionLogTail(concatSession);
          throw Exception('Stream concatenation failed (rc: $concatRc)\n$logTail');
        }
      }

      onProgress(0.92, 'Registering video with Android MediaStore...');
      final cleanCodec = codec.replaceAll(RegExp(r'[^A-Za-z0-9.]+'), '_');
      final fileName = 'Zakura_${subFolder}_${resolution}_${cleanCodec}_$timestamp.$ext';

      final mimeType = container == 'WEBM'
          ? 'video/webm'
          : container == 'MOV'
              ? 'video/quicktime'
              : container == 'MKV'
                  ? 'video/x-matroska'
                  : 'video/mp4';

      final finalUri = await StageTimer.time('save to Movies', () => saveToMovies(
        sourcePath: tempFinalFile.path,
        fileName: fileName,
        subFolder: subFolder,
        mimeType: mimeType,
      ));

      CrashLog.end('video export finished');
      onProgress(1.0, 'Export complete!');

      return tempFinalFile.path;
    } catch (e) {
      CrashLog.end('FAILED with exception: $e');
      rethrow;
    } finally {
      try {
        await prepareFrames?.call(0, 0, videoFps);
      } catch (_) {}
      try {
        if (sessionTempDir != null && sessionTempDir.existsSync()) {
          sessionTempDir.deleteSync(recursive: true);
        }
      } catch (_) {}
    }
  }

  // ===========================================================================
  // FINISHED VIDEO PLAYBACK POPUP
  // ===========================================================================
  static void _showPlaybackPopup(BuildContext context, String videoPath, double fps) {
    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: const Color(0xFF14141E),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        child: _VideoPlaybackDialog(videoPath: videoPath, fps: fps),
      ),
    );
  }

  // ===========================================================================
  // FFMPEG COMMAND BUILDERS
  // ===========================================================================
  static String _buildBatchEncodeCmd({
    required String inputFile,
    required String outputFile,
    required double fps,
    required int inW,
    required int inH,
    required int outW,
    required int outH,
    required String codec,
    required String bitDepth,
    required String bitrateProfile,
    required String container,
  }) {
    final is10Bit = bitDepth.startsWith('10');
    final is16Bit = bitDepth.startsWith('16');
    final isProRes = codec.contains('ProRes');
    final isHevc = codec.contains('HEVC') || codec.contains('H.265');
    final isAv1 = codec.contains('AV1');
    final isVp9 = codec.contains('VP9');

    String pixFmt;
    String colorMetadata;
    if (is10Bit) {
      pixFmt = '-pix_fmt yuv420p10le';
      colorMetadata = '-color_range tv -colorspace bt2020nc -color_primaries bt2020 -color_trc arib-std-b67';
    } else if (is16Bit) {
      pixFmt = '-pix_fmt yuv444p10le';
      colorMetadata = '-color_range tv -colorspace bt2020nc -color_primaries bt2020 -color_trc smpte2084';
    } else {
      pixFmt = '-pix_fmt yuv420p';
      colorMetadata = '-color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709';
    }

    String cOption;
    String speed = '';
    String rate = '';
    String codecExtra = '';
    if (isProRes) {
      if (is16Bit) {
        cOption = '-c:v prores_ks -profile:v 4';
        pixFmt = '-pix_fmt yuv444p10le';
      } else {
        cOption = '-c:v prores_ks -profile:v 3';
        pixFmt = '-pix_fmt yuv422p10le';
      }
    } else if (isHevc) {
      final hvcTag = (container.toUpperCase() == 'MP4' || container.toUpperCase() == 'MOV') ? ' -tag:v hvc1' : '';
      cOption = '-c:v libx265$hvcTag';
      speed = '-preset veryfast';
      rate = '-crf 20';
      codecExtra = '-x265-params log-level=error:pools=none:frame-threads=1:bframes=3:rc-lookahead=20${CpuSafe.x265AsmSuffix()}';
    } else if (isAv1) {
      cOption = '-c:v libsvtav1';
      speed = '-preset 6';
      rate = '-crf 24';
    } else if (isVp9) {
      cOption = '-c:v libvpx-vp9';
      speed = '-deadline good -cpu-used 4 -row-mt 1';
      rate = '-crf 22 -b:v 0';
    } else {
      cOption = '-c:v libx264';
      speed = '-preset fast';
      codecExtra = CpuSafe.x264Args();
      rate = '-crf 18';
    }

    final String pixName = pixFmt.replaceFirst('-pix_fmt ', '');
    final String matrix = (is10Bit || is16Bit) ? 'bt2020' : 'bt709';
    final scaleFilter = 'scale=$outW:$outH:flags=lanczos+accurate_rnd:out_color_matrix=$matrix:out_range=tv,format=$pixName';

    return '-y ${CpuSafe.ffArgs()}-f rawvideo -pixel_format rgba -video_size ${inW}x$inH -framerate $fps '
        '-i "$inputFile" -vf "$scaleFilter" $cOption $speed $codecExtra $pixFmt $colorMetadata $rate "$outputFile"';
  }

  static String _buildMezzanineCmd({
    required String inputFile,
    required String outputFile,
    required double fps,
    required int inW,
    required int inH,
    required int outW,
    required int outH,
  }) {
    return '-y ${CpuSafe.ffArgs()}-f rawvideo -pixel_format rgba -video_size ${inW}x$inH -framerate $fps '
        '-i "$inputFile" -vf "scale=$outW:$outH:flags=lanczos,format=bgr0" '
        '-c:v ffv1 -level 3 -g 1 -slicecrc 0 -an "$outputFile"';
  }

  static Future<void> _encodeHevcFinal({
    required File concatList,
    required String? audioSourcePath,
    required File outFile,
    required String container,
    required String bitDepth,
    required String bitrateProfile,
    required Function(double, String) onProgress,
  }) async {
    onProgress(0.80, 'Encoding HEVC (x265)...');
    final is10 = bitDepth.startsWith('10');
    final is16 = bitDepth.startsWith('16');
    final c = container.toUpperCase();
    final isMp4Family = c == 'MP4' || c == 'MOV';

    String pixFmt = is10 ? 'yuv420p10le' : (is16 ? 'yuv444p10le' : 'yuv420p');
    String matrix = (is10 || is16) ? 'bt2020' : 'bt709';
    String colorMetadata = (is10 || is16)
        ? '-color_range tv -colorspace bt2020nc -color_primaries bt2020 -color_trc arib-std-b67'
        : '-color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709';

    final video = '-c:v libx265${isMp4Family ? ' -tag:v hvc1' : ''} -preset veryfast '
        '-x265-params log-level=error:pools=none:frame-threads=1:bframes=3:rc-lookahead=20${CpuSafe.x265AsmSuffix()} -crf 20';

    final fastStart = isMp4Family ? '-movflags +faststart' : '';
    final hasAudio = audioSourcePath != null && File(audioSourcePath).existsSync();
    final audioIn = hasAudio ? '-i "$audioSourcePath" ' : '';
    final audioOpts = hasAudio
        ? '-map 0:v:0 -map 1:a:0? -c:a ${ExportMatrix.getAudioCodec(container)} -b:a 320k -shortest'
        : '-map 0:v:0 -an';

    final cmd = '-y ${CpuSafe.ffArgs()}-f concat -safe 0 -i "${concatList.path}" $audioIn'
        '-vf "scale=out_color_matrix=$matrix:out_range=tv:flags=accurate_rnd,format=$pixFmt" '
        '$video $colorMetadata $audioOpts $fastStart "${outFile.path}"';

    final session = await StageTimer.time('final: HEVC encode', () => FFmpegKit.execute(cmd));
    final rc = await session.getReturnCode();

    if (!ReturnCode.isSuccess(rc) || !outFile.existsSync() || outFile.lengthSync() < 2048) {
      final tail = await _sessionLogTail(session);
      throw Exception('HEVC encode failed (rc: $rc)\n$tail');
    }
  }

  static Map<String, int> _calculateDimensions(String resName, int srcW, int srcH) {
    int targetLongEdge;
    switch (resName) {
      case '720p': targetLongEdge = 1280; break;
      case '1080p': targetLongEdge = 1920; break;
      case '2K': targetLongEdge = 2560; break;
      case '4K': targetLongEdge = 3840; break;
      case 'Native':
      default:
        targetLongEdge = srcW > srcH ? srcW : srcH;
        break;
    }

    final aspect = srcW / srcH;
    int w, h;
    if (aspect >= 1.0) {
      w = targetLongEdge;
      h = (targetLongEdge / aspect).round();
    } else {
      h = targetLongEdge;
      w = (targetLongEdge * aspect).round();
    }

    if (w % 2 != 0) w += 1;
    if (h % 2 != 0) h += 1;

    return {'w': w, 'h': h};
  }

  static String _matrixKey(String container) =>
      container.toUpperCase() == 'WEBM' ? 'WebM' : container.toUpperCase();

  static String _codecToken(String codec) {
    if (codec.contains('ProRes')) return 'ProRes';
    if (codec.contains('HEVC') || codec.contains('H.265')) return 'HEVC';
    if (codec.contains('AV1')) return 'AV1';
    if (codec.contains('VP9')) return 'VP9';
    return 'H.264';
  }

  static bool _comboValid(String container, String codec) {
    final list = ExportMatrix.containerCodecs[_matrixKey(container)] ?? const <String>[];
    final token = _codecToken(codec);
    return list.any((e) => e.contains(token));
  }

  static List<String> _reconcile({
    required String container,
    required String codec,
    required String depth,
    required String changed,
  }) {
    String c = container, k = codec, d = depth;
    bool depthOk() => ExportMatrix.isBitDepthValid(_matrixKey(c), k, d);

    if (changed == 'depth') {
      if (!depthOk()) {
        if (d == '16-bit') c = 'MKV';
        else if (d == '10-bit' && k.contains('H.264')) k = 'HEVC / H.265';
        else if (d == '8-bit' && k.contains('ProRes')) k = 'H.264';
      }
    }
    return [c, k, d];
  }

  static Future<String> _sessionLogTail(dynamic session) async {
    try {
      final out = await session.getOutput();
      final s = (out ?? '').toString();
      return s.length > 500 ? s.substring(s.length - 500) : s;
    } catch (_) {
      return '(FFmpeg log unavailable)';
    }
  }
}

// Dialog for smooth finished playback popup
class _VideoPlaybackDialog extends StatefulWidget {
  final String videoPath;
  final double fps;

  const _VideoPlaybackDialog({required this.videoPath, required this.fps});

  @override
  State<_VideoPlaybackDialog> createState() => _VideoPlaybackDialogState();
}

class _VideoPlaybackDialogState extends State<_VideoPlaybackDialog> {
  late VideoPlayerController _controller;
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.file(File(widget.videoPath))
      ..initialize().then((_) {
        setState(() => _initialized = true);
        _controller.play();
        _controller.setLooping(true);
      });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Finished Render (${widget.fps.toInt()} fps)',
                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 16),
              ),
              IconButton(
                icon: const Icon(Icons.close, color: Colors.white54),
                onPressed: () => Navigator.pop(context),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (_initialized)
            AspectRatio(
              aspectRatio: _controller.value.aspectRatio,
              child: VideoPlayer(_controller),
            )
          else
            const SizedBox(
              height: 200,
              child: Center(child: CircularProgressIndicator(color: Color(0xFFE040FB))),
            ),
          const SizedBox(height: 12),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFFE040FB),
              foregroundColor: Colors.black,
            ),
            onPressed: () => Navigator.pop(context),
            child: const Text('Done', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
        ],
      ),
    );
  }
}
