// 入力ヘルパー（C#）を Windows 標準の csc.exe でビルドする
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const csc = path.join(process.env.WINDIR || 'C:\\Windows', 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe');
const root = path.join(__dirname, '..');
fs.mkdirSync(path.join(root, 'resources'), { recursive: true });

execFileSync(
  csc,
  [
    '-nologo',
    '-optimize+',
    '-target:winexe',
    '-out:' + path.join(root, 'resources', 'input-helper.exe'),
    path.join(root, 'native', 'InputHelper.cs'),
  ],
  { stdio: 'inherit' }
);
console.log('input-helper.exe をビルドしました');
