import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import '../config.dart';
import '../services/api_service.dart';
import '../services/ble_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

enum AppState { initial, scanning, connecting, monitoring }

class _HomeScreenState extends State<HomeScreen> {
  final BleService _ble = BleService();
  late ApiService _api;
  AppState _state = AppState.initial;
  List<ScanResult> _devices = [];
  int? _currentHr;
  bool _uploadOk = false;
  Timer? _uploadTimer;
  String _deviceName = '';
  String _statusText = '';
  StreamSubscription<List<ScanResult>>? _scanSub;
  StreamSubscription<BleState>? _adapterSub;
  BleState _bleState = BleState.unknown;

  @override
  void initState() {
    super.initState();
    _api = ApiService(AppConfig.serverUrl);
    _adapterSub = _ble.adapterState.listen((state) {
      if (!mounted) return;
      debugPrint('BLE state: $state');
      setState(() => _bleState = state);
    });
  }

  @override
  void dispose() {
    _uploadTimer?.cancel();
    _scanSub?.cancel();
    _adapterSub?.cancel();
    _ble.dispose();
    super.dispose();
  }

  String _bleStateText() {
    switch (_bleState) {
      case BleState.on:
        return '蓝牙已开启';
      case BleState.off:
        return '蓝牙已关闭';
      case BleState.turningOn:
        return '正在打开蓝牙...';
      case BleState.unavailable:
        return '蓝牙不可用';
      case BleState.unauthorized:
        return '蓝牙权限被拒绝';
      case BleState.unknown:
        return '检查蓝牙状态...';
    }
  }

  Future<void> _startScan() async {
    setState(() {
      _state = AppState.scanning;
      _devices = [];
      _statusText = '准备扫描...';
    });

    try {
      if (!await _ble.isBluetoothOn()) {
        setState(() => _statusText = '蓝牙未开启，尝试打开...');
        await FlutterBluePlus.turnOn();
      }

      setState(() => _statusText = '扫描中...');

      _scanSub?.cancel();
      _scanSub = _ble.scanResults.listen(
        (results) {
          if (!mounted) return;
          setState(() {
            _devices = results;
            _statusText = '已发现 ${results.length} 个设备';
          });
        },
        onError: (e) {
          if (!mounted) return;
          setState(() {
            _statusText = '扫描出错: $e';
            _state = AppState.initial;
          });
        },
      );

      await _ble.startScan();

      Future.delayed(const Duration(seconds: 17), () {
        if (_state == AppState.scanning) {
          _ble.stopScan();
          if (mounted) {
            setState(() {
              if (_devices.isEmpty) {
                _statusText = '未发现设备\n请确保:\n1. 设备在附近并处于配对模式\n2. 手机位置服务已开启\n3. 已授予蓝牙权限';
              }
            });
          }
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _state = AppState.initial;
        _statusText = '扫描失败: $e';
      });
    }
  }

  Future<void> _connect(BluetoothDevice device) async {
    setState(() {
      _state = AppState.connecting;
      _statusText = '连接中...';
    });
    try {
      _deviceName = await _ble.connect(device);
      _scanSub?.cancel();

      _ble.heartRateStream.listen((hr) {
        if (!mounted) return;
        setState(() => _currentHr = hr);
      });

      _ble.connectionState.listen((connected) {
        if (!mounted) return;
        if (!connected) {
          _uploadTimer?.cancel();
          setState(() {
            _state = AppState.initial;
            _statusText = '已断开连接';
          });
        }
      });

      setState(() {
        _state = AppState.monitoring;
        _statusText = '已连接';
      });
      _startUploadLoop();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _state = AppState.initial;
        _statusText = '连接失败: $e';
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('连接失败: $e')),
      );
    }
  }

  void _startUploadLoop() {
    _uploadTimer?.cancel();
    _uploadTimer = Timer.periodic(
      Duration(milliseconds: (AppConfig.uploadIntervalSeconds * 1000).round()),
      (_) async {
        if (_currentHr == null) return;
        final ok = await _api.uploadHeartRate(
          name: AppConfig.clientName,
          heartRate: _currentHr!,
          deviceModel: _deviceName,
        );
        if (!mounted) return;
        setState(() => _uploadOk = ok);
      },
    );
  }

  Future<void> _disconnect() async {
    _uploadTimer?.cancel();
    await _ble.disconnect();
    setState(() {
      _state = AppState.initial;
      _currentHr = null;
      _statusText = '';
    });
  }

  void _showSettings() {
    final urlCtrl = TextEditingController(text: AppConfig.serverUrl);
    final nameCtrl = TextEditingController(text: AppConfig.clientName);

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('设置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: urlCtrl,
              decoration: const InputDecoration(
                labelText: '服务器地址',
                hintText: 'http://192.168.1.100:8000',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: nameCtrl,
              decoration: const InputDecoration(
                labelText: '客户端名称',
                hintText: 'MengXin',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () {
              AppConfig.serverUrl = urlCtrl.text.trim();
              AppConfig.clientName = nameCtrl.text.trim();
              _api = ApiService(AppConfig.serverUrl);
              Navigator.pop(ctx);
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('心率监测'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: _state == AppState.initial ? _showSettings : null,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    switch (_state) {
      case AppState.initial:
        return _buildInitial();
      case AppState.scanning:
        return _buildScanning();
      case AppState.connecting:
        return const Center(child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('连接中...', style: TextStyle(fontSize: 18)),
          ],
        ));
      case AppState.monitoring:
        return _buildMonitoring();
    }
  }

  Widget _buildInitial() {
    final isOn = _bleState == BleState.on;
    return Center(child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.favorite_border, size: 80,
            color: isOn ? Colors.grey[400] : Colors.orange),
        const SizedBox(height: 24),
        Text(
          '客户端: ${AppConfig.clientName}',
          style: const TextStyle(fontSize: 18),
        ),
        const SizedBox(height: 8),
        Text(AppConfig.serverUrl,
            style: TextStyle(color: Colors.grey[600], fontSize: 13)),
        const SizedBox(height: 8),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.bluetooth, size: 16,
                color: isOn ? Colors.blue : Colors.red),
            const SizedBox(width: 4),
            Text(
              _bleStateText(),
              style: TextStyle(fontSize: 13, color: isOn ? Colors.blue : Colors.red),
            ),
          ],
        ),
        if (_statusText.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(_statusText, style: TextStyle(color: Colors.grey[600], fontSize: 13)),
        ],
        const SizedBox(height: 32),
        FilledButton.icon(
          onPressed: _startScan,
          icon: const Icon(Icons.bluetooth_searching),
          label: const Text('扫描设备'),
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
          ),
        ),
      ],
    ));
  }

  Widget _buildScanning() {
    return Column(
      children: [
        const LinearProgressIndicator(),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              const Text('扫描中...', style: TextStyle(fontSize: 16)),
              const Spacer(),
              Text(_statusText),
            ],
          ),
        ),
        Expanded(
          child: _devices.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(32),
                    child: Text(
                      _statusText,
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.grey[600], fontSize: 15),
                    ),
                  ),
                )
              : ListView.builder(
                  itemCount: _devices.length,
                  itemBuilder: (ctx, i) {
                    final d = _devices[i];
                    final name = d.device.platformName.isNotEmpty
                        ? d.device.platformName
                        : '未知设备';
                    final addr = d.device.remoteId.toString();
                    return ListTile(
                      leading: const Icon(Icons.bluetooth),
                      title: Text(name),
                      subtitle: Text(addr),
                      trailing: Text('${d.rssi} dBm'),
                      onTap: () => _connect(d.device),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildMonitoring() {
    final hr = _currentHr;
    final bgColor = hr == null
        ? Colors.grey
        : hr < 50
            ? Colors.blue
            : hr > 100
                ? Colors.red
                : Colors.green;

    return Center(child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.bluetooth_connected, color: Colors.blue[400], size: 28),
        const SizedBox(height: 8),
        Text(_deviceName, style: const TextStyle(fontSize: 16)),
        const SizedBox(height: 24),
        AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          width: 200,
          height: 200,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: bgColor.withAlpha(50),
            border: Border.all(color: bgColor, width: 4),
          ),
          child: Center(
            child: hr == null
                ? const Text('--', style: TextStyle(fontSize: 64))
                : Text('$hr',
                    style: const TextStyle(
                        fontSize: 64, fontWeight: FontWeight.bold)),
          ),
        ),
        const SizedBox(height: 8),
        const Text('BPM', style: TextStyle(fontSize: 18, color: Colors.grey)),
        const SizedBox(height: 32),
        _uploadOk
            ? const Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(Icons.cloud_done, color: Colors.green, size: 20),
                SizedBox(width: 6),
                Text('上传正常', style: TextStyle(color: Colors.green)),
              ])
            : const Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(Icons.cloud_off, color: Colors.orange, size: 20),
                SizedBox(width: 6),
                Text('等待上传', style: TextStyle(color: Colors.orange)),
              ]),
        const SizedBox(height: 32),
        OutlinedButton.icon(
          onPressed: _disconnect,
          icon: const Icon(Icons.link_off),
          label: const Text('断开连接'),
          style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
        ),
      ],
    ));
  }
}
