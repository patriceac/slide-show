using System.Diagnostics;
using System.Runtime.InteropServices;

namespace SlideShow;

public sealed class TrayApplicationContext : ApplicationContext
{
    private readonly AppState _state;
    private readonly SettingsStore _settingsStore;
    private readonly ViewerActivityTracker _viewerActivity;
    private readonly PowerAwakeManager _powerAwakeManager;
    private readonly AppWebServer _webServer;
    private readonly DiscoveryService _discoveryService;
    private readonly NotifyIcon _notifyIcon;
    private readonly Form _dispatcher;
    private readonly bool _startMinimized;
    private ToolStripMenuItem? _startupItem;
    private SlideshowWindow? _slideshowWindow;

    public TrayApplicationContext(bool startMinimized)
    {
        _startMinimized = startMinimized;
        _settingsStore = new SettingsStore();
        _viewerActivity = new ViewerActivityTracker();
        _powerAwakeManager = new PowerAwakeManager(_viewerActivity);
        _state = new AppState(_settingsStore, _viewerActivity);
        _webServer = new AppWebServer(_state, ChooseFolderAsync, OpenSlideshowWindowAsync);
        _discoveryService = new DiscoveryService(_state);
        _dispatcher = new Form
        {
            ShowInTaskbar = false,
            WindowState = FormWindowState.Minimized,
            FormBorderStyle = FormBorderStyle.FixedToolWindow,
            Opacity = 0
        };
        // Force a native handle so folder-picker requests can marshal back to the WinForms UI thread.
        _ = _dispatcher.Handle;

        _notifyIcon = new NotifyIcon
        {
            Icon = LoadAppIcon(),
            Text = "Slide Show",
            Visible = true,
            ContextMenuStrip = BuildTrayMenu()
        };
        _notifyIcon.DoubleClick += (_, _) => OpenControlCenter();

        _ = StartAsync();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            _slideshowWindow?.Close();
            _slideshowWindow?.Dispose();
            _dispatcher.Dispose();
            _discoveryService.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _webServer.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _powerAwakeManager.Dispose();
        }

        base.Dispose(disposing);
    }

    private ContextMenuStrip BuildTrayMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open control center", null, (_, _) => OpenControlCenter());
        menu.Items.Add("Open slideshow", null, (_, _) => _ = OpenSlideshowWindowAsync());
        menu.Items.Add("Copy mobile link", null, (_, _) => CopyMobileLink());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Choose folder", null, async (_, _) => await ChooseAndApplyFolderAsync());
        menu.Items.Add("Rescan images", null, (_, _) => Rescan());
        menu.Items.Add(new ToolStripSeparator());

        _startupItem = new ToolStripMenuItem("Start at login")
        {
            Checked = _state.GetSettings().StartAtLogin,
            CheckOnClick = true
        };
        _startupItem.CheckedChanged += (_, _) => SetStartup(_startupItem.Checked);
        menu.Items.Add(_startupItem);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, async (_, _) => await QuitAsync());
        return menu;
    }

    private static Icon LoadAppIcon()
    {
        try
        {
            var associatedIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            if (associatedIcon is not null)
            {
                return associatedIcon;
            }
        }
        catch
        {
        }

        try
        {
            var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
            if (File.Exists(iconPath))
            {
                return new Icon(iconPath);
            }
        }
        catch
        {
        }

        return SystemIcons.Application;
    }

    private async Task StartAsync()
    {
        await _webServer.StartAsync();
        _discoveryService.Start();
        _state.Rescan();

        var settings = _state.GetSettings();
        StartupManager.SetEnabled(settings.StartAtLogin);

        if (!_startMinimized)
        {
            OpenControlCenter();
        }

        UpdateTrayText();
    }

    public Task<string?> ChooseFolderAsync(string? initialPath)
    {
        var tcs = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);

        void ChooseFolder()
        {
            try
            {
                tcs.SetResult(ShowFolderDialog(initialPath));
            }
            catch (Exception ex)
            {
                tcs.SetException(ex);
            }
        }

        try
        {
            _dispatcher.BeginInvoke((Action)ChooseFolder);
        }
        catch (Exception ex)
        {
            tcs.SetException(ex);
        }

        return tcs.Task;
    }

    private static string? ShowFolderDialog(string? initialPath)
    {
        using var owner = new Form
        {
            FormBorderStyle = FormBorderStyle.FixedToolWindow,
            Location = Cursor.Position,
            Opacity = 0,
            ShowInTaskbar = false,
            Size = new Size(1, 1),
            StartPosition = FormStartPosition.Manual,
            TopMost = true
        };

        using var dialog = new FolderBrowserDialog
        {
            Description = "Choose an image folder",
            UseDescriptionForTitle = true,
            SelectedPath = Directory.Exists(initialPath) ? initialPath : Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)
        };

        owner.Show();
        owner.Activate();
        owner.BringToFront();
        _ = SetForegroundWindow(owner.Handle);

        return dialog.ShowDialog(owner) == DialogResult.OK ? dialog.SelectedPath : null;
    }

    private async Task ChooseAndApplyFolderAsync()
    {
        var folder = await ChooseFolderAsync(_state.GetSettings().FolderPath);
        if (folder is null)
        {
            return;
        }

        _state.UpdateSettings(new SettingsUpdateDto { FolderPath = folder });
    }

    private void OpenControlCenter() => OpenUrl($"http://localhost:{_state.Port}/settings");

    private Task OpenSlideshowWindowAsync()
    {
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        void OpenWindow()
        {
            try
            {
                var url = $"http://localhost:{_state.Port}/show";
                if (_slideshowWindow is { IsDisposed: false })
                {
                    _slideshowWindow.Close();
                    _slideshowWindow.Dispose();
                }

                _slideshowWindow = new SlideshowWindow(url);
                _slideshowWindow.FormClosed += (_, _) =>
                {
                    _slideshowWindow?.Dispose();
                    _slideshowWindow = null;
                };
                _slideshowWindow.Show();
                _slideshowWindow.Activate();
                tcs.SetResult();
            }
            catch (Exception ex)
            {
                tcs.SetException(ex);
            }
        }

        try
        {
            _dispatcher.BeginInvoke((Action)OpenWindow);
        }
        catch (Exception ex)
        {
            tcs.SetException(ex);
        }

        return tcs.Task;
    }

    private void CopyMobileLink()
    {
        try
        {
            var link = _state.GetSnapshot(true).DisplaySlideshowUrl;
            Clipboard.SetText(link);
        }
        catch
        {
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true
            });
        }
        catch
        {
        }
    }

    private void Rescan() => _state.Rescan();

    private void SetStartup(bool enabled)
    {
        _state.UpdateSettings(new SettingsUpdateDto { StartAtLogin = enabled });
        StartupManager.SetEnabled(enabled);
    }

    private async Task QuitAsync()
    {
        _notifyIcon.Visible = false;
        await _discoveryService.StopAsync();
        await _webServer.StopAsync();
        ExitThread();
    }

    private void UpdateTrayText()
    {
        try
        {
            _notifyIcon.Text = $"Slide Show - port {_state.Port}";
        }
        catch
        {
            _notifyIcon.Text = "Slide Show";
        }
    }

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
