# Move the mouse and click, at coordinates relative to a window's own corner.
#
#   powershell -NoProfile -File tools/click-at.ps1 -Process SEKAI -FromRight 118 -FromTop 42
#
# The updater banner lives at the top of the window, but where its button sits
# depends on the window's width and the theme's spacing — arithmetic nobody
# wants to redo by hand. Measured from the window's right edge instead, the
# button is a fixed distance in whatever the window's current size is.
#
# Used to click the update banner on a packaged build: the launcher is a GUI
# executable with no console and no harness for this, so driving the button it
# draws is the only way to see the update actually happen the way a reader sees
# it.

param(
    [string]$Process = "SEKAI",
    [int]$FromRight = 118,
    [int]$FromTop = 42,
    [switch]$RectOnly
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class WinRect {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT lpPoint);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, IntPtr extra);
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    public struct POINT { public int X; public int Y; }
    public static void ClickAt(int x, int y) {
        SetCursorPos(x, y);
        System.Threading.Thread.Sleep(250);
        mouse_event(0x0002, 0, 0, 0, IntPtr.Zero); // left down
        System.Threading.Thread.Sleep(60);
        mouse_event(0x0004, 0, 0, 0, IntPtr.Zero); // left up
    }
}
"@

$target = Get-Process -Name $Process -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne 0 } |
    Sort-Object WorkingSet64 -Descending |
    Select-Object -First 1

if (-not $target) {
    Write-Output "no window for process '$Process'"
    exit 1
}

# Measured from the *client* area, not the window: a maximized window's frame
# extends past the screen edges, so the window rect's top-left is not where the
# app's own pixels start.
$rect = New-Object WinRect+RECT
[void][WinRect]::GetWindowRect($target.MainWindowHandle, [ref]$rect)
$client = New-Object WinRect+RECT
[void][WinRect]::GetClientRect($target.MainWindowHandle, [ref]$client)
$origin = New-Object WinRect+POINT
[void][WinRect]::ClientToScreen($target.MainWindowHandle, [ref]$origin)

$width = $client.Right - $client.Left
$height = $client.Bottom - $client.Top

$x = $origin.X + $width - $FromRight
$y = $origin.Y + $FromTop
Write-Output "window '$($target.MainWindowTitle)' pid $($target.Id) window $($rect.Left),$($rect.Top),$($rect.Right),$($rect.Bottom)"
Write-Output "client at $($origin.X),$($origin.Y) size ${width}x${height}"
Write-Output "click at $x,$y"

if ($RectOnly) { exit 0 }

[void][WinRect]::SetForegroundWindow($target.MainWindowHandle)
Start-Sleep -Milliseconds 400
[WinRect]::ClickAt($x, $y)
Write-Output "clicked"
