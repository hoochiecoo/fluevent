import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // Канал событий должен совпадать с Kotlin
  static const eventChannel = EventChannel('com.example.flutter_opencv_lab/detection');
  
  bool _isLineDetected = false;

  @override
  void initState() {
    super.initState();
    // Слушаем поток данных от C++
    eventChannel.receiveBroadcastStream().listen((event) {
      if (event is bool) {
        setState(() {
          _isLineDetected = event;
        });
      }
    }, onError: (dynamic error) {
      debugPrint('Error: ${error.message}');
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        backgroundColor: _isLineDetected ? Colors.green : Colors.red,
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                _isLineDetected ? "LINE DETECTED!" : "NO LINE",
                style: const TextStyle(
                  fontSize: 32, 
                  color: Colors.white, 
                  fontWeight: FontWeight.bold
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                "Native C++ OpenCV Processing",
                style: TextStyle(color: Colors.white70),
              )
            ],
          ),
        ),
      ),
    );
  }
}
