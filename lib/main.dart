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
      theme: ThemeData.dark().copyWith(
        primaryColor: Colors.orange,
        scaffoldBackgroundColor: const Color(0xFF2C3E50),
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
  
  // Data State
  String _sceneData = "Scanning...";
  String _objectData = "No objects";
  bool _isCourt = false;

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if(mounted) {
        final Map data = event as Map;
        setState(() {
          _sceneData = data['scene'] ?? "";
          _objectData = data['objects'] ?? "";
          
          // Simple logic to detect court keywords
          String lowerScene = _sceneData.toLowerCase();
          _isCourt = lowerScene.contains("court") || 
                     lowerScene.contains("tennis") || 
                     lowerScene.contains("stadium");
        });
      }
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      setState(() => _textureId = tid);
    } catch (e) {
      print("Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Detector AI"), backgroundColor: Colors.black45),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                _textureId == null 
                    ? const Center(child: CircularProgressIndicator())
                    : Texture(textureId: _textureId!),
                
                // Overlay for Court Detection
                if (_isCourt)
                  Positioned(
                    top: 20, right: 20,
                    child: Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(color: Colors.green, borderRadius: BorderRadius.circular(8)),
                      child: const Text("🎾 TENNIS COURT DETECTED", style: TextStyle(fontWeight: FontWeight.bold)),
                    ),
                  )
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.all(20),
            color: Colors.black87,
            width: double.infinity,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text("SCENE ANALYSIS:", style: TextStyle(color: Colors.grey, fontSize: 12)),
                Text(_sceneData, style: const TextStyle(fontSize: 16, color: Colors.white, fontWeight: FontWeight.bold)),
                const SizedBox(height: 10),
                const Text("POSSIBLE ROUND OBJECTS:", style: TextStyle(color: Colors.grey, fontSize: 12)),
                Text(_objectData, style: const TextStyle(fontSize: 16, color: Colors.orangeAccent)),
              ],
            ),
          )
        ],
      ),
    );
  }
}
