import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blueAccent,
        scaffoldBackgroundColor: const Color(0xFFF5F5F5),
      ),
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
  String _mlData = "Waiting for faces...";
  
  // Dummy Switch States
  bool _isFaceDetectionEnabled = true;
  bool _isLandmarksEnabled = false;
  bool _isDebugMode = false;

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if(mounted) setState(() => _mlData = event.toString());
    }, onError: (e) {
      if(mounted) setState(() => _mlData = "Stream Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if(mounted) setState(() => _textureId = tid);
    } catch (e) {
      if(mounted) setState(() => _mlData = "Start Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Square Cam Control"),
        elevation: 2,
        backgroundColor: Colors.white,
      ),
      body: Column(
        children: [
          // 1. SQUARE CAMERA AT THE TOP
          // AspectRatio 1 means Width == Height
          AspectRatio(
            aspectRatio: 1.0, 
            child: Container(
              color: Colors.black,
              child: _textureId == null 
                  ? const Center(child: CircularProgressIndicator(color: Colors.white))
                  : ClipRect(
                      // FittedBox.cover ensures the video fills the square 
                      // (cropping edges like Instagram) rather than stretching.
                      child: FittedBox(
                        fit: BoxFit.cover, 
                        child: SizedBox(
                          // Native buffer size (generic approximation)
                          width: 480, 
                          height: 640,
                          child: Texture(textureId: _textureId!),
                        ),
                      ),
                    ),
            ),
          ),

          // 2. ML RESULT STRIP
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            color: Colors.blueGrey[50],
            child: Text(
              _mlData, 
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.blueGrey, fontWeight: FontWeight.bold),
            ),
          ),

          const Divider(height: 1),

          // 3. TOGGLES SECTION (Bottom)
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 10),
                  child: Text("Camera Settings", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                ),
                
                // Toggle 1
                _buildSwitchTile(
                  title: "Face Detection",
                  subtitle: "Enable ML Kit processing",
                  icon: Icons.face,
                  value: _isFaceDetectionEnabled,
                  onChanged: (v) => setState(() => _isFaceDetectionEnabled = v),
                ),

                const SizedBox(height: 10),

                // Toggle 2
                _buildSwitchTile(
                  title: "Show Landmarks",
                  subtitle: "Draw points on eyes/nose (Stub)",
                  icon: Icons.grid_on,
                  value: _isLandmarksEnabled,
                  onChanged: (v) => setState(() => _isLandmarksEnabled = v),
                ),

                const SizedBox(height: 10),

                // Toggle 3
                _buildSwitchTile(
                  title: "Debug Mode",
                  subtitle: "Show raw performance stats",
                  icon: Icons.developer_mode,
                  value: _isDebugMode,
                  onChanged: (v) => setState(() => _isDebugMode = v),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSwitchTile({
    required String title, 
    required String subtitle, 
    required IconData icon, 
    required bool value, 
    required Function(bool) onChanged
  }) {
    return Card(
      elevation: 0,
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.grey.shade300)
      ),
      child: SwitchListTile(
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text(subtitle, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
        secondary: Icon(icon, color: Colors.blueAccent),
        value: value,
        onChanged: onChanged,
        activeColor: Colors.blueAccent,
      ),
    );
  }
}
