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
        brightness: Brightness.dark,
        primarySwatch: Colors.green,
      ),
      home: const TennisDetectorScreen(),
    );
  }
}

class TennisDetectorScreen extends StatefulWidget {
  const TennisDetectorScreen({super.key});
  @override
  State<TennisDetectorScreen> createState() => _TennisDetectorScreenState();
}

class _TennisDetectorScreenState extends State<TennisDetectorScreen> {
  static const MethodChannel _methodChannel = MethodChannel('com.example.camera/methods');
  static const EventChannel _eventChannel = EventChannel('com.example.camera/events');
  
  int? _textureId;
  String _status = "Searching...";
  bool _isCourtDetected = false;
  List<String> _labels = [];

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final labels = List<String>.from(event['labels']);
        final detected = event['detected'] as bool;
        
        if (mounted) {
          setState(() {
            _labels = labels;
            _isCourtDetected = detected;
            _status = detected ? "TENNIS COURT DETECTED!" : "Scanning area...";
          });
        }
      }
    }, onError: (e) {
      if (mounted) setState(() => _status = "Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if (mounted) setState(() => _textureId = tid);
    } catch (e) {
      if (mounted) setState(() => _status = "Cam Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Tennis Court Detector"),
        backgroundColor: _isCourtDetected ? Colors.green : Colors.grey[900],
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                Container(
                  color: Colors.black,
                  child: _textureId == null 
                      ? const Center(child: CircularProgressIndicator())
                      : Texture(textureId: _textureId!),
                ),
                // Overlay for detected state
                if (_isCourtDetected)
                  Container(
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.greenAccent, width: 8),
                    ),
                  ),
                Positioned(
                  bottom: 20,
                  left: 20,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    color: Colors.black54,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: _labels.take(5).map((l) => Text(l, style: const TextStyle(color: Colors.white, fontSize: 14))).toList(),
                    ),
                  ),
                )
              ],
            ),
          ),
          Container(
            height: 100,
            width: double.infinity,
            color: _isCourtDetected ? Colors.green[800] : Colors.grey[850],
            alignment: Alignment.center,
            child: Text(
              _status,
              style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: Colors.white),
            ),
          )
        ],
      ),
    );
  }
}
