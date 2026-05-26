using Microsoft.Win32;

namespace SlideShow;

public static class StartupManager
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "Slide Show";

    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, false);
            return key?.GetValue(ValueName) is string;
        }
        catch
        {
            return false;
        }
    }

    public static void SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true);
            if (key is null)
            {
                return;
            }

            if (enabled)
            {
                var executable = Environment.ProcessPath ?? Application.ExecutablePath;
                key.SetValue(ValueName, $"\"{executable}\" --minimized");
            }
            else
            {
                key.DeleteValue(ValueName, false);
            }
        }
        catch
        {
        }
    }
}
