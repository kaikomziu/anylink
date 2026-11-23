// AnyLink 入力ヘルパー
// 標準入力から1行1コマンドを受け取り、SendInput でマウス・キーボード操作を注入する。
//   mm X Y        マウス移動（プライマリ画面上の 0..65535 正規化座標）
//   md B / mu B   ボタン押下 / 解放（B = 0:左 1:中 2:右）
//   wh DX DY      ホイール（DY>0 で下方向へスクロール）
//   kd VK / ku VK キー押下 / 解放（仮想キーコード）
//   tx BASE64     UTF-8 文字列を Unicode 入力として打ち込む
using System;
using System.Runtime.InteropServices;
using System.Text;

static class InputHelper
{
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public int mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [StructLayout(LayoutKind.Explicit)]
    struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public INPUTUNION u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll")]
    static extern uint MapVirtualKey(uint uCode, uint uMapType);

    const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    const uint MOUSEEVENTF_MOVE = 0x0001, MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004,
               MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010, MOUSEEVENTF_MIDDLEDOWN = 0x0020,
               MOUSEEVENTF_MIDDLEUP = 0x0040, MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x1000,
               MOUSEEVENTF_ABSOLUTE = 0x8000;
    const uint KEYEVENTF_EXTENDEDKEY = 0x0001, KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004, KEYEVENTF_SCANCODE = 0x0008;

    static readonly int Size = Marshal.SizeOf(typeof(INPUT));

    static void Mouse(uint flags, int dx, int dy, int data)
    {
        var inp = new INPUT { type = INPUT_MOUSE };
        inp.u.mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags };
        SendInput(1, new[] { inp }, Size);
    }

    // 拡張キー（矢印・Insert/Delete・Home/End・PageUp/Down・右Ctrl/Alt・Win など）
    static bool IsExtended(ushort vk)
    {
        switch (vk)
        {
            case 0x21: case 0x22: case 0x23: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28:
            case 0x2C: case 0x2D: case 0x2E: case 0x5B: case 0x5C: case 0x5D: case 0x6F: case 0x90:
            case 0xA3: case 0xA5:
                return true;
        }
        return false;
    }

    static void Key(ushort vk, bool up)
    {
        var inp = new INPUT { type = INPUT_KEYBOARD };
        uint flags = up ? KEYEVENTF_KEYUP : 0;
        if (IsExtended(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
        inp.u.ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)MapVirtualKey(vk, 0), dwFlags = flags };
        SendInput(1, new[] { inp }, Size);
    }

    static void TypeText(string s)
    {
        foreach (char c in s)
        {
            if (c == '\r') continue;
            if (c == '\n') { Key(0x0D, false); Key(0x0D, true); continue; }
            var down = new INPUT { type = INPUT_KEYBOARD };
            down.u.ki = new KEYBDINPUT { wVk = 0, wScan = c, dwFlags = KEYEVENTF_UNICODE };
            var up = new INPUT { type = INPUT_KEYBOARD };
            up.u.ki = new KEYBDINPUT { wVk = 0, wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP };
            SendInput(2, new[] { down, up }, Size);
        }
    }

    static readonly uint[] Down = { MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_RIGHTDOWN };
    static readonly uint[] Up = { MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_RIGHTUP };

    static void Main()
    {
        // winexe にはコンソールが無いので Console.InputEncoding は使えない。標準入力を直接読む
        var reader = new System.IO.StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        string line;
        while ((line = reader.ReadLine()) != null)
        {
            try
            {
                var p = line.Split(' ');
                switch (p[0])
                {
                    case "mm":
                        Mouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, int.Parse(p[1]), int.Parse(p[2]), 0);
                        break;
                    case "md":
                    case "mu":
                    {
                        int b = Math.Max(0, Math.Min(2, int.Parse(p[1])));
                        Mouse(p[0] == "md" ? Down[b] : Up[b], 0, 0, 0);
                        break;
                    }
                    case "wh":
                    {
                        int dx = int.Parse(p[1]), dy = int.Parse(p[2]);
                        if (dy != 0) Mouse(MOUSEEVENTF_WHEEL, 0, 0, -dy);
                        if (dx != 0) Mouse(MOUSEEVENTF_HWHEEL, 0, 0, dx);
                        break;
                    }
                    case "kd":
                        Key(ushort.Parse(p[1]), false);
                        break;
                    case "ku":
                        Key(ushort.Parse(p[1]), true);
                        break;
                    case "tx":
                        TypeText(Encoding.UTF8.GetString(Convert.FromBase64String(p[1])));
                        break;
                }
            }
            catch (Exception)
            {
                // 不正な行は無視して続行
            }
        }
    }
}
