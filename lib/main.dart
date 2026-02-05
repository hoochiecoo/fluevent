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
      theme: ThemeData.dark().copyWith(primaryColor: Colors.orange),
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
  List<Map<String, dynamic>> _detections = [];
  String? _errorMsg;
  bool _isReady = false;
  List<String> _logs = [];
  int _inferenceTime = 0;
  int _frameCount = 0;
  bool _showDebug = true;

  @override
  void initState() {
    super.initState();
    _initApp();
  }

  Future<void> _initApp() async {
    try {
      _addLog('[Init] Starting application...');
      await _startCamera();
      _setupEventListener();
      _addLog('[Init] ✓ Application ready');
      setState(() => _isReady = true);
    } catch (e) {
      _addLog('[Init] ✗ Error: $e');
      setState(() => _errorMsg = 'Init failed: $e');
    }
  }

  void _setupEventListener() {
    _eventChannel.receiveBroadcastStream().listen(
      (event) {
        if (!mounted) return;
        try {
          final Map data = event as Map;
          
          if (data.containsKey('error')) {
            _addLog('[ERROR] ${data['error']}');
            setState(() => _errorMsg = data['error']);
          }
          
          setState(() {
            _detections = List<Map<String, dynamic>>.from(data['detections'] ?? []);
            _inferenceTime = data['inferenceTime'] ?? 0;
            _frameCount = data['frameCount'] ?? 0;
            
            if (data['logs'] != null) {
              _logs = List<String>.from(data['logs']);
            }
            
            if (data['detections'] != null && (data['detections'] as List).isNotEmpty) {
              _errorMsg = null;
            }
          });
        } catch (e) {
          _addLog('[Parse] ✗ Error: $e');
        }
      },
      onError: (error) {
        _addLog('[Stream] ✗ Error: $error');
      },
    );
  }

  void _addLog(String message) {
    setState(() {
      _logs.add('[${DateTime.now().millisecondsSinceEpoch % 100000}] $message');
      if (_logs.length > 100) _logs.removeAt(0);
    });
  }

  Future<void> _startCamera() async {
    try {
      _addLog('[Camera] Starting...');
      final tid = await _methodChannel.invokeMethod('startCamera');
      setState(() => _textureId = tid);
      _addLog('[Camera] ✓ Texture ID: $tid');
    } catch (e) {
      _addLog('[Camera] ✗ Failed: $e');
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_isReady) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 20),
              Text(
                _errorMsg ?? 'Initializing...',
                style: const TextStyle(color: Colors.orange),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text("TFLite Detector"),
        backgroundColor: Colors.black,
        elevation: 0,
        actions: [
          IconButton(
            icon: Icon(_showDebug ? Icons.bug_report : Icons.visibility),
            onPressed: () => setState(() => _showDebug = !_showDebug),
          ),
        ],
      ),
      body: _showDebug ? _buildDebugView() : _buildCameraView(),
    );
  }

  Widget _buildCameraView() {
    return Column(
      children: [
        Expanded(
          child: _textureId == null
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Icon(Icons.camera_alt, size: 48, color: Colors.orange),
                      const SizedBox(height: 16),
                      Text(
                        _errorMsg ?? 'Camera not ready',
                        style: const TextStyle(color: Colors.red),
                      ),
                    ],
                  ),
                )
              : Texture(textureId: _textureId!),
        ),
        Container(
          padding: const EdgeInsets.all(16),
          color: Colors.black87,
          width: double.infinity,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    "Detected: ${_detections.length}",
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: Colors.orange,
                    ),
                  ),
                  Text(
                    "${_inferenceTime}ms | Frame ${_frameCount}",
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              if (_errorMsg != null)
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.red.withOpacity(0.2),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    '⚠️ $_errorMsg',
                    style: const TextStyle(fontSize: 12, color: Colors.red),
                  ),
                )
              else if (_detections.isEmpty)
                const Text(
                  '◌ Scanning...',
                  style: TextStyle(fontSize: 14, color: Colors.grey),
                )
              else
                ..._detections.map((det) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    '✓ ${det['class']} - ${det['confidence']}%',
                    style: const TextStyle(fontSize: 14, color: Colors.lightGreen),
                  ),
                )).toList(),
            ],
          ),
        )
      ],
    );
  }

  Widget _buildDebugView() {
    return Column(
      children: [
        Expanded(
          child: _textureId == null
              ? Container(
                  color: Colors.black54,
                  child: const Center(
                    child: Text(
                      'Camera initializing...',
                      style: TextStyle(color: Colors.orange),
                    ),
                  ),
                )
              : Texture(textureId: _textureId!),
        ),
        Expanded(
          child: Container(
            color: Colors.black87,
            child: Column(
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  color: Colors.black,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Detections: ${_detections.length}',
                            style: const TextStyle(
                              color: Colors.orange,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          Text(
                            'Inference: ${_inferenceTime}ms',
                            style: const TextStyle(color: Colors.grey, fontSize: 12),
                          ),
                        ],
                      ),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            'Frame: $_frameCount',
                            style: const TextStyle(
                              color: Colors.lightGreen,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          if (_errorMsg != null)
                            Text(
                              'Error: $_errorMsg',
                              style: const TextStyle(color: Colors.red, fontSize: 12),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: ListView.builder(
                    reverse: true,
                    itemCount: _logs.length,
                    itemBuilder: (context, index) {
                      final log = _logs[_logs.length - 1 - index];
                      final isError = log.contains('✗');
                      final isSuccess = log.contains('✓');
                      final isWarning = log.contains('⚠');
                      
                      Color textColor = Colors.grey;
                      if (isError) textColor = Colors.red;
                      if (isSuccess) textColor = Colors.lightGreen;
                      if (isWarning) textColor = Colors.orange;
                      
                      return Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 4,
                        ),
                        child: Text(
                          log,
                          style: TextStyle(
                            fontSize: 11,
                            color: textColor,
                            fontFamily: 'monospace',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
