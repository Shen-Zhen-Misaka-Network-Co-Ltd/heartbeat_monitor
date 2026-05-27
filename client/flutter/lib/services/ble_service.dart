import 'dart:async';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import '../config.dart';

enum BleState { unavailable, off, turningOn, on, unauthorized, unknown }

class BleService {
  BluetoothDevice? _device;
  StreamSubscription<List<int>>? _notificationSubscription;

  final StreamController<int> _heartRateController =
      StreamController<int>.broadcast();
  final StreamController<bool> _connectionController =
      StreamController<bool>.broadcast();

  Stream<int> get heartRateStream => _heartRateController.stream;
  Stream<bool> get connectionState => _connectionController.stream;

  String _deviceName = '';
  String get deviceName => _deviceName;
  bool get isConnected => _device != null;

  Stream<BleState> get adapterState =>
      FlutterBluePlus.adapterState.map((s) {
        switch (s) {
          case BluetoothAdapterState.on:
            return BleState.on;
          case BluetoothAdapterState.off:
            return BleState.off;
          case BluetoothAdapterState.turningOn:
            return BleState.turningOn;
          case BluetoothAdapterState.unavailable:
            return BleState.unavailable;
          case BluetoothAdapterState.unauthorized:
            return BleState.unauthorized;
          default:
            return BleState.unknown;
        }
      });

  Future<bool> isBluetoothOn() => FlutterBluePlus.isOn;

  Future<void> startScan() async {
    await FlutterBluePlus.startScan(
      timeout: const Duration(seconds: 15),
      androidUsesFineLocation: true,
      androidScanMode: AndroidScanMode.lowLatency,
    );
  }

  Stream<List<ScanResult>> get scanResults {
    return FlutterBluePlus.scanResults;
  }

  Future<void> stopScan() async {
    await FlutterBluePlus.stopScan();
  }

  Future<String> connect(BluetoothDevice device) async {
    _device = device;
    await device.connect();
    _connectionController.add(true);

    await device.discoverServices();
    _deviceName = device.platformName;

    final targetService = Guid(AppConfig.hrServiceUuid);
    final targetChar = Guid(AppConfig.hrMeasurementUuid);
    final services = device.servicesList;
    for (final service in services) {
      if (service.uuid == targetService) {
        for (final char in service.characteristics) {
          if (char.uuid == targetChar) {
            await char.setNotifyValue(true);
            _notificationSubscription = char.onValueReceived.listen(
              _parseHeartRate,
            );
            return device.platformName;
          }
        }
      }
    }
    throw Exception('心率服务未找到');
  }

  void _parseHeartRate(List<int> data) {
    if (data.isEmpty) return;
    final flags = data[0];
    final int hr;
    if (flags & 0x01 != 0) {
      hr = (data[2] << 8) | data[1];
    } else {
      hr = data[1];
    }
    _heartRateController.add(hr);
  }

  Future<void> disconnect() async {
    await _notificationSubscription?.cancel();
    _notificationSubscription = null;
    try {
      await _device?.disconnect();
    } catch (_) {}
    _device = null;
    _connectionController.add(false);
  }

  void dispose() {
    _heartRateController.close();
    _connectionController.close();
    _notificationSubscription?.cancel();
  }
}
