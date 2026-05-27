import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiService {
  final String baseUrl;

  ApiService(this.baseUrl);

  Future<bool> uploadHeartRate({
    required String name,
    required int heartRate,
    required String deviceModel,
  }) async {
    try {
      final url = baseUrl.endsWith('/')
          ? '${baseUrl}api/heartbeat'
          : '$baseUrl/api/heartbeat';
      final response = await http.post(
        Uri.parse(url),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'name': name,
          'heart_rate': heartRate,
          'device_model': deviceModel,
        }),
      );
      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }
}
