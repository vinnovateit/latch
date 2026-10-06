using System;
using System.IO;
using System.Windows.Forms;

namespace LatchSetup
{
    static class Program
    {
        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        static int Main(string[] args)
        {
            try
            {
                SetProcessDPIAware();
            }
            catch { }

            // Set up unhandled exception logging
            AppDomain.CurrentDomain.UnhandledException += (sender, e) =>
            {
                Exception ex = e.ExceptionObject as Exception;
                Logger.Error("Fatal AppDomain unhandled exception", ex);
            };

            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += (sender, e) =>
            {
                Logger.Error("Unhandled UI ThreadException", e.Exception);
                ShowCrashDialog(e.Exception);
            };

            try
            {
                Logger.Info("========================================");
                Logger.Info("LatchSetup started. Args: " + string.Join(" ", args ?? new string[0]));
                Logger.Info("OS: " + Environment.OSVersion.VersionString + ", 64-bit: " + Environment.Is64BitOperatingSystem);
                Logger.Info("Base Directory: " + AppDomain.CurrentDomain.BaseDirectory);
                Logger.Info("Log File: " + Logger.LogFilePath);

                bool isSilent = HasArg(args, "--silent", "-s");
                bool isUninstall = HasArg(args, "--uninstall", "-u");
                bool isLaunch = HasArg(args, "--launch", "-l");
                bool isReinstall = HasArg(args, "--reinstall", "-r");

                if (isLaunch)
                {
                    Logger.Info("Executing --launch pipeline...");
                    InstalledProduct product = InstalledProduct.Detect();
                    if (product.IsInstalled)
                    {
                        bool launched = InstallerRunner.LaunchApp(product.ExePath);
                        Logger.Info("Launch result: " + launched);
                        return launched ? 0 : 1;
                    }
                    Logger.Warn("Application is not installed; cannot launch.");
                    return 1;
                }

                if (isSilent)
                {
                    Logger.Info("Executing silent pipeline...");
                    int code = RunSilentPipeline(isUninstall, isReinstall);
                    Logger.Info("Silent pipeline exit code: " + code);
                    return code;
                }

                // Normal interactive GUI
                Logger.Info("Initializing interactive GUI...");
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new SetupForm(args));
                Logger.Info("Interactive GUI exited normally.");
                return 0;
            }
            catch (Exception ex)
            {
                Logger.Error("Fatal exception in Program.Main", ex);
                ShowCrashDialog(ex);
                return 1;
            }
        }

        private static void ShowCrashDialog(Exception ex)
        {
            try
            {
                string msg = string.Format(
                    "Setup encountered an unexpected error:\n\n{0}\n\nLog file created at:\n{1}",
                    ex != null ? ex.Message.TrimEnd('.') : "Unknown error",
                    Logger.LogFilePath
                );
                MessageBox.Show(msg, "Setup Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }

        private static int RunSilentPipeline(bool isUninstall, bool isReinstall)
        {
            try
            {
                if (isUninstall)
                {
                    int exitCode;
                    InstallerRunner.Uninstall(out exitCode);
                    return exitCode;
                }

                InstalledProduct installed = InstalledProduct.Detect();
                ReleaseInfo release = ReleaseFetcher.FetchLatestRelease();

                int cmp = ReleaseFetcher.CompareVersions(release.Version, installed.Version);
                if (installed.IsInstalled && cmp <= 0 && !isReinstall)
                {
                    // Already up to date
                    return 0;
                }

                string tempMsi = Path.Combine(Path.GetTempPath(), "Latch-updates", "LatchSetup.msi");
                string computedHash;

                ReleaseFetcher.DownloadFile(
                    release.DownloadUrl,
                    tempMsi,
                    release.Size,
                    null,
                    out computedHash
                );

                if (!string.IsNullOrEmpty(release.ExpectedSha256) &&
                    !string.Equals(release.ExpectedSha256, computedHash, StringComparison.OrdinalIgnoreCase))
                {
                    return 2; // Checksum failure
                }

                int msiExit;
                string logTrace;
                bool ok = InstallerRunner.InstallMsi(tempMsi, isReinstall, out msiExit, out logTrace);
                if (ok)
                {
                    InstallerRunner.LaunchApp(installed.ExePath);
                }
                return msiExit;
            }
            catch
            {
                return 1;
            }
        }

        private static bool HasArg(string[] args, string flag1, string flag2)
        {
            if (args == null) return false;
            foreach (string a in args)
            {
                if (string.Equals(a, flag1, StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(a, flag2, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }
    }
}
