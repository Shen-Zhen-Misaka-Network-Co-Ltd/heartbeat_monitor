# Heartbeat Monitor

实时心率监测系统，通过 BLE 连接心率设备，上传数据并在 Web 面板展示。

## 结构

```
heartbeat_monitor/
├── server/              # HTTP/WS 后端，接收数据并推送到 Web 面板
│   └── server.py
├── client/
│   ├── python/          # Python BLE 采集客户端
│   │   ├── client.py    # 连接心率设备并上传
│   │   └── config.py    # 服务器地址与采集间隔配置
│   └── flutter/         # Flutter 移动端
│       └── lib/         # BLE 扫描/连接 + 心率上传
├── requirements.txt     # Python 依赖
└── .gitignore
```

## 使用

### 启动服务端

```bash
pip install -r requirements.txt
python server/server.py
```

### BLE 采集 (Python)

编辑 `client/python/config.py` 配置服务器地址，然后：

```bash
python client/python/client.py
```

### Flutter 移动端

```bash
cd client/flutter
flutter run
```

## 协议

MIT
