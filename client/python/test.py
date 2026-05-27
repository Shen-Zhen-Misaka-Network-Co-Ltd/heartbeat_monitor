from bleak import BleakScanner
import asyncio
def callback(s, d):
    if d and "Xiaomi" in str(d.local_name):
        print(f"{s.address}  name={d.local_name}  mfg={list(d.manufacturer_data.keys()) if d.manufacturer_data else None}")
async def main():
    s = BleakScanner(detection_callback=callback)
    await s.start()
    await asyncio.sleep(10)
    await s.stop()
asyncio.run(main())