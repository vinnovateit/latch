using System;
using System.IO;

namespace LatchSetup
{
    public static class Logger
    {
        private static readonly object SyncLock = new object();
        private static string logFilePath;

        public static string LogFilePath
        {
            get
            {
                if (logFilePath == null)
                {
                    InitLogFile();
                }
                return logFilePath;
            }
        }

        private static void InitLogFile()
        {
            try
            {
                string tempDir = Path.Combine(Path.GetTempPath(), "Latch-installer");
                if (!Directory.Exists(tempDir))
                {
                    Directory.CreateDirectory(tempDir);
                }
                logFilePath = Path.Combine(tempDir, "installer.log");
            }
            catch
            {
                try
                {
                    string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                    string latchLogDir = Path.Combine(localAppData, "Latch-installer");
                    if (!Directory.Exists(latchLogDir))
                    {
                        Directory.CreateDirectory(latchLogDir);
                    }
                    logFilePath = Path.Combine(latchLogDir, "installer.log");
                }
                catch
                {
                    logFilePath = Path.Combine(Path.GetTempPath(), "LatchSetup.log");
                }
            }
        }

        public static void Info(string message)
        {
            Write("INFO", message);
        }

        public static void Warn(string message)
        {
            Write("WARN", message);
        }

        public static void Error(string message, Exception ex = null)
        {
            string detail = message;
            if (ex != null)
            {
                detail += Environment.NewLine + ex.ToString();
            }
            Write("ERROR", detail);
        }

        private static void Write(string level, string message)
        {
            lock (SyncLock)
            {
                try
                {
                    string path = LogFilePath;
                    string dir = Path.GetDirectoryName(path);
                    if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    {
                        Directory.CreateDirectory(dir);
                    }
                    string line = string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] [{1}] {2}", DateTime.Now, level, message);
                    File.AppendAllText(path, line + Environment.NewLine);
                }
                catch
                {
                    // Ignore write failures to prevent crash loops
                }
            }
        }
    }
}
