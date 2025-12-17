import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: const CameraScreen(),
    );
  }
}

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});
  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  static const MethodChannel _methodChannel = MethodChannel('com.example.camera/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.camera/events');

  int? _textureId;
  bool _lineDetected = false;
  int _lineLength = 0;
  int _similarCount = 0;
  String _status = "Initializing...";
  final List<Offset> _highlightPoints = [];

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((dynamic event) {
      if (!mounted) return;

      if (event is Map) {
        final bool detected = event['detected'] ?? false;
        final int length = event['length'] ?? 0;
        final int similar = event['similarCount'] ?? 0;

        // Генерация случайных точек для визуализации similarCount
        final List<Offset> points = [];
        final Random rnd = Random();
        const double radius = 50; // радиус подсветки
        for (int i = 0; i < similar; i++) {
          final double angle = rnd.nextDouble() * 2 * pi;
          final double r = sqrt(rnd.nextDouble()) * radius;
          final double dx = r * cos(angle);
          final double dy = r * sin(angle);
          points.add(Offset(dx, dy));
        }

        setState(() {
          _lineDetected = detected;
          _lineLength = length;
          _similarCount = similar;
          _highlightPoints.clear();
          _highlightPoints.addAll(points);
          _status = detected ? "Line Detected!" : "Scanning...";
        });
      }
    }, onError: (e) {
      setState(() => _status = "Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if (mounted) setState(() => _textureId = tid);
    } catch (e) {
      if (mounted) setState(() => _status = "Permission Error or Crash: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Line Detector Cam")),
      body: Stack(
        children: [
          // 1. Слой Камеры
          Positioned.fill(
            child: _textureId == null
                ? const Center(child: CircularProgressIndicator())
                : Texture(textureId: _textureId!),
          ),

          // 2. Слой Подсветки линии
          if (_lineDetected)
            Center(
              child: Container(
                height: 4,
                width: double.infinity,
                margin: const EdgeInsets.symmetric(horizontal: 20),
                decoration: BoxDecoration(
                  color: Colors.redAccent,
                  boxShadow: [
                    BoxShadow(color: Colors.red.withOpacity(0.8), blurRadius: 10, spreadRadius: 2)
                  ],
                ),
              ),
            ),

          // 3. Слой визуализации похожих пикселей
          if (_highlightPoints.isNotEmpty)
            Center(
              child: CustomPaint(
                size: const Size(double.infinity, double.infinity),
                painter: _HighlightPainter(_highlightPoints),
              ),
            ),

          // 4. Инфо-панель
          Positioned(
            bottom: 30,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: _lineDetected ? Colors.red.withOpacity(0.8) : Colors.black54,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _status,
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
                  ),
                  if (_lineDetected) ...[
                    const SizedBox(height: 4),
                    Text(
                      "Length: $_lineLength px",
                      style: const TextStyle(color: Colors.white70),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      "Similar Pixels: $_similarCount",
                      style: const TextStyle(color: Colors.white70),
                    ),
                  ],
                ],
              ),
            ),
          )
        ],
      ),
    );
  }
}

class _HighlightPainter extends CustomPainter {
  final List<Offset> points;
  _HighlightPainter(this.points);

  @override
  void paint(Canvas canvas, Size size) {
    final Paint paint = Paint()
      ..color = Colors.yellowAccent.withOpacity(0.7)
      ..style = PaintingStyle.fill;

    final Offset center = Offset(size.width / 2, size.height / 2);

    for (final p in points) {
      canvas.drawCircle(center + p, 3, paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
