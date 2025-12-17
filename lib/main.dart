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
        colorSchemeSeed: Colors.deepPurple,
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
  String _mlData = "Инициализация...";
  
  // State variables for our tunable parameters
  double _cannyThreshold1 = 85.0;
  double _cannyThreshold2 = 255.0;
  double _houghThreshold = 50.0;
  double _houghMinLineLength = 50.0;
  double _houghMaxLineGap = 10.0;
  double _blurKernelSize = 5.0;

  @override
  void initState() {
    super.initState();
    _startCamera();
    _eventChannel.receiveBroadcastStream().listen((event) {
      if(mounted) setState(() => _mlData = event.toString());
    }, onError: (e) {
      if(mounted) setState(() => _mlData = "Ошибка стрима: $e");
    });
  }

  Future<void> _startCamera() async {
    try {
      final tid = await _methodChannel.invokeMethod('startCamera');
      if(mounted) {
        setState(() => _textureId = tid);
        _updateNativeSettings(); // Send initial values
      }
    } catch (e) {
      if(mounted) setState(() => _mlData = "Ошибка старта: $e");
    }
  }

  Future<void> _updateNativeSettings() async {
    try {
      await _methodChannel.invokeMethod('updateSettings', {
        'cannyThreshold1': _cannyThreshold1,
        'cannyThreshold2': _cannyThreshold2,
        'houghThreshold': _houghThreshold.toInt(),
        'houghMinLineLength': _houghMinLineLength,
        'houghMaxLineGap': _houghMaxLineGap,
        'blurKernelSize': _blurKernelSize,
      });
    } catch (e) {
      if(mounted) setState(() => _mlData = "Ошибка настроек: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text("OpenCV Line Detector"),
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
                  ? const Center(child: CircularProgressIndicator(color: Colors.white))
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
            color: Colors.deepPurple[50],
            child: Text(
              _mlData, 
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.deepPurple, fontWeight: FontWeight.bold, fontSize: 16),
            ),
          ),

          const Divider(height: 1),

          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _buildSliderTile(
                  title: 'Canny: Нижний порог',
                  value: _cannyThreshold1,
                  min: 0, max: 255,
                  onChanged: (v) => setState(() => _cannyThreshold1 = v),
                ),
                _buildSliderTile(
                  title: 'Canny: Верхний порог',
                  value: _cannyThreshold2,
                  min: 0, max: 255,
                  onChanged: (v) => setState(() => _cannyThreshold2 = v),
                ),
                 _buildSliderTile(
                  title: 'Размер ядра размытия',
                  subtitle: 'Нечетное число',
                  value: _blurKernelSize,
                  min: 1, max: 21,
                  onChanged: (v) => setState(() => _blurKernelSize = v),
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8.0),
                  child: Divider(),
                ),
                _buildSliderTile(
                  title: 'Hough: Порог голосов',
                  value: _houghThreshold,
                  min: 1, max: 200,
                  onChanged: (v) => setState(() => _houghThreshold = v),
                ),
                _buildSliderTile(
                  title: 'Hough: Мин. длина линии',
                  value: _houghMinLineLength,
                  min: 1, max: 300,
                  onChanged: (v) => setState(() => _houghMinLineLength = v),
                ),
                _buildSliderTile(
                  title: 'Hough: Макс. разрыв',
                  value: _houghMaxLineGap,
                  min: 1, max: 100,
                  onChanged: (v) => setState(() => _houghMaxLineGap = v),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSliderTile({
    required String title,
    String? subtitle,
    required double value,
    required double min,
    required double max,
    required ValueChanged<double> onChanged,
  }) {
    return Card(
      elevation: 0.5,
      color: Colors.white,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10)
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
                    if(subtitle != null) Text(subtitle, style: TextStyle(fontSize: 12, color: Colors.grey[600])),
                  ],
                ),
                Text(value.toInt().toString(), style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: Colors.deepPurple)),
              ],
            ),
            Slider(
              value: value,
              min: min,
              max: max,
              divisions: (max - min).toInt(),
              label: value.toInt().toString(),
              onChanged: onChanged,
              onChangeEnd: (v) => _updateNativeSettings(), // Update native only when user releases slider
              activeColor: Colors.deepPurple,
            ),
          ],
        ),
      ),
    );
  }
}
