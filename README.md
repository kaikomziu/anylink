# AnyLink

PC（Windows）と Android で使える遠隔操作アプリです。

- ホームページ: https://kaikomziu.github.io/anylink/
- ダウンロード: https://github.com/kaikomziu/anylink/releases/latest
端末ごとに9桁のIDがあり、相手のIDを入力して接続 → 相手側で権限を選んで許可 → 操作できます。
映像と操作は **WebRTC の P2P** で端末同士が直接やり取りし、サーバー（WebSocket）は接続の取り次ぎだけを行います。

## 構成

| フォルダ | 中身 |
|---|---|
| `server/` | シグナリングサーバー（Node.js + ws）。ID発行・接続要求の中継・SDP/ICE中継 |
| `desktop/` | PC版（Electron）。画面配信・操作・許可ダイアログ。入力注入は `native/InputHelper.cs`（SendInput） |
| `android/` | Android版（Kotlin）。画面配信は MediaProjection、操作はユーザー補助サービス |
| `release/` | ビルド済みの配布物（exe / apk） |
| `docs/` | ホームページ（GitHub Pages） |
| `tools/make_icons.py` | アイコン生成 |

## 使い方

1. PC と Android に AnyLink を入れて起動する
   - シグナリングサーバーは Render に公開済み（`wss://anylink-signaling.onrender.com`）で、アプリの初期設定になっています
   - 無料プランのため、しばらく誰も使わないとサーバーが眠り、最初の接続に1分ほどかかることがあります（アプリは自動で再接続します）
   - 自分でサーバーを動かす場合は `cd server && npm install && npm start`（ポート 8787）して、アプリの **設定** でURLを変更
2. 操作したい側で、相手のIDを入力して「接続」
3. 操作される側に許可ダイアログが出る → 権限（マウス/タッチ・キーボード・クリップボード）を選んで「許可」
   - Android は続けて OS の「画面の録画・キャスト」確認が出るので「画面全体」を選んで開始

### Android で操作を受け入れるには
設定 → ユーザー補助 → 「AnyLink 遠隔操作」をオンにします（画面を見せるだけなら不要）。
アプリの更新や強制停止のあとに止まった場合は、一度オフにしてからオンにし直してください。

## 操作方法

**PC で操作するとき**: マウス・ホイール・キーボードがそのまま相手に送られます（映像をクリックしてから入力）。
相手が Android なら 右クリック＝戻る、中クリック＝ホーム。ツールバーに ◁ ○ □ もあります。

**Android で操作するとき**
- 相手が PC: タップ＝クリック、なぞる＝ドラッグ、長押し＝右クリック、2本指で上下＝スクロール
- 相手が Android: 触ったとおりにタップ・スワイプ・長押しされます
- ⌨ ボタンで文字入力（日本語の変換も確定してから送信）

## 安全のための仕組み
- 接続には毎回、操作される側の許可が必要（60秒で自動拒否）。権限は個別に選べ、される側で必ず確認
- 操作中は PC では最前面の赤い帯、Android では通知と画面上部の帯で表示。いつでも「切断」可能
- IDは端末ごとの秘密鍵と結び付けてあり、他の端末が同じIDを名乗れない
- 接続要求は1分10回までに制限（IDの総当たり対策）

## ビルド

```
# PC版（release 用 exe を desktop/dist に出力）
cd desktop
npm install
npm run dist

# Android版（apk を android/app/build/outputs/apk/release に出力）
cd android
./gradlew assembleRelease
```

テストで1台のPCに2つ起動したいときは `npx electron . --profile=b` のように設定フォルダを分けます。

## 制限・メモ
- 携帯回線や企業ネットワークなど、P2P でつながらない環境では TURN サーバーが必要です（設定で指定可）
- PC版はプライマリモニターのみ配信します。管理者権限のウィンドウ（UAC など）は操作できません
- Android の Ctrl+Alt+Del 相当や、ロック画面の解除はできません
