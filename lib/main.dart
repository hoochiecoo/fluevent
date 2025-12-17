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
  int _imgW = 640; // стандартное разрешение камеры
  int _imgH = 480;
  List<Offset> _points = [];

  @override
  void initState() {
    super.initState();
    _startCamera();

    _events.receiveBroadcastStream().listen((event) {
      if (!mounted || event is! Map) return;

      final pts = event['points'] ?? [];

      final List<Offset> parsed = [];
      for (final p in pts) {
        parsed.add(Offset(p[0].toDouble(), p[1].toDouble()));
      }

      setState(() {
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _textureId == null
          ? const Center(child: CircularProgressIndicator())
          : Center(
              child: SizedBox(
                width: _imgW.toDouble(),
                height: _imgH.toDouble(),
                child: Stack(
                  children: [
                    Texture(textureId: _textureId!),
                    CustomPaint(
                      painter: PixelPainter(
                        points: _points,
                        imageW: _imgW,
                        imageH: _imgH,
                      ),
                    ),
                  ],
                ),
              ),
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

    // Камера почти всегда отдаёт landscape, экран — portrait
    final bool rotated = imageW > imageH;

    final double srcW = rotated ? imageH.toDouble() : imageW.toDouble();
    final double srcH = rotated ? imageW.toDouble() : imageH.toDouble();

    // используем scale = 1, так как texture уже 640x480
    final double scale = 1.0;

    final double offsetX = 0;
    final double offsetY = 0;

    final paint = Paint()
      ..color = Colors.yellowAccent.withOpacity(0.7)
      ..style = PaintingStyle.fill;

    for (final p in points) {
      double x = p.dx;
      double y = p.dy;

      // поворот 90° CW для Texture (не меняем, как ты просил)
      if (rotated) {
        final tmp = x;
        x = imageH - y;
        y = tmp;
      }

      final double px = x * scale + offsetX;
      final double py = y * scale + offsetY;

      // не рисуем за пределами texture
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
