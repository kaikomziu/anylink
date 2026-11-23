// AnyLink シグナリングサーバー
// 役割: 端末IDの発行・オンライン管理、接続要求の取り次ぎ、WebRTC の SDP / ICE 中継。
// 画面映像や操作データはここを通らない（WebRTC の P2P で直接やり取りする）。
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT) || 8787;
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'devices.json');
const PROTOCOL_VERSION = 1;

// ---- 端末ID ⇔ 秘密鍵ハッシュの永続化（IDの乗っ取り防止） ----
let devices = {};
try {
  devices = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
} catch {
  devices = {};
}
let saveTimer = null;
function saveDevices() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.writeFile(DATA_FILE, JSON.stringify(devices), () => {});
  }, 500);
}

const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

function newDeviceId() {
  // 9桁の数字ID（先頭は0以外）
  for (;;) {
    const id = String(crypto.randomInt(100000000, 1000000000));
    if (!devices[id]) return id;
  }
}

// ---- オンライン端末とセッション ----
const online = new Map(); // id -> ws
const sessions = new Map(); // sessionId -> { controller, host, state: 'pending' | 'active', createdAt }

function send(ws, msg) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function endSession(sessionId, by, reason) {
  const s = sessions.get(sessionId);
  if (!s) return;
  sessions.delete(sessionId);
  for (const id of [s.controller, s.host]) {
    if (id !== by) send(online.get(id), { type: 'session_ended', sessionId, reason });
  }
}

// 総当たりでIDを探されないよう、接続要求に回数制限をかける
function rateLimited(ws) {
  const now = Date.now();
  ws.reqTimes = (ws.reqTimes || []).filter((t) => now - t < 60_000);
  if (ws.reqTimes.length >= 10) return true;
  ws.reqTimes.push(now);
  return false;
}

// ---- HTTP（ヘルスチェック用） ----
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end(`AnyLink signaling OK (online: ${online.size})\n`);
});

const wss = new WebSocketServer({ server, maxPayload: 256 * 1024 });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.deviceId = null;
  ws.on('pong', () => (ws.isAlive = true));

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (!msg || typeof msg.type !== 'string') return;

    // 登録前に許可するのは register のみ
    if (msg.type !== 'register' && !ws.deviceId) {
      send(ws, { type: 'error', code: 'not_registered' });
      return;
    }

    switch (msg.type) {
      case 'register': {
        const secret = typeof msg.secret === 'string' ? msg.secret : '';
        if (secret.length < 16) {
          send(ws, { type: 'error', code: 'bad_secret' });
          return;
        }
        let id = typeof msg.id === 'string' && /^\d{9}$/.test(msg.id) ? msg.id : null;
        const hash = sha256(secret);
        if (id && devices[id] && devices[id].hash !== hash) id = null; // 他人のID → 新規発行
        if (!id) id = newDeviceId();
        devices[id] = { hash, lastSeen: Date.now() };
        saveDevices();

        // 同じIDの古い接続は追い出す
        const old = online.get(id);
        if (old && old !== ws) {
          send(old, { type: 'error', code: 'replaced' });
          old.close();
        }
        ws.deviceId = id;
        ws.deviceName = String(msg.name || '').slice(0, 64) || '不明な端末';
        ws.platform = String(msg.platform || '').slice(0, 16);
        online.set(id, ws);
        send(ws, { type: 'registered', id, protocol: PROTOCOL_VERSION });
        return;
      }

      case 'connect_request': {
        const to = String(msg.to || '').replace(/\D/g, '');
        if (rateLimited(ws)) {
          send(ws, { type: 'connect_result', ok: false, code: 'rate_limited' });
          return;
        }
        if (to === ws.deviceId) {
          send(ws, { type: 'connect_result', ok: false, code: 'self' });
          return;
        }
        const target = online.get(to);
        if (!target) {
          send(ws, { type: 'connect_result', ok: false, code: 'offline' });
          return;
        }
        const sessionId = crypto.randomUUID();
        sessions.set(sessionId, { controller: ws.deviceId, host: to, state: 'pending', createdAt: Date.now() });
        send(ws, { type: 'connect_pending', sessionId, to });
        send(target, {
          type: 'incoming',
          sessionId,
          from: ws.deviceId,
          fromName: ws.deviceName,
          fromPlatform: ws.platform,
          requested: msg.requested || null,
        });
        return;
      }

      case 'connect_response': {
        // 操作される側（host）からの許可・拒否
        const s = sessions.get(msg.sessionId);
        if (!s || s.host !== ws.deviceId || s.state !== 'pending') return;
        const controllerWs = online.get(s.controller);
        if (!msg.accepted) {
          sessions.delete(msg.sessionId);
          send(controllerWs, { type: 'connect_result', ok: false, code: 'rejected', sessionId: msg.sessionId });
          return;
        }
        s.state = 'active';
        const p = msg.permissions || {};
        const permissions = { view: true, control: !!p.control, keyboard: !!p.keyboard, clipboard: !!p.clipboard };
        send(controllerWs, {
          type: 'connect_result',
          ok: true,
          sessionId: msg.sessionId,
          hostId: s.host,
          hostName: ws.deviceName,
          hostPlatform: ws.platform,
          permissions,
        });
        return;
      }

      case 'cancel_request': {
        const s = sessions.get(msg.sessionId);
        if (s && s.controller === ws.deviceId) endSession(msg.sessionId, ws.deviceId, 'cancelled');
        return;
      }

      case 'signal': {
        // アクティブなセッションの当事者同士でのみ中継する
        const s = sessions.get(msg.sessionId);
        if (!s || s.state !== 'active') return;
        let to = null;
        if (s.controller === ws.deviceId) to = s.host;
        else if (s.host === ws.deviceId) to = s.controller;
        if (!to) return;
        send(online.get(to), { type: 'signal', sessionId: msg.sessionId, from: ws.deviceId, data: msg.data });
        return;
      }

      case 'end': {
        const s = sessions.get(msg.sessionId);
        if (s && (s.controller === ws.deviceId || s.host === ws.deviceId)) {
          endSession(msg.sessionId, ws.deviceId, msg.reason || 'ended');
        }
        return;
      }

      case 'ping':
        send(ws, { type: 'pong' });
        return;
    }
  });

  ws.on('close', () => {
    const id = ws.deviceId;
    if (!id) return;
    if (online.get(id) === ws) {
      online.delete(id);
      for (const [sid, s] of sessions) {
        if (s.controller === id || s.host === id) endSession(sid, id, 'peer_offline');
      }
    }
  });
});

// 死んだ接続の掃除と、放置された保留セッションの破棄
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
  const now = Date.now();
  for (const [sid, s] of sessions) {
    if (s.state === 'pending' && now - s.createdAt > 60_000) endSession(sid, null, 'timeout');
  }
}, 25_000);

server.listen(PORT, () => {
  console.log(`AnyLink シグナリングサーバー起動: ws://0.0.0.0:${PORT}`);
});
