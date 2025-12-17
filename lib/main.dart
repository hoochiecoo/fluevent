import 'dart:async';
import 'package:flutter/material.dart';
import 'package.flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
        scaffoldBackgroundColor: const Color(0xFFF0F2F5),
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
  String _detectionData = "Ожидание данных о линиях...";
  
  // Filter states
  bool _grayscaleEnabled = true;
  bool _blurEnabled = true;
  bool _cannyEnabled = true;

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if(mounted) setState(() => _detectionData = event.toString());
    }, onError: (e) {
      if(mounted) setState(() => _detectionData = "Stream Error: ${e.toString()}");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if (mounted) setState(() => _textureId = tid);
      _updateNativeSettings(); // Send initial settings
    } catch (e) {
      if (mounted) setState(() => _detectionData = "Start Error: ${e.toString()}");
    }
  }

  Future<void> _updateNativeSettings() async {
    try {
      await _methodChannel.invokeMethod('updateSettings', {
        'grayscale': _grayscaleEnabled,
        'blur': _blurEnabled,
        'canny': _cannyEnabled,
      });
    } catch (e) {
       if (mounted) setState(() => _detectionData = "Settings Error: ${e.toString()}");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("Детекция Линий"),
        elevation: 2,
        backgroundColor: Colors.white,
      ),
      body: Column(
        children: [
          AspectRatio(
            aspectRatio: 1.0,
            child: Container(
              color: Colors.black,
              child: _textureId == null 
                  ? const Center(child: CircularProgressIndicator())
                  : ClipRect(
                      child: FittedBox(
                        fit: BoxFit.cover,
                        child: SizedBox(
                          width: 480, 
                          height: 640,
                          child: Texture(textureId: _textureId!),
                        ),
                      ),
                    ),
            ),
          ),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            color: Colors.blueGrey[50],
            child: Text(
              _detectionData,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.blueGrey, fontWeight: FontWeight.bold),
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 10),
                  child: Text("Фильтры Обработки", style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                ),
                
                _buildSwitchTile(
                  title: "Оттенки серого",
                  subtitle: "Упрощает изображение для анализа яркости.",
                  icon: Icons.filter_b_and_w,
                  value: _grayscaleEnabled,
                  onChanged: (v) => setState(() {
                    _grayscaleEnabled = v;
                    _updateNativeSettings();
                  }),
                ),
                const SizedBox(height: 10),
                _buildSwitchTile(
                  title: "Гауссово размытие",
                  subtitle: "Удаляет цифровой шум и сглаживает детали.",
                  icon: Icons.blur_on,
                  value: _blurEnabled,
                  onChanged: (v) => setState(() {
                    _blurEnabled = v;
                    _updateNativeSettings();
                  }),
                ),
                const SizedBox(height: 10),
                _buildSwitchTile(
                  title: "Детектор границ Canny",
                  subtitle: "Основной шаг для выделения контуров линий.",
                  icon: Icons.border_style,
                  value: _cannyEnabled,
                  onChanged: (v) => setState(() {
                    _cannyEnabled = v;
                    _updateNativeSettings();
                  }),
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
        secondary: Icon(icon, color: Colors.blue),
        value: value,
        onChanged: onChanged,
        activeColor: Colors.blue,
      ),
    );
  }
}
