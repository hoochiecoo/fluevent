import 'dart:async';
import 'dart:convert';
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
      home: const LaneDetectorScreen(),
    );
  }
}

class LaneDetectorScreen extends StatefulWidget {
  const LaneDetectorScreen({super.key});
  @override
  State<LaneDetectorScreen> createState() => _LaneDetectorScreenState();
}

class _LaneDetectorScreenState extends State<LaneDetectorScreen> {
  static const MethodChannel _methodChannel = MethodChannel('com.example.lane/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.lane/events');
  
  int? _textureId;
  // Normilized coordinates (0.0 to 1.0)
  double _leftLineX = 0.2;
  double _rightLineX = 0.8;
  String _status = "Initializing...";

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      // Expecting JSON: {"left": 0.3, "right": 0.7}
      try {
        final Map<String, dynamic> data = jsonDecode(event);
        if(mounted) {
            setState(() {
                _leftLineX = data['left'] ?? _leftLineX;
                _rightLineX = data['right'] ?? _rightLineX;
                _status = "Tracking Active";
            });
        }
      } catch(e) {
          // ignore parsing errors
      }
    }, onError: (e) {
      if(mounted) setState(() => _status = "Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if(mounted) setState(() => _textureId = tid);
    } catch (e) {
      if(mounted) setState(() => _status = "Camera Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // 1. Camera Texture
          if (_textureId != null)
            Positioned.fill(child: Texture(textureId: _textureId!))
          else
            const Center(child: CircularProgressIndicator()),

          // 2. Lane Overlay
          if (_textureId != null)
            Positioned.fill(
              child: CustomPaint(
                painter: LanePainter(leftX: _leftLineX, rightX: _rightLineX),
              ),
            ),

          // 3. UI Overlay
          Positioned(
            bottom: 30,
            left: 20,
            right: 20,
            child: Container(
              padding: const EdgeInsets.all(15),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Colors.yellowAccent)
              ),
              child: Column(
                children: [
                    const Text("LANE ASSIST", style: TextStyle(color: Colors.yellowAccent, fontWeight: FontWeight.bold, letterSpacing: 2)),
                    const SizedBox(height: 5),
                    Text(_status, style: const TextStyle(color: Colors.white)),
                ],
              ),
            ),
          )
        ],
      ),
    );
  }
}

class LanePainter extends CustomPainter {
  final double leftX;
  final double rightX;

  LanePainter({required this.leftX, required this.rightX});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.greenAccent.withOpacity(0.7)
      ..strokeWidth = 6
      ..style = PaintingStyle.stroke;
      
    final fillPaint = Paint()
      ..color = Colors.green.withOpacity(0.2)
      ..style = PaintingStyle.fill;

    // Define points based on normalized X coordinates
    // We assume the lanes converge towards the center slightly at the horizon (0.4 height)
    
    // Bottom points
    final p1 = Offset(size.width * leftX, size.height);
    final p2 = Offset(size.width * rightX, size.height);
    
    // Top points (Fake perspective for visual effect based on bottom detection)
    // Shift slightly towards center
    final center = size.width / 2;
    final p3 = Offset(size.width * rightX - (size.width * rightX - center) * 0.6, size.height * 0.6);
    final p4 = Offset(size.width * leftX + (center - size.width * leftX) * 0.6, size.height * 0.6);

    // Draw Lines
    canvas.drawLine(p1, p4, paint);
    canvas.drawLine(p2, p3, paint);
    
    // Draw Safe Zone Path
    final path = Path()
      ..moveTo(p1.dx, p1.dy)
      ..lineTo(p4.dx, p4.dy)
      ..lineTo(p3.dx, p3.dy)
      ..lineTo(p2.dx, p2.dy)
      ..close();
      
    canvas.drawPath(path, fillPaint);
  }

  @override
  bool shouldRepaint(LanePainter oldDelegate) {
    return oldDelegate.leftX != leftX || oldDelegate.rightX != rightX;
  }
}
