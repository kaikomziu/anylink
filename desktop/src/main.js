// AnyLink PC版 メインプロセス
// 設定の保存、画面ソース取得、入力ヘルパーの管理、トレイ常駐、操作中インジケーターを担当する。
'use strict';

const { app, BrowserWindow, ipcMain, desktopCapturer, screen, clipboard, Tray, Menu, nativeImage, Notification } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { codeToVk } = require('./keymap');

// テスト用: --profile=名前 で設定フォルダを分けると、1台で2つ同時に起動できる
const profileArg = process.argv.find((a) => a.startsWith('--profile='));
if (profileArg) app.setPath('userData', app.getPath('userData') + '-' + profileArg.split('=')[1].replace(/\W/g, ''));

if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

let mainWin = null;
let indicatorWin = null;
let tray = null;
let quitting = false;

// ---- 設定 ----
const configPath = () => path.join(app.getPath('userData'), 'config.json');
const DEFAULT_CONFIG = {
  serverUrl: 'wss://anylink-signaling.onrender.com',
  deviceName: os.hostname(),
  turnUrl: '',
  turnUser: '',
  turnPass: '',
  id: '',
  secret: '',
};
function loadConfig() {
  let c = {};
  try {
    c = JSON.parse(fs.readFileSync(configPath(), 'utf8'));
  } catch {}
  c = { ...DEFAULT_CONFIG, ...c };
  if (!c.secret) {
    c.secret = crypto.randomBytes(24).toString('hex');
    saveConfig(c);
  }
  return c;
}
function saveConfig(c) {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true });
  fs.writeFileSync(configPath(), JSON.stringify(c, null, 2));
}

// ---- アイコン ----
const iconPath = () =>
  app.isPackaged ? path.join(process.resourcesPath, 'app.asar', 'src', 'icon.png') : path.join(__dirname, 'icon.png');

// ---- 入力ヘルパー ----
let helper = null;
function helperPath() {
  return app.isPackaged ? path.join(process.resourcesPath, 'input-helper.exe') : path.join(__dirname, '..', 'resources', 'input-helper.exe');
}
function helperWrite(line) {
  if (!helper || helper.exitCode !== null) {
    helper = spawn(helperPath(), [], { stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true });
    helper.on('error', (e) => console.error('input-helper 起動失敗', e));
    helper.on('exit', () => (helper = null));
  }
  helper.stdin.write(line + '\n');
}

const pressedKeys = new Set();
const pressedButtons = new Set();
const clamp01 = (v) => Math.min(1, Math.max(0, Number(v) || 0));

// 操作メッセージ（正規化座標）を入力ヘルパーのコマンドに変換する
function injectInput(m) {
  switch (m.t) {
    case 'mm':
    case 'md':
    case 'mu': {
      if (m.x !== undefined) {
        helperWrite(`mm ${Math.round(clamp01(m.x) * 65535)} ${Math.round(clamp01(m.y) * 65535)}`);
      }
      if (m.t === 'md' || m.t === 'mu') {
        const b = [0, 1, 2].includes(m.b) ? m.b : 0;
        helperWrite(`${m.t} ${b}`);
        if (m.t === 'md') pressedButtons.add(b);
        else pressedButtons.delete(b);
      }
      break;
    }
    case 'wh':
      helperWrite(`wh ${Math.round(Number(m.dx) || 0)} ${Math.round(Number(m.dy) || 0)}`);
      break;
    case 'kd':
    case 'ku': {
      const vk = codeToVk(m.code, m.key);
      if (!vk) return;
      helperWrite(`${m.t} ${vk}`);
      if (m.t === 'kd') pressedKeys.add(vk);
      else pressedKeys.delete(vk);
      break;
    }
    case 'text':
      if (typeof m.s === 'string' && m.s.length) helperWrite('tx ' + Buffer.from(m.s.slice(0, 4000), 'utf8').toString('base64'));
      break;
  }
}

// 切断時に押しっぱなしのキー・ボタンを離す（Shift が押されたまま等を防ぐ）
function releaseAll() {
  for (const vk of pressedKeys) helperWrite(`ku ${vk}`);
  for (const b of pressedButtons) helperWrite(`mu ${b}`);
  pressedKeys.clear();
  pressedButtons.clear();
}

// ---- ウィンドウ ----
function createMainWindow() {
  mainWin = new BrowserWindow({
    width: 980,
    height: 680,
    minWidth: 720,
    minHeight: 520,
    title: 'AnyLink',
    icon: iconPath(),
    backgroundColor: '#0f1115',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false, // 最小化中も画面配信を止めない
    },
  });
  mainWin.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWin.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      mainWin.hide();
      if (Notification.isSupported() && !app._trayNotified) {
        app._trayNotified = true;
        new Notification({ title: 'AnyLink', body: 'トレイで待機中です。接続要求を受け付けられます。' }).show();
      }
    }
  });
}

function showMain() {
  if (!mainWin) return;
  if (mainWin.isMinimized()) mainWin.restore();
  mainWin.show();
  mainWin.focus();
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(iconPath()).resize({ width: 16, height: 16 }));
  tray.setToolTip('AnyLink');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: 'AnyLink を開く', click: showMain },
      { type: 'separator' },
      {
        label: '終了',
        click: () => {
          quitting = true;
          app.quit();
        },
      },
    ])
  );
  tray.on('click', showMain);
}

function showIndicator(text) {
  if (!indicatorWin) {
    const wa = screen.getPrimaryDisplay().workArea;
    const w = 420;
    indicatorWin = new BrowserWindow({
      width: w,
      height: 46,
      x: Math.round(wa.x + (wa.width - w) / 2),
      y: wa.y + 6,
      frame: false,
      resizable: false,
      movable: true,
      alwaysOnTop: true,
      skipTaskbar: true,
      focusable: true,
      transparent: true,
      webPreferences: {
        preload: path.join(__dirname, 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
      },
    });
    indicatorWin.setAlwaysOnTop(true, 'screen-saver');
    indicatorWin.loadFile(path.join(__dirname, 'renderer', 'indicator.html'), { query: { text } });
    indicatorWin.on('closed', () => (indicatorWin = null));
  } else {
    indicatorWin.webContents.send('indicator:text', text);
  }
}
function hideIndicator() {
  if (indicatorWin) indicatorWin.destroy();
  indicatorWin = null;
}

// ---- IPC ----
ipcMain.handle('config:get', () => loadConfig());
ipcMain.handle('config:set', (_e, patch) => {
  const c = { ...loadConfig(), ...patch };
  saveConfig(c);
  return c;
});
ipcMain.handle('screen:source', async () => {
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
  const primary = screen.getPrimaryDisplay();
  const src = sources.find((s) => String(s.display_id) === String(primary.id)) || sources[0];
  return {
    id: src ? src.id : null,
    width: Math.round(primary.size.width * primary.scaleFactor),
    height: Math.round(primary.size.height * primary.scaleFactor),
  };
});
ipcMain.on('input', (_e, m) => {
  if (m && typeof m.t === 'string') injectInput(m);
});
ipcMain.on('input:releaseAll', releaseAll);
ipcMain.handle('clipboard:read', () => clipboard.readText());
ipcMain.on('clipboard:write', (_e, s) => clipboard.writeText(String(s || '').slice(0, 100000)));
ipcMain.on('win:attention', () => {
  showMain();
  mainWin.flashFrame(true);
  mainWin.setAlwaysOnTop(true);
  setTimeout(() => mainWin && mainWin.setAlwaysOnTop(false), 1500);
});
ipcMain.on('win:fullscreen', (_e, on) => mainWin && mainWin.setFullScreen(!!on));
ipcMain.on('indicator:show', (_e, text) => showIndicator(String(text || '')));
ipcMain.on('indicator:hide', hideIndicator);
ipcMain.on('indicator:disconnect', () => mainWin && mainWin.webContents.send('host:disconnect'));
ipcMain.on('indicator:open', showMain);
ipcMain.on('notify', (_e, title, body) => {
  if (Notification.isSupported()) {
    const n = new Notification({ title: String(title), body: String(body) });
    n.on('click', showMain);
    n.show();
  }
});

app.on('second-instance', showMain);

app.whenReady().then(() => {
  createMainWindow();
  createTray();
});

app.on('before-quit', () => {
  quitting = true;
  releaseAll();
  if (helper) helper.kill();
});
app.on('window-all-closed', () => {});
