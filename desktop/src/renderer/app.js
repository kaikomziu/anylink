// AnyLink PC版 レンダラー
// シグナリング（WebSocket）と WebRTC（P2P）で、操作する側・される側の両方を担当する。
'use strict';

const api = window.anylink;
const $ = (id) => document.getElementById(id);

const ERR = {
  offline: '相手はオフラインか、IDが間違っています',
  rejected: '相手が接続を拒否しました',
  rate_limited: '接続要求が多すぎます。少し待ってから試してください',
  self: '自分自身には接続できません',
  timeout: '相手が応答しませんでした',
  cancelled: '接続要求が取り消されました',
  peer_offline: '相手との接続が切れました',
  ended: '相手が切断しました',
  ice_failed: 'P2P接続に失敗しました（TURNサーバーの設定が必要な場合があります）',
};

let config = null;
let state = { mode: 'idle' }; // idle | requesting | consent | controller | host
let pc = null;
let dc = null;
let localStream = null;
let pendingCandidates = [];
let statsTimer = null;

// ---------- 共通ユーティリティ ----------
const formatId = (id) => (id ? String(id).replace(/(\d{3})(?=\d)/g, '$1 ') : '--- --- ---');
const normId = (s) => String(s || '').replace(/\D/g, '');

let toastTimer = null;
function toast(text, ms = 3500) {
  const el = $('toast');
  el.textContent = text;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), ms);
}

function showView(name) {
  document.querySelectorAll('.view').forEach((v) => v.classList.toggle('active', v.id === name));
}

function setStatus(kind, text) {
  $('status').className = 'status ' + kind;
  $('statusText').textContent = text;
}

function rtcConfig() {
  const iceServers = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }];
  if (config.turnUrl) iceServers.push({ urls: config.turnUrl, username: config.turnUser, credential: config.turnPass });
  return { iceServers };
}

// ---------- シグナリング ----------
const sig = {
  ws: null,
  registered: false,
  backoff: 1000,
  stopped: false,
  timer: null,

  connect() {
    clearTimeout(this.timer);
    if (!config.serverUrl) {
      setStatus('offline', 'サーバー未設定');
      return;
    }
    setStatus('', 'サーバーに接続中…');
    let ws;
    try {
      ws = new WebSocket(config.serverUrl);
    } catch {
      setStatus('offline', 'サーバーURLが不正です');
      return;
    }
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 1000;
      ws.send(JSON.stringify({ type: 'register', id: config.id, secret: config.secret, name: config.deviceName, platform: 'windows' }));
    };
    ws.onmessage = (e) => {
      let msg;
      try {
        msg = JSON.parse(e.data);
      } catch {
        return;
      }
      onSignalMessage(msg);
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.registered = false;
      setStatus('offline', 'オフライン（再接続します）');
      if (!this.stopped) {
        this.timer = setTimeout(() => this.connect(), this.backoff);
        this.backoff = Math.min(this.backoff * 2, 15000);
      }
    };
  },

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  },

  restart() {
    this.stopped = false;
    const old = this.ws;
    this.ws = null;
    if (old) old.close();
    this.connect();
  },
};
setInterval(() => sig.send({ type: 'ping' }), 20000);

async function onSignalMessage(msg) {
  switch (msg.type) {
    case 'registered':
      sig.registered = true;
      if (msg.id !== config.id) config = await api.setConfig({ id: msg.id });
      $('myId').textContent = formatId(msg.id);
      setStatus('online', 'オンライン');
      break;

    case 'error':
      if (msg.code === 'replaced') {
        sig.stopped = true;
        setStatus('offline', '別の場所で同じIDが使われたため切断しました');
      }
      break;

    case 'connect_pending':
      if (state.mode === 'requesting') state.sessionId = msg.sessionId;
      break;

    case 'connect_result':
      if (state.mode !== 'requesting') return;
      $('waitModal').classList.add('hidden');
      if (!msg.ok) {
        toast(ERR[msg.code] || '接続できませんでした');
        state = { mode: 'idle' };
        return;
      }
      startController(msg);
      break;

    case 'incoming':
      onIncoming(msg);
      break;

    case 'signal':
      onRtcSignal(msg);
      break;

    case 'session_ended':
      if (state.sessionId !== msg.sessionId) return;
      if (state.mode === 'consent') hideConsent();
      if (state.mode === 'requesting') $('waitModal').classList.add('hidden');
      toast(ERR[msg.reason] || '接続が終了しました');
      cleanup();
      break;
  }
}

// ---------- WebRTC 共通 ----------
function createPeer() {
  pendingCandidates = [];
  pc = new RTCPeerConnection(rtcConfig());
  const sessionId = state.sessionId;
  pc.onicecandidate = (e) => {
    if (e.candidate) sig.send({ type: 'signal', sessionId, data: { candidate: e.candidate.toJSON() } });
  };
  pc.onconnectionstatechange = () => {
    if (!pc) return;
    if (pc.connectionState === 'failed') {
      toast(ERR.ice_failed, 6000);
      endSession('ice_failed');
    } else if (pc.connectionState === 'connected' && state.mode === 'controller') {
      $('stageMsg').textContent = '';
    }
  };
  return pc;
}

async function onRtcSignal(msg) {
  if (!pc || msg.sessionId !== state.sessionId) return;
  const d = msg.data || {};
  try {
    if (d.sdp) {
      await pc.setRemoteDescription(d.sdp);
      for (const c of pendingCandidates) await pc.addIceCandidate(c).catch(() => {});
      pendingCandidates = [];
      if (d.sdp.type === 'offer') {
        await pc.setLocalDescription(await pc.createAnswer());
        sig.send({ type: 'signal', sessionId: state.sessionId, data: { sdp: pc.localDescription.toJSON() } });
      }
    } else if (d.candidate) {
      if (pc.remoteDescription) await pc.addIceCandidate(d.candidate).catch(() => {});
      else pendingCandidates.push(d.candidate);
    }
  } catch (e) {
    console.error('signal処理エラー', e);
  }
}

function sendDc(obj) {
  if (dc && dc.readyState === 'open') dc.send(JSON.stringify(obj));
}

function endSession(reason = 'ended') {
  if (state.sessionId) sig.send({ type: 'end', sessionId: state.sessionId, reason });
  cleanup();
}

function cleanup() {
  clearInterval(statsTimer);
  statsTimer = null;
  if (dc) try { dc.close(); } catch {}
  if (pc) try { pc.close(); } catch {}
  dc = null;
  pc = null;
  if (localStream) localStream.getTracks().forEach((t) => t.stop());
  localStream = null;
  $('remoteVideo').srcObject = null;
  if (state.mode === 'host') {
    api.releaseAll();
    api.hideIndicator();
    $('hostBanner').classList.add('hidden');
  }
  if (state.mode === 'controller') {
    releaseRemoteKeys();
    api.setFullscreen(false);
  }
  state = { mode: 'idle' };
  showView('home');
}

// ---------- 操作される側（ホスト） ----------
let consentTimer = null;

function onIncoming(msg) {
  if (state.mode !== 'idle') {
    // 別の接続中・要求中は自動で断る
    sig.send({ type: 'connect_response', sessionId: msg.sessionId, accepted: false });
    return;
  }
  state = { mode: 'consent', sessionId: msg.sessionId, peerId: msg.from, peerName: msg.fromName, peerPlatform: msg.fromPlatform };
  const req = msg.requested || {};
  $('consentName').textContent = msg.fromName || '不明な端末';
  $('consentInfo').textContent = `ID ${formatId(msg.from)}・${platformLabel(msg.fromPlatform)}`;
  $('permControl').checked = !!req.control;
  $('permKeyboard').checked = !!req.keyboard;
  $('permClipboard').checked = !!req.clipboard;
  $('consentModal').classList.remove('hidden');
  api.attention();
  api.notify('AnyLink: 接続の要求', `${msg.fromName || '不明な端末'}（ID ${formatId(msg.from)}）が接続を求めています`);

  let left = 60;
  $('consentTimer').textContent = `(${left})`;
  clearInterval(consentTimer);
  consentTimer = setInterval(() => {
    left--;
    $('consentTimer').textContent = `(${left})`;
    if (left <= 0) respondConsent(false);
  }, 1000);
}

function hideConsent() {
  clearInterval(consentTimer);
  $('consentModal').classList.add('hidden');
}

function platformLabel(p) {
  return { windows: 'Windows', android: 'Android' }[p] || p || '不明';
}

function respondConsent(accepted) {
  if (state.mode !== 'consent') return;
  hideConsent();
  const permissions = {
    view: true,
    control: $('permControl').checked,
    keyboard: $('permKeyboard').checked,
    clipboard: $('permClipboard').checked,
  };
  sig.send({ type: 'connect_response', sessionId: state.sessionId, accepted, permissions });
  if (!accepted) {
    state = { mode: 'idle' };
    return;
  }
  startHost(permissions).catch((e) => {
    console.error(e);
    toast('画面の取得に失敗しました: ' + e.message, 6000);
    endSession('capture_failed');
  });
}

async function startHost(perms) {
  state = { ...state, mode: 'host', perms };
  const label = `${state.peerName || '不明な端末'}（${formatId(state.peerId)}）`;
  const text = perms.control || perms.keyboard ? `${label} に操作されています` : `${label} に画面を共有中`;
  $('hostBannerText').textContent = text;
  $('hostBanner').classList.remove('hidden');
  api.showIndicator(text);

  const src = await api.getScreenSource();
  if (!src.id) throw new Error('画面が見つかりません');
  localStream = await navigator.mediaDevices.getUserMedia({
    audio: false,
    video: {
      mandatory: {
        chromeMediaSource: 'desktop',
        chromeMediaSourceId: src.id,
        maxWidth: 1920,
        maxHeight: 1080,
        maxFrameRate: 30,
      },
    },
  });
  if (state.mode !== 'host') return; // 取得中に切断された
  const track = localStream.getVideoTracks()[0];
  track.contentHint = 'detail';

  createPeer();
  const sender = pc.addTrack(track, localStream);
  dc = pc.createDataChannel('ctl', { ordered: true });
  dc.onopen = () => sendDc({ t: 'hello', platform: 'windows', name: config.deviceName, w: src.width, h: src.height, perms });
  dc.onmessage = (e) => {
    let m;
    try {
      m = JSON.parse(e.data);
    } catch {
      return;
    }
    onHostDcMessage(m);
  };

  await pc.setLocalDescription(await pc.createOffer());
  sig.send({ type: 'signal', sessionId: state.sessionId, data: { sdp: pc.localDescription.toJSON() } });

  try {
    const p = sender.getParameters();
    p.degradationPreference = 'maintain-resolution';
    if (!p.encodings || !p.encodings.length) p.encodings = [{}];
    p.encodings[0].maxBitrate = 8_000_000;
    p.encodings[0].maxFramerate = 30;
    await sender.setParameters(p);
  } catch {}
}

// 相手からの操作メッセージ。ここで権限を必ず確認する
async function onHostDcMessage(m) {
  if (state.mode !== 'host' || !m || typeof m.t !== 'string') return;
  const p = state.perms;
  switch (m.t) {
    case 'mm':
    case 'md':
    case 'mu':
      if (p.control) api.input(m);
      break;
    case 'wh':
      if (p.control) api.input({ t: 'wh', dx: (Number(m.dx) || 0) * 1.2, dy: (Number(m.dy) || 0) * 1.2 });
      break;
    case 'kd':
    case 'ku':
    case 'text':
      if (p.keyboard) api.input(m);
      break;
    case 'clip':
      if (p.clipboard && typeof m.s === 'string') {
        api.writeClipboard(m.s);
        toast('相手からクリップボードを受け取りました');
      }
      break;
    case 'clipreq':
      if (p.clipboard) sendDc({ t: 'clip', s: await api.readClipboard() });
      break;
  }
}

// ---------- 操作する側（コントローラー） ----------
function requestConnect(id) {
  id = normId(id);
  if (id.length !== 9) return toast('IDは9桁の数字です');
  if (!sig.registered) return toast('サーバーに接続されていません');
  if (state.mode !== 'idle') return toast('すでに接続中です');
  state = { mode: 'requesting', targetId: id };
  sig.send({
    type: 'connect_request',
    to: id,
    requested: { control: $('reqControl').checked, keyboard: $('reqKeyboard').checked, clipboard: $('reqClipboard').checked },
  });
  $('waitText').textContent = `${formatId(id)} の許可を待っています…`;
  $('waitModal').classList.remove('hidden');
}

function startController(msg) {
  state = {
    mode: 'controller',
    sessionId: msg.sessionId,
    peerId: msg.hostId,
    peerName: msg.hostName,
    peerPlatform: msg.hostPlatform,
    perms: msg.permissions,
  };
  saveRecent(msg.hostId, msg.hostName);

  createPeer();
  pc.ontrack = (e) => {
    $('remoteVideo').srcObject = e.streams[0] || new MediaStream([e.track]);
  };
  pc.ondatachannel = (e) => {
    dc = e.channel;
    dc.onmessage = (ev) => {
      let m;
      try {
        m = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (m.t === 'clip' && typeof m.s === 'string') {
        api.writeClipboard(m.s);
        toast('相手のクリップボードをコピーしました');
      } else if (m.t === 'hello') {
        state.perms = m.perms || state.perms;
        renderViewerPerms();
      }
    };
  };

  const p = state.perms;
  $('viewerTitle').textContent = `${msg.hostName || '不明な端末'}（${formatId(msg.hostId)}）`;
  renderViewerPerms();
  const isAndroid = msg.hostPlatform === 'android';
  document.querySelectorAll('[data-nav]').forEach((b) => b.classList.toggle('hidden', !isAndroid || !p.control));
  $('sendClip').classList.toggle('hidden', !p.clipboard);
  $('getClip').classList.toggle('hidden', !p.clipboard);
  $('stage').classList.toggle('no-control', !p.control);
  $('stageMsg').textContent = '映像を待っています…';
  showView('viewer');
  $('stage').focus();
  statsTimer = setInterval(updateStats, 2000);
}

function renderViewerPerms() {
  const p = state.perms || {};
  const list = ['画面'];
  if (p.control) list.push('マウス');
  if (p.keyboard) list.push('キーボード');
  if (p.clipboard) list.push('クリップボード');
  $('viewerPerms').textContent = '許可: ' + list.join('・');
}

async function updateStats() {
  if (!pc) return;
  const stats = await pc.getStats();
  let fps = 0, w = 0, h = 0, rtt = null, relay = false;
  const candidates = {};
  stats.forEach((r) => {
    if (r.type === 'inbound-rtp' && r.kind === 'video') {
      fps = r.framesPerSecond || 0;
      w = r.frameWidth || 0;
      h = r.frameHeight || 0;
    }
    if (r.type === 'local-candidate' || r.type === 'remote-candidate') candidates[r.id] = r;
  });
  stats.forEach((r) => {
    if (r.type === 'candidate-pair' && r.nominated && r.state === 'succeeded') {
      if (r.currentRoundTripTime != null) rtt = Math.round(r.currentRoundTripTime * 1000);
      const l = candidates[r.localCandidateId];
      if (l && l.candidateType === 'relay') relay = true;
    }
  });
  const parts = [];
  if (w) parts.push(`${w}×${h}`);
  parts.push(`${Math.round(fps)}fps`);
  if (rtt != null) parts.push(`${rtt}ms`);
  parts.push(relay ? 'TURN中継' : 'P2P');
  $('viewerStats').textContent = parts.join(' · ');
}

// 動画の表示領域内での正規化座標（0〜1）を求める
function videoPoint(ev) {
  const v = $('remoteVideo');
  const vw = v.videoWidth, vh = v.videoHeight;
  if (!vw || !vh) return null;
  const r = v.getBoundingClientRect();
  const scale = Math.min(r.width / vw, r.height / vh);
  const cw = vw * scale, ch = vh * scale;
  const ox = r.left + (r.width - cw) / 2, oy = r.top + (r.height - ch) / 2;
  const x = (ev.clientX - ox) / cw, y = (ev.clientY - oy) / ch;
  return { x: Math.min(1, Math.max(0, x)), y: Math.min(1, Math.max(0, y)), inside: x >= 0 && x <= 1 && y >= 0 && y <= 1 };
}

const canControl = () => state.mode === 'controller' && state.perms && state.perms.control;
const canKeyboard = () => state.mode === 'controller' && state.perms && state.perms.keyboard;

let pendingMove = null;
let moveScheduled = false;
const stage = $('stage');

stage.addEventListener('pointermove', (ev) => {
  if (!canControl()) return;
  const pt = videoPoint(ev);
  if (!pt) return;
  pendingMove = pt;
  if (!moveScheduled) {
    moveScheduled = true;
    requestAnimationFrame(() => {
      moveScheduled = false;
      if (pendingMove) sendDc({ t: 'mm', x: pendingMove.x, y: pendingMove.y });
      pendingMove = null;
    });
  }
});
stage.addEventListener('pointerdown', (ev) => {
  stage.focus();
  if (!canControl()) return;
  const pt = videoPoint(ev);
  if (!pt || !pt.inside) return;
  stage.setPointerCapture(ev.pointerId);
  sendDc({ t: 'md', x: pt.x, y: pt.y, b: [0, 1, 2].includes(ev.button) ? ev.button : 0 });
  ev.preventDefault();
});
stage.addEventListener('pointerup', (ev) => {
  if (!canControl()) return;
  const pt = videoPoint(ev);
  if (!pt) return;
  sendDc({ t: 'mu', x: pt.x, y: pt.y, b: [0, 1, 2].includes(ev.button) ? ev.button : 0 });
});
stage.addEventListener('contextmenu', (ev) => ev.preventDefault());
stage.addEventListener(
  'wheel',
  (ev) => {
    if (!canControl()) return;
    ev.preventDefault();
    const k = ev.deltaMode === 1 ? 40 : ev.deltaMode === 2 ? 400 : 1;
    sendDc({ t: 'wh', dx: ev.deltaX * k, dy: ev.deltaY * k });
  },
  { passive: false }
);

const remoteKeys = new Map(); // code -> key
function releaseRemoteKeys() {
  for (const [code, key] of remoteKeys) sendDc({ t: 'ku', code, key });
  remoteKeys.clear();
}
window.addEventListener('keydown', (ev) => {
  if (!canKeyboard() || document.activeElement !== stage) return;
  ev.preventDefault();
  remoteKeys.set(ev.code, ev.key);
  sendDc({ t: 'kd', code: ev.code, key: ev.key });
});
window.addEventListener('keyup', (ev) => {
  if (!canKeyboard() || document.activeElement !== stage) return;
  ev.preventDefault();
  remoteKeys.delete(ev.code);
  sendDc({ t: 'ku', code: ev.code, key: ev.key });
});
window.addEventListener('blur', () => {
  if (state.mode === 'controller') releaseRemoteKeys();
});

// ---------- 最近の接続 ----------
function loadRecent() {
  try {
    return JSON.parse(localStorage.getItem('recent') || '[]');
  } catch {
    return [];
  }
}
function saveRecent(id, name) {
  const list = loadRecent().filter((r) => r.id !== id);
  list.unshift({ id, name, t: Date.now() });
  localStorage.setItem('recent', JSON.stringify(list.slice(0, 8)));
  renderRecent();
}
function renderRecent() {
  const ul = $('recent');
  ul.innerHTML = '';
  const list = loadRecent();
  if (!list.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'まだありません';
    ul.appendChild(li);
    return;
  }
  for (const r of list) {
    const li = document.createElement('li');
    const a = document.createElement('span');
    a.className = 'rid';
    a.textContent = formatId(r.id);
    const b = document.createElement('span');
    b.className = 'rname';
    b.textContent = r.name || '';
    li.append(a, b);
    li.onclick = () => {
      $('remoteId').value = formatId(r.id);
      requestConnect(r.id);
    };
    ul.appendChild(li);
  }
}

// ---------- 設定 ----------
function openSettings() {
  $('setServer').value = config.serverUrl;
  $('setName').value = config.deviceName;
  $('setTurnUrl').value = config.turnUrl;
  $('setTurnUser').value = config.turnUser;
  $('setTurnPass').value = config.turnPass;
  $('settingsModal').classList.remove('hidden');
}
async function saveSettings() {
  const serverUrl = $('setServer').value.trim();
  if (serverUrl && !/^wss?:\/\//.test(serverUrl)) return toast('URLは ws:// または wss:// で始めてください');
  config = await api.setConfig({
    serverUrl,
    deviceName: $('setName').value.trim() || config.deviceName,
    turnUrl: $('setTurnUrl').value.trim(),
    turnUser: $('setTurnUser').value.trim(),
    turnPass: $('setTurnPass').value,
  });
  $('myName').textContent = config.deviceName;
  $('settingsModal').classList.add('hidden');
  sig.restart(); // サーバーURL・デバイス名の変更を反映させる
  toast('保存しました');
}

// ---------- イベント ----------
$('connectForm').addEventListener('submit', (e) => {
  e.preventDefault();
  requestConnect($('remoteId').value);
});
$('remoteId').addEventListener('input', (e) => {
  const d = normId(e.target.value).slice(0, 9);
  e.target.value = d ? formatId(d) : '';
});
$('copyId').onclick = () => {
  if (!config.id) return;
  navigator.clipboard.writeText(config.id);
  toast('IDをコピーしました');
};
$('waitCancel').onclick = () => {
  if (state.sessionId) sig.send({ type: 'cancel_request', sessionId: state.sessionId });
  $('waitModal').classList.add('hidden');
  state = { mode: 'idle' };
};
$('consentAccept').onclick = () => respondConsent(true);
$('consentReject').onclick = () => respondConsent(false);
$('hostDisconnect').onclick = () => endSession('ended');
api.onHostDisconnect(() => {
  if (state.mode === 'host') endSession('ended');
});
$('viewerDisconnect').onclick = () => endSession('ended');
let fs = false;
$('toggleFs').onclick = () => {
  fs = !fs;
  api.setFullscreen(fs);
  stage.focus();
};
$('sendClip').onclick = async () => {
  sendDc({ t: 'clip', s: await api.readClipboard() });
  toast('クリップボードを送りました');
  stage.focus();
};
$('getClip').onclick = () => {
  sendDc({ t: 'clipreq' });
  stage.focus();
};
document.querySelectorAll('[data-nav]').forEach((b) => {
  b.onclick = () => {
    sendDc({ t: 'nav', a: b.dataset.nav });
    stage.focus();
  };
});
$('openSettings').onclick = openSettings;
$('settingsCancel').onclick = () => $('settingsModal').classList.add('hidden');
$('settingsSave').onclick = saveSettings;

// ---------- 起動 ----------
(async () => {
  config = await api.getConfig();
  $('myId').textContent = formatId(config.id);
  $('myName').textContent = config.deviceName;
  renderRecent();
  sig.connect();
})();
