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
      theme: ThemeData(primarySwatch: Colors.deepPurple),
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
  String _data = "Waiting for faces...";

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      // Receive ML Kit data here
      if(mounted) setState(() => _data = event.toString());
    }, onError: (e) {
      if(mounted) setState(() => _data = "Stream Error: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if(mounted) setState(() => _textureId = tid);
    } catch (e) {
      if(mounted) setState(() => _data = "Start Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("ML Kit Face Detector")),
      body: Column(
        children: [
          Expanded(
            child: Container(
              color: Colors.black,
              child: _textureId == null 
                  ? const Center(child: CircularProgressIndicator())
                  : Texture(textureId: _textureId!),
            ),
          ),
          Container(
            padding: const EdgeInsets.all(24),
            color: Colors.white,
            width: double.infinity,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text("Detection Results:", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                const SizedBox(height: 8),
                Text(_data, style: const TextStyle(fontSize: 18, color: Colors.deepPurple)),
              ],
            ),
          )
        ],
      ),
    );
  }
}
