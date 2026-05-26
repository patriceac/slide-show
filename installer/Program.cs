using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using Microsoft.Win32;

namespace SlideShow.Installer;

internal static class Program
{
    private const string AppName = "Slide Show";
    private const string AppExeName = "SlideShow.exe";
    private const string SetupExeName = "SlideShowSetup.exe";
    private const string PayloadResourceName = "SlideShow.Installer.Payload.zip";
    private const string RunValueName = "Slide Show";
    private const string Publisher = "Slide Show";
    private const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Slide Show";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        var silent = args.Any(arg => string.Equals(arg, "--silent", StringComparison.OrdinalIgnoreCase));
        if (args.Any(arg => string.Equals(arg, "--uninstall", StringComparison.OrdinalIgnoreCase)))
        {
            RunUninstall(silent);
            return;
        }

        if (silent)
        {
            RunSilentInstall(args);
            return;
        }

        Application.Run(new InstallForm());
    }

    private static void RunSilentInstall(string[] args)
    {
        var launchAfterInstall = !args.Any(arg => string.Equals(arg, "--no-launch", StringComparison.OrdinalIgnoreCase));

        try
        {
            InstallerActions.Install(launchAfterInstall, (_, _) => { });
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            Environment.ExitCode = 1;
        }
    }

    private static void RunUninstall(bool silent)
    {
        if (!silent)
        {
            var response = MessageBox.Show(
                "Remove Slide Show from this computer?",
                "Uninstall Slide Show",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2);

            if (response != DialogResult.Yes)
            {
                return;
            }
        }

        try
        {
            InstallerActions.Uninstall();
            if (!silent)
            {
                MessageBox.Show("Slide Show has been removed.", "Uninstall Slide Show", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }
        catch (Exception ex)
        {
            if (!silent)
            {
                MessageBox.Show(ex.Message, "Uninstall failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    private sealed class InstallForm : Form
    {
        private readonly Label _statusLabel;
        private readonly ProgressBar _progressBar;
        private readonly Button _installButton;
        private readonly Button _cancelButton;
        private readonly CheckBox _launchCheckBox;

        public InstallForm()
        {
            Text = "Install Slide Show";
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new Size(520, 310);
            BackColor = Color.White;
            Font = new Font("Segoe UI", 9F);
            Icon = LoadIcon();

            var titleLabel = new Label
            {
                Text = "Install Slide Show",
                Font = new Font("Segoe UI", 18F, FontStyle.Bold),
                AutoSize = false,
                Location = new Point(28, 26),
                Size = new Size(464, 40)
            };

            var bodyLabel = new Label
            {
                Text = "This installs the local slideshow server, adds a Start Menu shortcut, and registers an uninstaller for your user account.",
                AutoSize = false,
                Location = new Point(30, 78),
                Size = new Size(458, 42)
            };

            var installPathLabel = new Label
            {
                Text = InstallerActions.InstallDirectory,
                AutoEllipsis = true,
                BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.FromArgb(248, 249, 251),
                Location = new Point(30, 132),
                Padding = new Padding(8, 6, 8, 0),
                Size = new Size(458, 34)
            };

            _launchCheckBox = new CheckBox
            {
                Text = "Launch Slide Show after installing",
                Checked = true,
                AutoSize = true,
                Location = new Point(30, 184)
            };

            _statusLabel = new Label
            {
                Text = "Ready to install",
                AutoSize = false,
                Location = new Point(30, 216),
                Size = new Size(458, 22)
            };

            _progressBar = new ProgressBar
            {
                Location = new Point(30, 242),
                Size = new Size(458, 8),
                Style = ProgressBarStyle.Continuous
            };

            _installButton = new Button
            {
                Text = "Install",
                Location = new Point(308, 266),
                Size = new Size(86, 30),
                UseVisualStyleBackColor = true
            };
            _installButton.Click += InstallButton_Click;

            _cancelButton = new Button
            {
                Text = "Cancel",
                Location = new Point(402, 266),
                Size = new Size(86, 30),
                UseVisualStyleBackColor = true
            };
            _cancelButton.Click += (_, _) => Close();

            Controls.AddRange([
                titleLabel,
                bodyLabel,
                installPathLabel,
                _launchCheckBox,
                _statusLabel,
                _progressBar,
                _installButton,
                _cancelButton
            ]);
        }

        private async void InstallButton_Click(object? sender, EventArgs e)
        {
            _installButton.Enabled = false;
            _cancelButton.Enabled = false;
            _launchCheckBox.Enabled = false;
            _progressBar.Value = 5;

            try
            {
                await Task.Run(() => InstallerActions.Install(_launchCheckBox.Checked, ReportProgress));
                _progressBar.Value = 100;
                _statusLabel.Text = "Installed successfully";

                MessageBox.Show(
                    "Slide Show is installed. You can launch it from the Start Menu whenever you need the server.",
                    "Install Slide Show",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);

                Close();
            }
            catch (Exception ex)
            {
                _statusLabel.Text = "Install failed";
                _progressBar.Value = 0;
                MessageBox.Show(ex.Message, "Install failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
                _installButton.Enabled = true;
                _cancelButton.Enabled = true;
                _launchCheckBox.Enabled = true;
            }
        }

        private void ReportProgress(int percent, string message)
        {
            if (InvokeRequired)
            {
                BeginInvoke((Action)(() => ReportProgress(percent, message)));
                return;
            }

            _progressBar.Value = Math.Clamp(percent, 0, 100);
            _statusLabel.Text = message;
        }

        private static Icon? LoadIcon()
        {
            try
            {
                var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
                return File.Exists(iconPath) ? new Icon(iconPath) : null;
            }
            catch
            {
                return null;
            }
        }
    }

    private static class InstallerActions
    {
        public static string InstallDirectory =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", AppName);

        private static string ProgramsDirectory =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), @"Microsoft\Windows\Start Menu\Programs", AppName);

        public static void Install(bool launchAfterInstall, Action<int, string> progress)
        {
            progress(10, "Closing any running copy");
            StopRunningApp();

            progress(20, "Preparing files");
            var tempRoot = Path.Combine(Path.GetTempPath(), "SlideShowInstall-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempRoot);

            try
            {
                ExtractPayload(tempRoot);

                progress(45, "Installing application");
                ReplaceDirectoryContents(tempRoot, InstallDirectory);
                CopyInstallerToInstallDirectory();

                progress(70, "Adding Start Menu shortcut");
                CreateStartMenuShortcuts();

                progress(85, "Registering uninstaller");
                RegisterUninstaller();

                if (launchAfterInstall)
                {
                    progress(95, "Launching Slide Show");
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = Path.Combine(InstallDirectory, AppExeName),
                        WorkingDirectory = InstallDirectory,
                        UseShellExecute = true
                    });
                }
            }
            finally
            {
                TryDeleteDirectory(tempRoot);
            }
        }

        public static void Uninstall()
        {
            StopRunningApp();
            RemoveStartMenuShortcuts();
            RemoveRunEntryIfInstalledPath();
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKeyPath, false);

            TryDeleteDirectory(InstallDirectory);
            if (Directory.Exists(InstallDirectory))
            {
                ScheduleDirectoryRemoval(InstallDirectory);
            }
        }

        private static void ExtractPayload(string targetDirectory)
        {
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(PayloadResourceName)
                ?? throw new InvalidOperationException("The installer payload is missing. Rebuild the installer with installer\\build-installer.ps1.");

            using var archive = new ZipArchive(stream, ZipArchiveMode.Read);
            archive.ExtractToDirectory(targetDirectory, true);
        }

        private static void ReplaceDirectoryContents(string sourceDirectory, string destinationDirectory)
        {
            Directory.CreateDirectory(destinationDirectory);

            foreach (var file in Directory.EnumerateFiles(destinationDirectory))
            {
                File.SetAttributes(file, FileAttributes.Normal);
                File.Delete(file);
            }

            foreach (var directory in Directory.EnumerateDirectories(destinationDirectory))
            {
                Directory.Delete(directory, true);
            }

            CopyDirectory(sourceDirectory, destinationDirectory);
        }

        private static void CopyDirectory(string sourceDirectory, string destinationDirectory)
        {
            Directory.CreateDirectory(destinationDirectory);

            foreach (var directory in Directory.EnumerateDirectories(sourceDirectory, "*", SearchOption.AllDirectories))
            {
                Directory.CreateDirectory(directory.Replace(sourceDirectory, destinationDirectory, StringComparison.OrdinalIgnoreCase));
            }

            foreach (var file in Directory.EnumerateFiles(sourceDirectory, "*", SearchOption.AllDirectories))
            {
                var destination = file.Replace(sourceDirectory, destinationDirectory, StringComparison.OrdinalIgnoreCase);
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                File.Copy(file, destination, true);
            }
        }

        private static void CopyInstallerToInstallDirectory()
        {
            var currentInstaller = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(currentInstaller) || !File.Exists(currentInstaller))
            {
                return;
            }

            File.Copy(currentInstaller, Path.Combine(InstallDirectory, SetupExeName), true);
        }

        private static void CreateStartMenuShortcuts()
        {
            Directory.CreateDirectory(ProgramsDirectory);

            var appPath = Path.Combine(InstallDirectory, AppExeName);
            var iconPath = Path.Combine(InstallDirectory, "Assets", "AppIcon.ico");
            CreateShortcut(
                Path.Combine(ProgramsDirectory, "Slide Show.lnk"),
                appPath,
                "",
                InstallDirectory,
                iconPath,
                "Launch the Slide Show server and control center");

            var uninstallPath = Path.Combine(InstallDirectory, SetupExeName);
            CreateShortcut(
                Path.Combine(ProgramsDirectory, "Uninstall Slide Show.lnk"),
                uninstallPath,
                "--uninstall",
                InstallDirectory,
                iconPath,
                "Remove Slide Show from this computer");
        }

        private static void RemoveStartMenuShortcuts()
        {
            TryDeleteDirectory(ProgramsDirectory);
        }

        private static void RegisterUninstaller()
        {
            using var key = Registry.CurrentUser.CreateSubKey(UninstallKeyPath);
            if (key is null)
            {
                return;
            }

            var setupPath = Path.Combine(InstallDirectory, SetupExeName);
            var appPath = Path.Combine(InstallDirectory, AppExeName);

            key.SetValue("DisplayName", AppName);
            key.SetValue("DisplayVersion", GetVersion());
            key.SetValue("Publisher", Publisher);
            key.SetValue("InstallLocation", InstallDirectory);
            key.SetValue("DisplayIcon", $"\"{appPath}\"");
            key.SetValue("UninstallString", $"\"{setupPath}\" --uninstall");
            key.SetValue("QuietUninstallString", $"\"{setupPath}\" --uninstall --silent");
            key.SetValue("NoModify", 1, RegistryValueKind.DWord);
            key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            key.SetValue("EstimatedSize", EstimateInstalledSizeKb(), RegistryValueKind.DWord);
        }

        private static int EstimateInstalledSizeKb()
        {
            try
            {
                return (int)Math.Max(1, Directory.EnumerateFiles(InstallDirectory, "*", SearchOption.AllDirectories).Sum(file => new FileInfo(file).Length) / 1024);
            }
            catch
            {
                return 1;
            }
        }

        private static string GetVersion()
        {
            return Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.0.0";
        }

        private static void RemoveRunEntryIfInstalledPath()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, true);
                var value = key?.GetValue(RunValueName) as string;
                if (value is not null && value.Contains(InstallDirectory, StringComparison.OrdinalIgnoreCase))
                {
                    key?.DeleteValue(RunValueName, false);
                }
            }
            catch
            {
            }
        }

        private static void StopRunningApp()
        {
            var appPath = Path.Combine(InstallDirectory, AppExeName);

            foreach (var process in Process.GetProcessesByName("SlideShow"))
            {
                try
                {
                    if (!string.IsNullOrWhiteSpace(process.MainModule?.FileName) &&
                        !process.MainModule.FileName.Equals(appPath, StringComparison.OrdinalIgnoreCase) &&
                        !process.MainModule.FileName.Contains(@"\Slide Show\", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }

                    process.Kill();
                    process.WaitForExit(5000);
                }
                catch
                {
                }
                finally
                {
                    process.Dispose();
                }
            }
        }

        private static void TryDeleteDirectory(string path)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    Directory.Delete(path, true);
                }
            }
            catch
            {
            }
        }

        private static void ScheduleDirectoryRemoval(string path)
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c timeout /t 2 /nobreak > nul & rmdir /s /q \"{path}\"",
                    WindowStyle = ProcessWindowStyle.Hidden,
                    CreateNoWindow = true,
                    UseShellExecute = false
                });
            }
            catch
            {
            }
        }

        private static void CreateShortcut(string shortcutPath, string targetPath, string arguments, string workingDirectory, string iconPath, string description)
        {
            var shellType = Type.GetTypeFromProgID("WScript.Shell")
                ?? throw new InvalidOperationException("Windows shortcut support is unavailable on this system.");
            var shell = Activator.CreateInstance(shellType)
                ?? throw new InvalidOperationException("Unable to create a Windows shortcut.");

            try
            {
                var shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, [shortcutPath])
                    ?? throw new InvalidOperationException("Unable to create a Windows shortcut.");
                var shortcutType = shortcut.GetType();

                shortcutType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, [targetPath]);
                shortcutType.InvokeMember("Arguments", BindingFlags.SetProperty, null, shortcut, [arguments]);
                shortcutType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, [workingDirectory]);
                shortcutType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, [iconPath]);
                shortcutType.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut, [description]);
                shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, []);
            }
            finally
            {
                if (shell is IDisposable disposable)
                {
                    disposable.Dispose();
                }
            }
        }
    }
}
