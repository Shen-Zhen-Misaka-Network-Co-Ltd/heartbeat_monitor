import json
from datetime import datetime, timedelta

from aiohttp import web, WSMsgType

clients: dict[str, dict] = {}
history: dict[str, list[dict]] = {}
ws_clients: set[web.WebSocketResponse] = set()
HR_NORMAL_MIN = 50
HR_NORMAL_MAX = 100

PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>心率监控面板</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.7/dist/chart.umd.min.js"></script>
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
.card { cursor: pointer; }
.client-widget { break-inside: avoid; }
.chart-wrap { background: rgba(30, 41, 59, .5); border-radius: 0 0 16px 16px; padding: 16px 24px 24px; margin-top: -1px; border: 1px solid rgba(255,255,255,.06); border-top: none; }
.chart-inner { position: relative; height: 200px; }
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
  var safeName = (name || '').replace(/"/g, '&quot;');
  return '<div class="card hr-' + zone + '" data-name="' + safeName + '">'
    + '<span class="status-dot ' + status + '"></span>'
    + '<span class="hr-bg">' + hr + '</span>'
    + '<div class="name">' + safeName + '</div>'
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

  var widgets = {};
  Array.from(grid.children).forEach(function(el) {
    if (el.classList.contains('client-widget'))
      widgets[el.dataset.name] = el;
  });

  keys.forEach(function(name) {
    var c = data[name];
    if (c.last_seen && (now - new Date(c.last_seen).getTime()) / 1000 < 10) online++;
    var html = buildCard(name, c);
    if (widgets[name]) {
      var oldCard = widgets[name].querySelector('.card');
      if (oldCard) oldCard.outerHTML = html;
      delete widgets[name];
    } else {
      var w = document.createElement('div');
      w.className = 'client-widget';
      w.dataset.name = name;
      w.innerHTML = html;
      grid.appendChild(w);
    }
  });

  Object.keys(widgets).forEach(function(name) {
    closeChart(name);
    widgets[name].remove();
  });
  document.getElementById('count').textContent = online + ' 人在线';
}

var chartInstances = {};
var historyCache = {};

function fmtTime(iso) {
  if (!iso || iso.length < 16) return iso;
  return iso.slice(5, 16);
}

function renderChart(name, records) {
  var safeName = name.replace(/[^a-zA-Z0-9_]/g, '_');
  var ctx = document.getElementById('canvas-' + safeName);
  if (!ctx) return;
  var labels = records.map(function(r) { return fmtTime(r.last_seen); });
  var values = records.map(function(r) { return r.heart_rate || 0; });
  chartInstances[name] = new Chart(ctx.getContext('2d'), {
    type: 'line',
    data: {
      labels: labels,
      datasets: [{
        label: '心率 (BPM)',
        data: values,
        borderColor: '#22c55e',
        backgroundColor: 'rgba(34,197,94,0.1)',
        borderWidth: 2,
        tension: 0.3,
        fill: true,
        pointRadius: 3,
        pointBackgroundColor: '#22c55e'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: { legend: { display: false } },
      scales: {
        x: {
          ticks: { color: '#94a3b8', maxTicksLimit: 10, font: { size: 10 } },
          grid: { color: 'rgba(255,255,255,0.05)' }
        },
        y: {
          ticks: { color: '#94a3b8', font: { size: 10 } },
          grid: { color: 'rgba(255,255,255,0.08)' },
          beginAtZero: false
        }
      }
    }
  });
}

function fetchChart(name) {
  if (historyCache[name]) {
    renderChart(name, historyCache[name]);
    return;
  }
  fetch('/api/history?name=' + encodeURIComponent(name))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      historyCache[name] = data.history || [];
      renderChart(name, historyCache[name]);
    });
}

function openChart(name, card) {
  var safeName = name.replace(/[^a-zA-Z0-9_]/g, '_');
  var wrap = document.createElement('div');
  wrap.className = 'chart-wrap';
  wrap.id = 'chart-' + safeName;
  wrap.innerHTML = '<div class="chart-inner"><canvas id="canvas-' + safeName + '"></canvas></div>';
  card.parentNode.appendChild(wrap);
  fetchChart(name);
}

function closeChart(name) {
  if (chartInstances[name]) {
    chartInstances[name].destroy();
    delete chartInstances[name];
  }
  var wrap = document.getElementById('chart-' + name.replace(/[^a-zA-Z0-9_]/g, '_'));
  if (wrap) wrap.remove();
}

document.getElementById('grid').addEventListener('click', function(e) {
  var card = e.target.closest('.card');
  if (!card) return;
  var name = card.getAttribute('data-name');
  if (!name) return;
  var wrapId = 'chart-' + name.replace(/[^a-zA-Z0-9_]/g, '_');
  if (document.getElementById(wrapId)) {
    closeChart(name);
  } else {
    Object.keys(chartInstances).forEach(function(n) { closeChart(n); });
    openChart(name, card);
  }
});

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

    now = datetime.now()
    record = {
        "heart_rate": data.get("heart_rate"),
        "device_model": data.get("device_model", "Unknown"),
        "last_seen": now.isoformat(),
    }
    clients[name] = record

    if name not in history:
        history[name] = []
    history[name].append(record)
    cutoff = now - timedelta(days=7)
    history[name] = [r for r in history[name] if datetime.fromisoformat(r["last_seen"]) >= cutoff]

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


async def handle_history(request: web.Request) -> web.Response:
    name = request.query.get("name")
    if not name:
        return web.json_response({"status": "error", "message": "name required"}, status=400)
    records = history.get(name, [])

    MAX_POINTS = 200
    if len(records) > MAX_POINTS:
        step = len(records) / MAX_POINTS
        sampled = []
        for i in range(MAX_POINTS):
            idx = int(i * step)
            if idx < len(records):
                sampled.append(records[idx])
        records = sampled

    return web.json_response({"history": records})


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
    app.router.add_get("/api/history", handle_history)
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/", handle_dashboard)

    print(f"服务器启动: http://{HOST}:{PORT}")
    web.run_app(app, host=HOST, port=PORT)


if __name__ == "__main__":
    main()
