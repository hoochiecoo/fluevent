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

      final w = event['width'];
      final h = event['height'];
      final pts = event['points'];

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
    final id = await _method.invokeMethod('startCamera');
    setState(() => _textureId = id);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            child: _textureId == null
                ? const Center(child: CircularProgressIndicator())
                : Texture(textureId: _textureId!),
          ),

          Positioned.fill(
            child: CustomPaint(
              painter: PixelPainter(
                points: _points,
                imageW: _imgW,
                imageH: _imgH,
              ),
            ),
          ),
        ],
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
    final sx = size.width / imageW;
    final sy = size.height / imageH;

    final paint = Paint()
      ..color = Colors.yellowAccent
      ..style = PaintingStyle.fill;

    for (final p in points) {
      canvas.drawCircle(
        Offset(p.dx * sx, p.dy * sy),
        2,
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant PixelPainter oldDelegate) => true;
}
