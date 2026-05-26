using System.Diagnostics;
using System.Threading;

namespace SlideShow;

internal static class Program
{
    private const string MutexName = @"Local\SlideShowTrayApp";

    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, MutexName, out var createdNew);
        if (!createdNew)
        {
            TryOpenExistingInstance();
            return;
        }

        ApplicationConfiguration.Initialize();
        using var context = new TrayApplicationContext(args.Any(a => string.Equals(a, "--minimized", StringComparison.OrdinalIgnoreCase)));
        Application.Run(context);
    }

    private static void TryOpenExistingInstance()
    {
        try
        {
            var settings = new SettingsStore().Load();
            Process.Start(new ProcessStartInfo
            {
                FileName = $"http://localhost:{settings.Port}/settings",
                UseShellExecute = true
            });
        }
        catch
        {
        }
    }
}
