import asyncio

import aiohttp
from bleak import BleakClient, BleakScanner

from config import HEARTBEAT_INTERVAL, SERVER_URL

CLIENT_NAME = "MengXin"
TARGET_ADDRESS = "CC:07:D0:A1:4A:4C"

HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"


class HeartbeatClient:
    def __init__(self):
        self.name = CLIENT_NAME
        self.address = TARGET_ADDRESS.upper()
        self.server_url = SERVER_URL.rstrip("/")
        self.interval = HEARTBEAT_INTERVAL
        self.device_model = "Unknown"
        self.last_hr = None
        self.client = None

    async def upload(self, heart_rate: int):
        payload = {
            "name": self.name,
            "heart_rate": heart_rate,
            "device_model": self.device_model,
        }
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{self.server_url}/api/heartbeat",
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=5),
                ) as resp:
                    if resp.status != 200:
                        print(f"[!] 上传失败: {resp.status}")
        except Exception as e:
            print(f"[!] 连接服务器失败: {e}")

    def notification_handler(self, sender, data: bytearray):
        if not data:
            return
        print(f"收到数据: {data.hex()}")
        flags = data[0]
        if flags & 0x01:
            hr = int.from_bytes(data[1:3], "little")
        else:
            hr = data[1]
        self.last_hr = hr
        print(f"心率: {hr} BPM | 设备: {self.device_model}")

    async def run(self):
        print(f"客户端名称: {self.name}")
        print(f"目标设备: {self.address}")
        print(f"服务器: {self.server_url}")

        scanner = BleakScanner()
        await scanner.start()
        print("扫描中，等待目标设备出现...")

        device = None
        try:
            while device is None:
                await asyncio.sleep(1)
                for d in scanner.discovered_devices:
                    if d.address.upper() == self.address:
                        device = d
                        break

            await scanner.stop()
            self.device_model = device.name or "Unknown"
            print(f"发现设备: {self.device_model} ({device.address})，正在连接...")

            async with BleakClient(device, timeout=15.0, address_type="random") as self.client:
                print(f"已连接: {device.address}")

                def handler(sender, data):
                    self.notification_handler(sender, data)

                await self.client.start_notify(HR_MEASUREMENT_UUID, handler)
                print("已订阅心率通知，等待数据...")

                while True:
                    await asyncio.sleep(self.interval)
                    if self.last_hr is not None:
                        await self.upload(self.last_hr)

        except asyncio.CancelledError:
            pass
        finally:
            if self.client and self.client.is_connected:
                await self.client.stop_notify(HR_MEASUREMENT_UUID)
                await self.client.disconnect()
            print("已断开连接。")


def main():
    client = HeartbeatClient()
    try:
        asyncio.run(client.run())
    except KeyboardInterrupt:
        print("\n用户中断")


if __name__ == "__main__":
    main()
