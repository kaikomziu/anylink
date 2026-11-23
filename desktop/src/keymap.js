// KeyboardEvent.code → Windows 仮想キーコード の対応表
'use strict';

const MAP = {
  Escape: 0x1b, F1: 0x70, F2: 0x71, F3: 0x72, F4: 0x73, F5: 0x74, F6: 0x75, F7: 0x76, F8: 0x77,
  F9: 0x78, F10: 0x79, F11: 0x7a, F12: 0x7b,
  Backquote: 0xc0, Minus: 0xbd, Equal: 0xbb, Backspace: 0x08, Tab: 0x09,
  BracketLeft: 0xdb, BracketRight: 0xdd, Backslash: 0xdc, IntlYen: 0xdc, IntlRo: 0xe2,
  CapsLock: 0x14, Semicolon: 0xba, Quote: 0xde, Enter: 0x0d,
  ShiftLeft: 0xa0, ShiftRight: 0xa1, ControlLeft: 0xa2, ControlRight: 0xa3, AltLeft: 0xa4, AltRight: 0xa5,
  MetaLeft: 0x5b, MetaRight: 0x5c, ContextMenu: 0x5d,
  Comma: 0xbc, Period: 0xbe, Slash: 0xbf, Space: 0x20,
  Convert: 0x1c, NonConvert: 0x1d, KanaMode: 0x15, Lang1: 0x15, Lang2: 0x19,
  PrintScreen: 0x2c, ScrollLock: 0x91, Pause: 0x13,
  Insert: 0x2d, Home: 0x24, PageUp: 0x21, Delete: 0x2e, End: 0x23, PageDown: 0x22,
  ArrowUp: 0x26, ArrowDown: 0x28, ArrowLeft: 0x25, ArrowRight: 0x27,
  NumLock: 0x90, NumpadDivide: 0x6f, NumpadMultiply: 0x6a, NumpadSubtract: 0x6d, NumpadAdd: 0x6b,
  NumpadEnter: 0x0d, NumpadDecimal: 0x6e,
};
for (let i = 0; i < 26; i++) MAP['Key' + String.fromCharCode(65 + i)] = 65 + i;
for (let i = 0; i < 10; i++) {
  MAP['Digit' + i] = 0x30 + i;
  MAP['Numpad' + i] = 0x60 + i;
}

// Android のソフトキーボード等から来る key 名だけの入力にも対応する
const KEY_MAP = {
  Enter: 0x0d, Backspace: 0x08, Tab: 0x09, Escape: 0x1b, Delete: 0x2e, ' ': 0x20,
  ArrowUp: 0x26, ArrowDown: 0x28, ArrowLeft: 0x25, ArrowRight: 0x27, Home: 0x24, End: 0x23,
  PageUp: 0x21, PageDown: 0x22, Control: 0xa2, Shift: 0xa0, Alt: 0xa4, Meta: 0x5b,
};

function codeToVk(code, key) {
  if (code && MAP[code]) return MAP[code];
  if (key && KEY_MAP[key]) return KEY_MAP[key];
  if (key && /^F\d{1,2}$/.test(key)) return MAP[key] || 0;
  return 0;
}

module.exports = { codeToVk };
