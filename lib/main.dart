import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CameraX Texture',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(primarySwatch: Colors.blue),
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
  String _analysisResult = "Waiting for data...";
  String _error = "";

  @override
  void initState() {
    super.initState();
    _initializeCamera();
    _subscribeToAnalysis();
  }

  Future<void> _initializeCamera() async {
    try {
      final int textureId = await _methodChannel.invokeMethod('startCamera');
      setState(() {
        _textureId = textureId;
      });
    } on PlatformException catch (e) {
      setState(() {
        _error = "Error: ${e.message}";
      });
    }
  }

  void _subscribeToAnalysis() {
    _eventChannel.receiveBroadcastStream().listen((event) {
      setState(() {
        _analysisResult = event.toString();
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("CI/CD Camera Build")),
      body: Column(
        children: [
          Expanded(
            child: Container(
              color: Colors.black,
              child: Center(
                child: _textureId == null
                    ? (_error.isNotEmpty 
                        ? Text(_error, style: const TextStyle(color: Colors.red)) 
                        : const CircularProgressIndicator())
                    : AspectRatio(
                        aspectRatio: 3.0 / 4.0,
                        child: Texture(textureId: _textureId!),
                      ),
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(20),
            width: double.infinity,
            color: Colors.white,
            child: Text("Native Data: $_analysisResult", 
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
          )
        ],
      ),
    );
  }
}
