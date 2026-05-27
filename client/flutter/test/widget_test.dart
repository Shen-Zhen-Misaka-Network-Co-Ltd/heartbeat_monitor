import 'package:flutter_test/flutter_test.dart';
import 'package:heartbeat_monitor_app/main.dart';

void main() {
  testWidgets('App renders home screen', (WidgetTester tester) async {
    await tester.pumpWidget(const HeartbeatApp());
    expect(find.text('心率监测'), findsOneWidget);
  });
}
