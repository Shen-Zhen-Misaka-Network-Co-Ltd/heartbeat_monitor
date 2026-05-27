import json
from datetime import datetime

from aiohttp import web, WSMsgType

clients: dict[str, dict] = {}
ws_clients: set[web.WebSocketResponse] = set()
HR_NORMAL_MIN = 50
HR_NORMAL_MAX = 100

PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>心率监控面板</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; color: #e2e8f0; min-height: 100vh; position: relative; background: #0f172a; background-size: cover; background-position: center; background-attachment: fixed; }
body::before { content: ''; position: fixed; inset: 0; background: rgba(10, 15, 30, .55); z-index: 0; }
.header { position: relative; z-index: 1; padding: 24px 32px; border-bottom: 1px solid rgba(255,255,255,.06); display: flex; align-items: center; justify-content: space-between; backdrop-filter: blur(8px); }
.header h1 { font-size: 22px; color: #f1f5f9; text-shadow: 0 2px 8px rgba(0,0,0,.3); }
.header .count { color: #94a3b8; font-size: 14px; }
.grid { position: relative; z-index: 1; display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; padding: 24px 32px; }
.card { background: rgba(30, 41, 59, .65); backdrop-filter: blur(12px); border-radius: 16px; padding: 24px; position: relative; overflow: hidden; transition: transform .15s, box-shadow .15s; border: 1px solid rgba(255,255,255,.06); }
.card:hover { transform: translateY(-2px); box-shadow: 0 8px 32px rgba(0,0,0,.4); }
.card .status-dot { width: 10px; height: 10px; border-radius: 50%; position: absolute; top: 16px; right: 16px; }
.card .status-dot.online { background: #22c55e; box-shadow: 0 0 10px rgba(34,197,94,.5); }
.card .status-dot.offline { background: #475569; }
.card .name { font-size: 15px; font-weight: 600; color: #cbd5e1; margin-bottom: 16px; text-shadow: 0 1px 4px rgba(0,0,0,.2); }
.card .hr-value { font-size: 56px; font-weight: 800; line-height: 1; margin-bottom: 4px; text-shadow: 0 2px 12px rgba(0,0,0,.3); }
.card .hr-label { font-size: 13px; color: #94a3b8; margin-bottom: 16px; }
.card .meta { display: flex; justify-content: space-between; font-size: 12px; color: #475569; border-top: 1px solid rgba(255,255,255,.06); padding-top: 12px; }
.card .meta .model { color: #a78bfa; }
.card .meta .time { color: #64748b; }
.card .hr-bg { position: absolute; font-size: 140px; font-weight: 900; color: rgba(255,255,255,.04); line-height: 1; top: -10px; right: 10px; user-select: none; pointer-events: none; }
.hr-low .hr-value { color: #38bdf8; }
.hr-normal .hr-value { color: #22c55e; }
.hr-high .hr-value { color: #f43f5e; }
.empty { grid-column: 1 / -1; text-align: center; padding: 80px 20px; color: #475569; font-size: 15px; }
</style>
</head>
<body>
<div class="header">
<h1>&#x2764;&#xFE0F; 心率监控</h1>
<span class="count" id="count">0 人在线</span>
</div>
<div class="grid" id="grid"></div>
<script>
var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
var ws = new WebSocket(proto + '//' + location.host + '/ws');

var bg = new Image();
bg.onload = function() { document.body.style.backgroundImage = 'url(' + bg.src + ')'; };
bg.src = '{bg_api}?_=' + Date.now();

function hrZone(r) {
  if (r == null) return 'normal';
  var v = parseInt(r, 10);
  if (v < 50) return 'low';
  if (v > 100) return 'high';
  return 'normal';
}

function buildCard(name, c) {
  var zone = hrZone(c.heart_rate);
  var status = 'offline';
  if (c.last_seen) {
    var diff = (Date.now() - new Date(c.last_seen).getTime()) / 1000;
    if (diff < 10) status = 'online';
  }
  var hr = c.heart_rate || '&mdash;';
  var model = c.device_model || 'Unknown';
  var time = c.last_seen ? c.last_seen.slice(11, 19) : '&mdash;';
  return '<div class="card hr-' + zone + '">'
    + '<span class="status-dot ' + status + '"></span>'
    + '<span class="hr-bg">' + hr + '</span>'
    + '<div class="name">' + name + '</div>'
    + '<div class="hr-value">' + hr + '</div>'
    + '<div class="hr-label">BPM</div>'
    + '<div class="meta">'
    + '<span class="model">' + model + '</span>'
    + '<span class="time">' + time + '</span>'
    + '</div></div>';
}

function render(data) {
  var grid = document.getElementById('grid');
  var keys = Object.keys(data);
  if (keys.length === 0) {
    grid.innerHTML = '<div class="empty">暂无客户端连接</div>';
    document.getElementById('count').textContent = '0 人在线';
    return;
  }
  var online = 0;
  var now = Date.now();
  var cards = keys.map(function(name) {
    var c = data[name];
    if (c.last_seen && (now - new Date(c.last_seen).getTime()) / 1000 < 10) online++;
    return buildCard(name, c);
  });
  grid.innerHTML = cards.join('');
  document.getElementById('count').textContent = online + ' 人在线';
}

ws.onmessage = function(e) {
  render(JSON.parse(e.data));
};
ws.onclose = function() {
  setTimeout(function() {
    location.reload();
  }, 3000);
};
</script>
</body>
</html>"""


def is_online(last_seen: str) -> bool:
    if not last_seen:
        return False
    t = datetime.fromisoformat(last_seen)
    return (datetime.now() - t).total_seconds() < 10


async def broadcast(data: dict):
    msg = json.dumps(data, ensure_ascii=False)
    dead = set()
    for ws in ws_clients:
        try:
            await ws.send_str(msg)
        except Exception:
            dead.add(ws)
    ws_clients.difference_update(dead)


async def handle_heartbeat(request: web.Request) -> web.Response:
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"status": "error", "message": "invalid JSON"}, status=400)

    name = data.get("name")
    if not name:
        return web.json_response({"status": "error", "message": "name required"}, status=400)

    clients[name] = {
        "heart_rate": data.get("heart_rate"),
        "device_model": data.get("device_model", "Unknown"),
        "last_seen": datetime.now().isoformat(),
    }
    await broadcast(clients)
    return web.json_response({"status": "ok"})


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    ws_clients.add(ws)

    try:
        await ws.send_str(json.dumps(clients, ensure_ascii=False))
        async for msg in ws:
            if msg.type == WSMsgType.ERROR:
                break
    finally:
        ws_clients.discard(ws)
    return ws


async def handle_dashboard(request: web.Request) -> web.Response:
    ua = request.headers.get("User-Agent", "").lower()
    is_mobile = any(kw in ua for kw in ("mobile", "android", "iphone", "ipad"))
    bg_api = "https://www.loliapi.com/acg/pe/" if is_mobile else "https://www.loliapi.com/acg/"
    return web.Response(text=PAGE.replace("{bg_api}", bg_api), content_type="text/html")


def main():
    HOST = "0.0.0.0"
    PORT = 8000

    app = web.Application()
    app.router.add_post("/api/heartbeat", handle_heartbeat)
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/", handle_dashboard)

    print(f"服务器启动: http://{HOST}:{PORT}")
    web.run_app(app, host=HOST, port=PORT)


if __name__ == "__main__":
    main()
