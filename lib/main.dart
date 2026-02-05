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
  Map? _lastEvent;
  Map? _tfliteStatus;

  static const MethodChannel _methodChannel = MethodChannel('com.example.camera/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.camera/events');
  int? _textureId;
  
  String _sceneData = "Scanning...";
  String _objectData = "No objects";
  String _tfliteOutput = "";
  List<List<double>> _boxes = [];
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
          _boxes = (data['boxes'] as List<dynamic>?)?.map((b) => List<double>.from(b)).toList() ?? [];
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
            child: _textureId == null
                ? const Center(child: CircularProgressIndicator())
                : Texture(textureId: _textureId!),
          ),
          Container(
            padding: const EdgeInsets.all(20),
            color: Colors.black87,
            width: double.infinity,
            child: SingleChildScrollView(
              child: Text(
                _lastEvent == null ? 'Нет данных от Kotlin' : _lastEvent.toString(),
                style: const TextStyle(fontSize: 14, color: Colors.white),
              ),
            ),
          )
        ],
      ),
    );
  }
}
