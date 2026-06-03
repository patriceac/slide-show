using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace SlideShow;

public sealed class SlideshowWindow : Form, IMessageFilter
{
    private const string ExitMessage = "exit-slideshow";
    private const int WmKeyDown = 0x0100;
    private const int WmSysKeyDown = 0x0104;
    private const int WhKeyboardLl = 13;
    private const int VkEscape = 0x1B;
    private readonly string _url;
    private readonly WebView2 _webView;
    private readonly LowLevelKeyboardProc _keyboardProc;
    private IntPtr _keyboardHook = IntPtr.Zero;

    public SlideshowWindow(string url)
    {
        _url = url;
        _webView = new WebView2
        {
            Dock = DockStyle.Fill,
            DefaultBackgroundColor = Color.Black,
            CreationProperties = new CoreWebView2CreationProperties
            {
                UserDataFolder = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "Slide Show",
                    "WebView2")
            }
        };
        _keyboardProc = OnLowLevelKeyboard;

        AutoScaleMode = AutoScaleMode.None;
        BackColor = Color.Black;
        Bounds = Screen.FromPoint(Cursor.Position).Bounds;
        FormBorderStyle = FormBorderStyle.None;
        KeyPreview = true;
        ShowInTaskbar = true;
        StartPosition = FormStartPosition.Manual;
        Text = "Slide Show";
        TopMost = true;
        WindowState = FormWindowState.Maximized;

        Controls.Add(_webView);
        Load += OnLoadAsync;
        Shown += (_, _) =>
        {
            Application.AddMessageFilter(this);
            _keyboardHook = SetWindowsHookEx(WhKeyboardLl, _keyboardProc, IntPtr.Zero, 0);
        };
        KeyDown += OnKeyDown;
        _webView.KeyDown += OnKeyDown;
    }

    public bool PreFilterMessage(ref Message message)
    {
        if ((message.Msg == WmKeyDown || message.Msg == WmSysKeyDown) && (Keys)message.WParam == Keys.Escape)
        {
            Close();
            return true;
        }

        return false;
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        if (keyData == Keys.Escape)
        {
            Close();
            return true;
        }

        return base.ProcessCmdKey(ref msg, keyData);
    }

    private IntPtr OnLowLevelKeyboard(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0 &&
            (wParam == (IntPtr)WmKeyDown || wParam == (IntPtr)WmSysKeyDown) &&
            Marshal.ReadInt32(lParam) == VkEscape)
        {
            BeginInvoke((Action)Close);
            return 1;
        }

        return CallNextHookEx(_keyboardHook, nCode, wParam, lParam);
    }

    private async void OnLoadAsync(object? sender, EventArgs eventArgs)
    {
        try
        {
            await _webView.EnsureCoreWebView2Async();
            _webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            _webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            _webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            _webView.CoreWebView2.WebMessageReceived += OnWebMessageReceived;
            await _webView.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync($$"""
                document.addEventListener("keydown", event => {
                  if (event.key === "Escape") {
                    chrome.webview.postMessage("{{ExitMessage}}");
                  }
                });
                """);
            _webView.Source = new Uri(_url);
            Activate();
        }
        catch
        {
            Close();
        }
    }

    private void OnKeyDown(object? sender, KeyEventArgs eventArgs)
    {
        if (eventArgs.KeyCode == Keys.Escape)
        {
            eventArgs.Handled = true;
            Close();
        }
    }

    private void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs eventArgs)
    {
        if (string.Equals(eventArgs.TryGetWebMessageAsString(), ExitMessage, StringComparison.Ordinal))
        {
            Close();
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Application.RemoveMessageFilter(this);
            if (_keyboardHook != IntPtr.Zero)
            {
                UnhookWindowsHookEx(_keyboardHook);
                _keyboardHook = IntPtr.Zero;
            }
            _webView.Dispose();
        }

        base.Dispose(disposing);
    }

    private delegate IntPtr LowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelKeyboardProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
}
