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
  Map? _tfliteStatus;

  static const MethodChannel _methodChannel = MethodChannel('com.example.camera/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.camera/events');
  int? _textureId;
  
  String _sceneData = "Scanning...";
  String _objectData = "No objects";
  String _tfliteOutput = "";
  bool _isCourt = false;

  @override
  void initState() {
    super.initState();
    _checkTFLite();
    _startCamera();
    _runTFLiteModel();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if(mounted) {
        final Map data = event as Map;
        setState(() {
          _sceneData = data['scene'] ?? "";
          _objectData = data['objects'] ?? "";
          
          String lowerScene = _sceneData.toLowerCase();
          _isCourt = lowerScene.contains("court") || 
                     lowerScene.contains("tennis") || 
                     lowerScene.contains("stadium") ||
                     lowerScene.contains("grass");
        });
      }
    });
  }

  Future<void> _runTFLiteModel() async {
    try {
      final output = await _methodChannel.invokeMethod('runTFLiteModel');
      setState(() => _tfliteOutput = output.toString());
    } catch (e) {
      setState(() => _tfliteOutput = 'Error: $e');
    }
  }

  Future<void> _checkTFLite() async {
    try {
      final status = await _methodChannel.invokeMethod('isTFLiteAvailable');
      setState(() => _tfliteStatus = Map.from(status));
    } catch (e) {
      setState(() => _tfliteStatus = null);
    }
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
                Row(
                  children: [
                    const Text("POSSIBLE ROUND OBJECTS:", style: TextStyle(color: Colors.grey, fontSize: 12)),
                    const SizedBox(width: 8),
                    Text(
                      _tfliteStatus == null
                        ? "(TFLite: ...)"
                        : (_tfliteStatus!["tflite"] == true
                            ? (_tfliteStatus!["model"] == true
                                ? "(TFLite: OK, Model: OK)"
                                : "(TFLite: OK, Model: Not found)")
                            : "(TFLite: Not found)"),
                      style: TextStyle(
                        color: _tfliteStatus == null
                            ? Colors.grey
                            : (_tfliteStatus!["tflite"] == true
                                ? (_tfliteStatus!["model"] == true ? Colors.green : Colors.orange)
                                : Colors.red),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                Text(_objectData, style: const TextStyle(fontSize: 16, color: Colors.orangeAccent)),
                const SizedBox(height: 8),
                Text(_tfliteOutput, style: const TextStyle(fontSize: 12, color: Colors.lightBlueAccent)),

              ],
            ),
          )
        ],
      ),
    );
  }
}
