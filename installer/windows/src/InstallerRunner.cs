using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Principal;
using System.Threading;
using Microsoft.Win32;

namespace LatchSetup
{
    public static class InstallerRunner
    {
        public static bool IsAdministrator()
        {
            try
            {
                WindowsIdentity identity = WindowsIdentity.GetCurrent();
                WindowsPrincipal principal = new WindowsPrincipal(identity);
                return principal.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch
            {
                return false;
            }
        }

        public static void StopRunningProcesses()
        {
            // 1. Terminate running Latch application instances
            try
            {
                Process[] latchProcesses = Process.GetProcessesByName("Latch");
                foreach (Process proc in latchProcesses)
                {
                    try
                    {
                        if (!proc.HasExited)
                        {
                            proc.CloseMainWindow();
                            if (!proc.WaitForExit(1500))
                            {
                                proc.Kill();
                                proc.WaitForExit(1000);
                            }
                        }
                    }
                    catch (System.ComponentModel.Win32Exception)
                    {
                        TryKillProcessElevated(proc.Id);
                    }
                    catch { }
                    finally
                    {
                        proc.Dispose();
                    }
                }
            }
            catch { }

            // 2. Terminate any other LatchSetup instances
            try
            {
                int currentPid = Process.GetCurrentProcess().Id;
                Process[] setupProcesses = Process.GetProcessesByName("LatchSetup");
                foreach (Process proc in setupProcesses)
                {
                    try
                    {
                        if (proc.Id != currentPid && !proc.HasExited)
                        {
                            proc.Kill();
                            proc.WaitForExit(1000);
                        }
                    }
                    catch (System.ComponentModel.Win32Exception)
                    {
                        TryKillProcessElevated(proc.Id);
                    }
                    catch { }
                    finally
                    {
                        proc.Dispose();
                    }
                }
            }
            catch { }
        }

        private static void TryKillProcessElevated(int pid)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "taskkill.exe",
                    Arguments = string.Format("/F /PID {0}", pid),
                    UseShellExecute = true,
                    Verb = "runas",
                    WindowStyle = ProcessWindowStyle.Hidden
                };
                using (Process p = Process.Start(psi))
                {
                    if (p != null) p.WaitForExit(2000);
                }
            }
            catch { }
        }

        public static bool RunMsi(string arguments, out int exitCode)
        {
            string systemRoot = Environment.GetFolderPath(Environment.SpecialFolder.System);
            string msiexecPath = Path.Combine(systemRoot, "msiexec.exe");

            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = msiexecPath,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            using (Process process = Process.Start(psi))
            {
                process.WaitForExit();
                exitCode = process.ExitCode;
                // 0 = Success, 3010 = Reboot required but successful
                return exitCode == 0 || exitCode == 3010;
            }
        }

        public static bool RunMsiElevated(string arguments, out int exitCode, out string logTrace)
        {
            string systemRoot = Environment.GetFolderPath(Environment.SpecialFolder.System);
            string msiexecPath = Path.Combine(systemRoot, "msiexec.exe");

            ProcessStartInfo psi = new ProcessStartInfo
            {
                FileName = msiexecPath,
                Arguments = arguments,
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden
            };

            try
            {
                using (Process process = Process.Start(psi))
                {
                    process.WaitForExit();
                    exitCode = process.ExitCode;
                    logTrace = string.Empty;
                    return exitCode == 0 || exitCode == 3010;
                }
            }
            catch (Exception ex)
            {
                exitCode = -1;
                logTrace = "Elevation request failed: " + ex.Message;
                return false;
            }
        }

        public static bool InstallMsi(string msiPath, bool forceReinstall, out int exitCode, out string logTrace)
        {
            StopRunningProcesses();

            if (forceReinstall)
            {
                InstalledProduct installed = InstalledProduct.Detect();
                if (installed.IsInstalled && installed.ProductCodes != null && installed.ProductCodes.Count > 0)
                {
                    foreach (string pc in installed.ProductCodes)
                    {
                        int unExit;
                        string unTrace;
                        Uninstall(pc, out unExit, out unTrace);
                    }
                }
            }

            string logDir = Path.Combine(Path.GetTempPath(), "Latch-updates");
            if (!Directory.Exists(logDir)) Directory.CreateDirectory(logDir);

            string logPath = Path.Combine(logDir, "msiexec-setup.log");
            string args = string.Format("/i \"{0}\" /qn /norestart /L*V \"{1}\"", msiPath, logPath);
            bool ok = RunMsi(args, out exitCode);

            // If installation failed due to privilege/access denial, attempt elevated execution
            if (!ok && (exitCode == 5 || exitCode == 1603) && !IsAdministrator())
            {
                int elevatedExit;
                string elevatedTrace;
                if (RunMsiElevated(args, out elevatedExit, out elevatedTrace))
                {
                    exitCode = elevatedExit;
                    logTrace = string.Empty;
                    return true;
                }
            }

            if (!ok)
            {
                logTrace = ExtractLogTrace(logPath);
                if (string.IsNullOrEmpty(logTrace))
                {
                    logTrace = GetMsiExitCodeMessage(exitCode);
                }
            }
            else
            {
                logTrace = string.Empty;
            }

            return ok;
        }

        public static bool InstallMsi(string msiPath, out int exitCode, out string logTrace)
        {
            return InstallMsi(msiPath, false, out exitCode, out logTrace);
        }

        public static bool InstallMsi(string msiPath, out int exitCode)
        {
            string logTrace;
            return InstallMsi(msiPath, false, out exitCode, out logTrace);
        }

        public static bool Reinstall(string msiPath, out int exitCode, out string logTrace)
        {
            return InstallMsi(msiPath, true, out exitCode, out logTrace);
        }

        public static bool Reinstall(string msiPath, out int exitCode)
        {
            string logTrace;
            return InstallMsi(msiPath, true, out exitCode, out logTrace);
        }

        public static bool Uninstall(string productCode, out int exitCode, out string logTrace)
        {
            StopRunningProcesses();

            string logDir = Path.Combine(Path.GetTempPath(), "Latch-updates");
            if (!Directory.Exists(logDir)) Directory.CreateDirectory(logDir);

            string logPath = Path.Combine(logDir, "msiexec-uninstall.log");
            string target = !string.IsNullOrEmpty(productCode) ? productCode : InstalledProduct.ProductUpgradeCode;
            string args = string.Format("/x {0} /qn /norestart /L*V \"{1}\"", target, logPath);
            bool ok = RunMsi(args, out exitCode);

            if (!ok && (exitCode == 5 || exitCode == 1603) && !IsAdministrator())
            {
                int elevatedExit;
                string elevatedTrace;
                if (RunMsiElevated(args, out elevatedExit, out elevatedTrace))
                {
                    exitCode = elevatedExit;
                    logTrace = string.Empty;
                    RemoveCredentialStore();
                    return true;
                }
            }

            if (!ok)
            {
                logTrace = ExtractLogTrace(logPath);
                if (string.IsNullOrEmpty(logTrace))
                {
                    logTrace = GetMsiExitCodeMessage(exitCode);
                }
            }
            else
            {
                logTrace = string.Empty;
            }

            if (ok || exitCode == 0 || exitCode == 1605)
            {
                RemoveCredentialStore();
            }

            return ok;
        }

        public static void RemoveCredentialStore()
        {
            try
            {
                Logger.Info("Removing credential store and associated auth artifacts...");

                List<string> candidateDirs = new List<string>();

                string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                if (!string.IsNullOrEmpty(localAppData))
                {
                    candidateDirs.Add(Path.Combine(localAppData, "VinnovateIT", "Latch"));
                    candidateDirs.Add(Path.Combine(localAppData, "Latch"));
                }

                string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
                if (!string.IsNullOrEmpty(userProfile))
                {
                    string fallbackLocal = Path.Combine(userProfile, "AppData", "Local");
                    candidateDirs.Add(Path.Combine(fallbackLocal, "VinnovateIT", "Latch"));
                    candidateDirs.Add(Path.Combine(fallbackLocal, "Latch"));
                }

                try
                {
                    InstalledProduct installed = InstalledProduct.Detect();
                    if (installed != null && !string.IsNullOrEmpty(installed.InstallDir))
                    {
                        candidateDirs.Add(installed.InstallDir);
                    }
                }
                catch { }

                string[] patterns = new string[]
                {
                    "credentials.bin*",
                    ".runtime.token*",
                    ".runtime.lock*",
                    ".runtime.json"
                };

                foreach (string dir in candidateDirs)
                {
                    if (string.IsNullOrEmpty(dir) || !Directory.Exists(dir)) continue;

                    foreach (string pattern in patterns)
                    {
                        try
                        {
                            string[] files = Directory.GetFiles(dir, pattern);
                            foreach (string file in files)
                            {
                                for (int attempt = 0; attempt < 3; attempt++)
                                {
                                    try
                                    {
                                        if (!File.Exists(file)) break;
                                        File.SetAttributes(file, FileAttributes.Normal);
                                        File.Delete(file);
                                        Logger.Info("Removed credential/auth file: " + file);
                                        break;
                                    }
                                    catch (Exception ex)
                                    {
                                        if (attempt == 2)
                                        {
                                            Logger.Warn(string.Format("Failed to delete file {0}: {1}", file, ex.Message));
                                        }
                                        else
                                        {
                                            Thread.Sleep(50);
                                        }
                                    }
                                }
                            }
                        }
                        catch (Exception pex)
                        {
                            Logger.Warn(string.Format("Failed pattern search {0} in {1}: {2}", pattern, dir, pex.Message));
                        }
                    }
                }

                // Clean registry autostart entry if left over
                try
                {
                    using (RegistryKey runKey = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true))
                    {
                        if (runKey != null && runKey.GetValue("Latch") != null)
                        {
                            runKey.DeleteValue("Latch", false);
                            Logger.Info("Removed Latch from registry Run key.");
                        }
                    }
                }
                catch (Exception rex)
                {
                    Logger.Warn("Failed to clean registry autostart value: " + rex.Message);
                }
            }
            catch (Exception ex)
            {
                Logger.Warn("Exception in RemoveCredentialStore: " + ex.Message);
            }
        }

        public static bool Uninstall(out int exitCode, out string logTrace)
        {
            InstalledProduct installed = InstalledProduct.Detect();
            bool result = false;
            if (installed.ProductCodes != null && installed.ProductCodes.Count > 0)
            {
                bool allOk = true;
                exitCode = 0;
                logTrace = string.Empty;
                foreach (string pc in installed.ProductCodes)
                {
                    int ec;
                    string lt;
                    bool ok = Uninstall(pc, out ec, out lt);
                    if (!ok)
                    {
                        allOk = false;
                        exitCode = ec;
                        logTrace = lt;
                    }
                }
                result = allOk;
            }
            else
            {
                result = Uninstall(InstalledProduct.ProductUpgradeCode, out exitCode, out logTrace);
            }

            if (result || exitCode == 0 || exitCode == 1605)
            {
                RemoveCredentialStore();
            }

            return result;
        }

        public static bool Uninstall(out int exitCode)
        {
            string logTrace;
            return Uninstall(out exitCode, out logTrace);
        }

        public static string ExtractLogTrace(string logPath)
        {
            if (!File.Exists(logPath)) return string.Empty;

            try
            {
                string[] lines;
                using (FileStream stream = new FileStream(logPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                using (StreamReader reader = new StreamReader(stream, true))
                {
                    List<string> lineList = new List<string>();
                    string line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        lineList.Add(line);
                    }
                    lines = lineList.ToArray();
                }

                List<string> matches = new List<string>();
                foreach (string line in lines)
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    string l = line.Trim();
                    if (l.StartsWith("===")) continue;
                    if (l.IndexOf("returning", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("Configuration failed", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("Another version", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("Return value 3", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("Product:", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("Error ", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        l.IndexOf("fatal", StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        matches.Add(l);
                    }
                }

                if (matches.Count == 0)
                {
                    for (int i = Math.Max(0, lines.Length - 15); i < lines.Length; i++)
                    {
                        if (!string.IsNullOrWhiteSpace(lines[i]))
                            matches.Add(lines[i].Trim());
                    }
                }

                if (matches.Count > 10)
                {
                    matches = matches.GetRange(matches.Count - 10, 10);
                }

                return string.Join(Environment.NewLine, matches.ToArray());
            }
            catch (Exception ex)
            {
                return "Failed to read log trace: " + ex.Message;
            }
        }

        public static string GetMsiExitCodeMessage(int exitCode)
        {
            switch (exitCode)
            {
                case 1602: return "Installation canceled by user (Code 1602)";
                case 1603: return "Fatal error during installation (Code 1603)";
                case 1605: return "This action is only valid for products that are currently installed (Code 1605)";
                case 1618: return "Another installation is already in progress (Code 1618)";
                case 1619: return "Installation package could not be opened (Code 1619)";
                case 1638: return "Another version of this product is already installed (Code 1638)";
                default: return string.Format("Process exited with code {0}", exitCode);
            }
        }

        public static bool LaunchApp(string exePath)
        {
            if (string.IsNullOrEmpty(exePath) || !File.Exists(exePath))
            {
                string defaultExe = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "Latch",
                    "Latch.exe"
                );
                if (File.Exists(defaultExe)) exePath = defaultExe;
                else return false;
            }

            try
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    WorkingDirectory = Path.GetDirectoryName(exePath),
                    UseShellExecute = true
                };
                Process.Start(psi);
                return true;
            }
            catch
            {
                return false;
            }
        }
    }
}
