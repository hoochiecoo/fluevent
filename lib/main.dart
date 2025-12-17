import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: CameraScreen(),
    );
  }
}

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  static const MethodChannel _method =
      MethodChannel('com.example.camera/methods');
  static const EventChannel _events =
      EventChannel('com.example.camera/events');

  int? _textureId;
  int _imgW = 1;
  int _imgH = 1;
  List<Offset> _points = [];

  @override
  void initState() {
    super.initState();
    _startCamera();

    _events.receiveBroadcastStream().listen((event) {
      if (!mounted || event is! Map) return;

      final w = event['width'] ?? 1;
      final h = event['height'] ?? 1;
      final pts = event['points'] ?? [];

      final List<Offset> parsed = [];

      for (final p in pts) {
        parsed.add(Offset(p[0].toDouble(), p[1].toDouble()));
      }

      setState(() {
        _imgW = w;
        _imgH = h;
        _points = parsed;
      });
    });
  }

  Future<void> _startCamera() async {
    try {
      final id = await _method.invokeMethod('startCamera');
      if (!mounted) return;
      setState(() => _textureId = id);
    } catch (e) {
      debugPrint('Camera start error: $e');
    }
  }

  Future<void> _processImage() async {
    try {
      final path = await _method.invokeMethod<String>('processImage');
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Saved: $path')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('processImage failed: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _textureId == null
          ? const Center(child: CircularProgressIndicator())
          : LayoutBuilder(
              builder: (context, constraints) {
                // пропорции камеры и экрана
                final cameraAspect = _imgW / _imgH;
                final screenAspect = constraints.maxWidth / constraints.maxHeight;

                double displayW = constraints.maxWidth;
                double displayH = constraints.maxHeight;
                double offsetX = 0;
                double offsetY = 0;

                if (screenAspect > cameraAspect) {
                  // экран шире
                  displayH = constraints.maxHeight;
                  displayW = displayH * cameraAspect;
                  offsetX = (constraints.maxWidth - displayW) / 2;
                } else {
                  // экран выше
                  displayW = constraints.maxWidth;
                  displayH = displayW / cameraAspect;
                  offsetY = (constraints.maxHeight - displayH) / 2;
                }

                return Stack(
                  children: [
                    Positioned(
                      left: offsetX,
                      top: offsetY,
                      width: displayW,
                      height: displayH,
                      child: Texture(textureId: _textureId!),
                    ),
                    Positioned(
                      left: offsetX,
                      top: offsetY,
                      width: displayW,
                      height: displayH,
                      child: CustomPaint(
                        painter: PixelPainter(
                          points: _points,
                          imageW: _imgW,
                          imageH: _imgH,
                        ),
                      ),
                    ),
                    Positioned(
                      right: 16,
                      bottom: 16,
                      child: FloatingActionButton(
                        onPressed: _processImage,
                        child: const Icon(Icons.memory),
                      ),
                    )
                  ],
                );
              },
            ),
    );
  }
}

class PixelPainter extends CustomPainter {
  final List<Offset> points;
  final int imageW;
  final int imageH;

  PixelPainter({
    required this.points,
    required this.imageW,
    required this.imageH,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (imageW == 0 || imageH == 0) return;

    // проверяем, нужно ли повернуть
    final bool rotated = imageW > imageH;

    final double srcW = rotated ? imageH.toDouble() : imageW.toDouble();
    final double srcH = rotated ? imageW.toDouble() : imageH.toDouble();

    // BoxFit.cover
    final double scale =
        (size.width / srcW).compareTo(size.height / srcH) > 0
            ? size.width / srcW
            : size.height / srcH;

    final double drawnW = srcW * scale;
    final double drawnH = srcH * scale;

    final double offsetX = (size.width - drawnW) / 2;
    final double offsetY = (size.height - drawnH) / 2;

    final paint = Paint()
      ..color = Colors.yellowAccent.withOpacity(0.7)
      ..style = PaintingStyle.fill;

    for (final p in points) {
      double x = p.dx;
      double y = p.dy;

      // поворот 90° CW для Texture
      if (rotated) {
        final tmp = x;
        x = imageH - y;
        y = tmp;
      }

      final double px = x * scale + offsetX;
      final double py = y * scale + offsetY;

      if (px < 0 || py < 0 || px > size.width || py > size.height) continue;

      canvas.drawCircle(
        Offset(px, py),
        2,
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant PixelPainter oldDelegate) => true;
}
