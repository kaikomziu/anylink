// レンダラーに公開する最小限の API
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('anylink', {
  getConfig: () => ipcRenderer.invoke('config:get'),
  setConfig: (patch) => ipcRenderer.invoke('config:set', patch),
  getScreenSource: () => ipcRenderer.invoke('screen:source'),
  input: (m) => ipcRenderer.send('input', m),
  releaseAll: () => ipcRenderer.send('input:releaseAll'),
  readClipboard: () => ipcRenderer.invoke('clipboard:read'),
  writeClipboard: (s) => ipcRenderer.send('clipboard:write', s),
  attention: () => ipcRenderer.send('win:attention'),
  setFullscreen: (on) => ipcRenderer.send('win:fullscreen', on),
  showIndicator: (text) => ipcRenderer.send('indicator:show', text),
  hideIndicator: () => ipcRenderer.send('indicator:hide'),
  indicatorDisconnect: () => ipcRenderer.send('indicator:disconnect'),
  indicatorOpen: () => ipcRenderer.send('indicator:open'),
  notify: (title, body) => ipcRenderer.send('notify', title, body),
  onHostDisconnect: (fn) => ipcRenderer.on('host:disconnect', () => fn()),
  onIndicatorText: (fn) => ipcRenderer.on('indicator:text', (_e, t) => fn(t)),
});
