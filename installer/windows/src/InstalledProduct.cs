using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace LatchSetup
{
    public class InstalledProduct
    {
        public const string ProductUpgradeCode = "{6F3B9C84-1D52-4E7A-9B06-2A8F5C14D7E3}";

        [DllImport("msi.dll", CharSet = CharSet.Unicode)]
        private static extern int MsiEnumRelatedProducts(string lpUpgradeCode, int dwReserved, int iProductIndex, StringBuilder lpProductBuf);

        public bool IsInstalled { get; set; }
        public string Version { get; set; }
        public string ExePath { get; set; }
        public string InstallDir { get; set; }
        public string UninstallString { get; set; }
        public string ProductCode { get; set; }
        public List<string> ProductCodes { get; set; }

        public InstalledProduct()
        {
            ProductCodes = new List<string>();
        }

        public static InstalledProduct Detect()
        {
            InstalledProduct result = new InstalledProduct();
            List<string> productCodes = new List<string>();

            // 1. Enumerate related product codes via MsiEnumRelatedProducts P/Invoke
            try
            {
                StringBuilder buf = new StringBuilder(39);
                int index = 0;
                while (MsiEnumRelatedProducts(ProductUpgradeCode, 0, index, buf) == 0)
                {
                    string code = buf.ToString();
                    if (!string.IsNullOrEmpty(code) && !productCodes.Contains(code))
                    {
                        productCodes.Add(code);
                    }
                    index++;
                }
            }
            catch { }

            // 2. Search HKCU and HKLM Uninstall keys for DisplayName == "Latch"
            string[] uninstallPaths = new string[]
            {
                @"Software\Microsoft\Windows\CurrentVersion\Uninstall",
                @"Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Uninstall"
            };

            RegistryKey[] rootHives = new RegistryKey[]
            {
                Registry.CurrentUser,
                Registry.LocalMachine
            };

            foreach (RegistryKey hive in rootHives)
            {
                foreach (string path in uninstallPaths)
                {
                    try
                    {
                        using (RegistryKey root = hive.OpenSubKey(path))
                        {
                            if (root == null) continue;
                            string[] subKeyNames = root.GetSubKeyNames();
                            foreach (string subKeyName in subKeyNames)
                            {
                                try
                                {
                                    using (RegistryKey subKey = root.OpenSubKey(subKeyName))
                                    {
                                        if (subKey == null) continue;
                                        object dispNameObj = subKey.GetValue("DisplayName");
                                        if (dispNameObj == null) continue;
                                        string dispName = dispNameObj.ToString().Trim();
                                        if (string.Equals(dispName, "Latch", StringComparison.OrdinalIgnoreCase))
                                        {
                                            string candidateCode = null;
                                            if (IsGuid(subKeyName))
                                            {
                                                candidateCode = subKeyName;
                                            }

                                            string uninstStr = subKey.GetValue("UninstallString") as string;
                                            if (string.IsNullOrEmpty(candidateCode) && !string.IsNullOrEmpty(uninstStr))
                                            {
                                                candidateCode = ExtractGuid(uninstStr);
                                            }

                                            if (!string.IsNullOrEmpty(candidateCode) && !productCodes.Contains(candidateCode))
                                            {
                                                productCodes.Add(candidateCode);
                                            }

                                            if (string.IsNullOrEmpty(result.Version))
                                            {
                                                result.Version = subKey.GetValue("DisplayVersion") as string;
                                            }
                                            if (string.IsNullOrEmpty(result.InstallDir))
                                            {
                                                result.InstallDir = subKey.GetValue("InstallLocation") as string;
                                            }
                                            if (string.IsNullOrEmpty(result.UninstallString))
                                            {
                                                result.UninstallString = uninstStr;
                                            }
                                        }
                                    }
                                }
                                catch { }
                            }
                        }
                    }
                    catch { }
                }
            }

            // 3. Inspect registry for any resolved ProductCodes where properties weren't populated
            foreach (string pc in productCodes)
            {
                if (!string.IsNullOrEmpty(result.Version) && !string.IsNullOrEmpty(result.InstallDir))
                    break;

                foreach (RegistryKey hive in rootHives)
                {
                    foreach (string path in uninstallPaths)
                    {
                        try
                        {
                            using (RegistryKey key = hive.OpenSubKey(path + @"\" + pc))
                            {
                                if (key != null)
                                {
                                    if (string.IsNullOrEmpty(result.Version))
                                        result.Version = key.GetValue("DisplayVersion") as string;
                                    if (string.IsNullOrEmpty(result.InstallDir))
                                        result.InstallDir = key.GetValue("InstallLocation") as string;
                                    if (string.IsNullOrEmpty(result.UninstallString))
                                        result.UninstallString = key.GetValue("UninstallString") as string;
                                }
                            }
                        }
                        catch { }
                    }
                }
            }

            result.ProductCodes = productCodes;
            if (productCodes.Count > 0)
            {
                result.ProductCode = productCodes[0];
                result.IsInstalled = true;
            }

            // 4. Resolve executable path
            string defaultInstallDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Latch"
            );
            string defaultExe = Path.Combine(defaultInstallDir, "Latch.exe");

            if (string.IsNullOrEmpty(result.InstallDir))
            {
                result.InstallDir = defaultInstallDir;
            }

            string candidateExe = Path.Combine(result.InstallDir, "Latch.exe");
            if (File.Exists(candidateExe))
            {
                result.ExePath = candidateExe;
                result.IsInstalled = true;
            }
            else if (File.Exists(defaultExe))
            {
                result.ExePath = defaultExe;
                result.InstallDir = defaultInstallDir;
                result.IsInstalled = true;
            }
            else if (result.IsInstalled)
            {
                result.ExePath = defaultExe;
            }

            // 5. Resolve version if missing from registry
            if (result.IsInstalled && string.IsNullOrEmpty(result.Version) && File.Exists(result.ExePath))
            {
                try
                {
                    FileVersionInfo fvi = FileVersionInfo.GetVersionInfo(result.ExePath);
                    result.Version = fvi.ProductVersion ?? fvi.FileVersion;
                }
                catch { }
            }

            if (result.Version != null)
            {
                result.Version = result.Version.Trim().TrimStart('v', 'V');
            }

            return result;
        }

        private static bool IsGuid(string s)
        {
            if (string.IsNullOrEmpty(s)) return false;
            s = s.Trim();
            if (s.StartsWith("{") && s.EndsWith("}") && s.Length == 38)
            {
                try
                {
                    new Guid(s);
                    return true;
                }
                catch
                {
                    return false;
                }
            }
            return false;
        }

        private static string ExtractGuid(string s)
        {
            if (string.IsNullOrEmpty(s)) return null;
            Match m = Regex.Match(s, @"\{[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}\}");
            return m.Success ? m.Value : null;
        }
    }
}
