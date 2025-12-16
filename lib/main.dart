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
  String _data = "Waiting...";

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      setState(() => _data = event.toString());
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      setState(() => _textureId = tid);
    } catch (e) {
      setState(() => _data = "Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("CameraX Fix")),
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
            padding: const EdgeInsets.all(20),
            width: double.infinity,
            color: Colors.white,
            child: Text("Data: $_data", style: const TextStyle(fontSize: 20)),
          )
        ],
      ),
    );
  }
}
