using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace SlideShow;

public sealed class SlideshowWindow : Form
{
    private readonly string _url;
    private readonly Action? _openLibrary;
    private readonly WebView2 _webView;

    public SlideshowWindow(string url, Action? openLibrary = null)
    {
        _url = url;
        _openLibrary = openLibrary;
        _webView = new WebView2
        {
            Dock = DockStyle.Fill,
            DefaultBackgroundColor = Color.Black,
            CreationProperties = new CoreWebView2CreationProperties
            {
                UserDataFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Slide Show", "WebView2")
            }
        };
        AutoScaleMode = AutoScaleMode.None;
        BackColor = Color.Black;
        Bounds = Screen.FromPoint(Cursor.Position).Bounds;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = true;
        StartPosition = FormStartPosition.Manual;
        Text = "Slide Show";
        WindowState = FormWindowState.Maximized;
        Controls.Add(_webView);
        Load += OnLoadAsync;
    }

    private async void OnLoadAsync(object? sender, EventArgs eventArgs)
    {
        try
        {
            await _webView.EnsureCoreWebView2Async();
            _webView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            _webView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            _webView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            _webView.CoreWebView2.WebMessageReceived += (_, message) =>
            {
                var action = message.TryGetWebMessageAsString();
                if (action is "exit-slideshow" or "open-library")
                {
                    Close();
                    if (action == "open-library") _openLibrary?.Invoke();
                }
            };
            await _webView.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync("""
                document.addEventListener("keydown", event => {
                  if (event.key === "Escape" && document.querySelector("#offlineModal")?.hidden && document.querySelector("#settingsPanel")?.hidden) {
                    chrome.webview.postMessage("exit-slideshow");
                  }
                });
                """);
            _webView.Source = new Uri(_url);
            Activate();
        }
        catch (Exception error)
        {
            MessageBox.Show(this, "The slideshow window could not open. Try Play here in your browser.\n\n" + error.Message, "Slide Show", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
        }
    }
}
