using System;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace LatchSetup
{
    public class ReleaseInfo
    {
        public string Version { get; set; }
        public string DownloadUrl { get; set; }
        public string AssetName { get; set; }
        public long Size { get; set; }
        public string ExpectedSha256 { get; set; }
    }

    public static class ReleaseFetcher
    {
        private const string GitHubApiUrl = "https://api.github.com/repos/vinnovateit/latch/releases/latest";
        private const string GitHubFallbackUrl = "https://github.com/vinnovateit/latch/releases/latest";

        static ReleaseFetcher()
        {
            try
            {
                // Force TLS 1.2 / 1.3 support on older .NET runtime baselines
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072 | (SecurityProtocolType)768 | SecurityProtocolType.Tls;
                ServicePointManager.DefaultConnectionLimit = Math.Max(16, ServicePointManager.DefaultConnectionLimit);
            }
            catch
            {
                // Fall back to system default
            }
        }

        public static ReleaseInfo FetchLatestRelease()
        {
            string json = null;
            Exception lastEx = null;

            for (int attempt = 1; attempt <= 3; attempt++)
            {
                try
                {
                    HttpWebRequest request = (HttpWebRequest)WebRequest.Create(GitHubApiUrl);
                    request.Method = "GET";
                    request.UserAgent = "LatchSetup";
                    request.Accept = "application/vnd.github+json";
                    request.Timeout = 12000;
                    request.ReadWriteTimeout = 12000;

                    using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                    using (Stream stream = response.GetResponseStream())
                    using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
                    {
                        json = reader.ReadToEnd();
                    }
                    lastEx = null;
                    break;
                }
                catch (Exception ex)
                {
                    lastEx = ex;
                    Logger.Warn(string.Format("FetchLatestRelease attempt {0} failed: {1}", attempt, ex.Message));
                    if (attempt < 3 && IsNetworkOrDnsError(ex))
                    {
                        Thread.Sleep(1200);
                        continue;
                    }
                    break;
                }
            }

            if (json == null)
            {
                // If API is rate-limited or unavailable, fall back to checking release redirect
                try
                {
                    return FetchLatestViaRedirect();
                }
                catch (Exception redirectEx)
                {
                    if (lastEx != null && IsNetworkOrDnsError(lastEx)) throw lastEx;
                    throw redirectEx;
                }
            }

            return ParseReleaseJson(json);
        }

        private static ReleaseInfo FetchLatestViaRedirect()
        {
            Exception lastEx = null;
            for (int attempt = 1; attempt <= 2; attempt++)
            {
                try
                {
                    HttpWebRequest request = (HttpWebRequest)WebRequest.Create(GitHubFallbackUrl);
                    request.Method = "HEAD";
                    request.UserAgent = "LatchSetup";
                    request.AllowAutoRedirect = false;
                    request.Timeout = 10000;

                    string location = null;
                    using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                    {
                        location = response.Headers["Location"];
                    }

                    if (string.IsNullOrEmpty(location))
                    {
                        throw new Exception("Unable to resolve latest release URL.");
                    }

                    // e.g. https://github.com/vinnovateit/latch/releases/tag/v1.4.3
                    string tag = location.Substring(location.LastIndexOf('/') + 1).TrimStart('v', 'V');
                    return new ReleaseInfo
                    {
                        Version = tag,
                        AssetName = "LatchSetup.msi",
                        DownloadUrl = string.Format("https://github.com/vinnovateit/latch/releases/download/v{0}/LatchSetup.msi", tag),
                        Size = 0,
                        ExpectedSha256 = null
                    };
                }
                catch (Exception ex)
                {
                    lastEx = ex;
                    Logger.Warn(string.Format("FetchLatestViaRedirect attempt {0} failed: {1}", attempt, ex.Message));
                    if (attempt < 2 && IsNetworkOrDnsError(ex))
                    {
                        Thread.Sleep(1200);
                        continue;
                    }
                }
            }

            if (lastEx != null) throw lastEx;
            throw new Exception("Unable to resolve latest release URL.");
        }

        private static ReleaseInfo ParseReleaseJson(string json)
        {
            ReleaseInfo info = new ReleaseInfo();

            // Extract tag_name
            Match tagMatch = Regex.Match(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
            if (!tagMatch.Success)
            {
                throw new Exception("Could not find tag_name in release metadata.");
            }
            info.Version = tagMatch.Groups[1].Value.TrimStart('v', 'V');

            // Find assets array and locate LatchSetup.msi or *.msi
            // Matches asset blocks containing name and browser_download_url
            MatchCollection assetMatches = Regex.Matches(json, "\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}");
            foreach (Match match in assetMatches)
            {
                string block = match.Value;
                string name = Regex.Match(block, "\"name\"\\s*:\\s*\"([^\"]+)\"").Groups[1].Value;

                if (name.IndexOf("LatchSetup.msi", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    (name.StartsWith("Latch", StringComparison.OrdinalIgnoreCase) && name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase)))
                {
                    info.AssetName = name;

                    Match urlMatch = Regex.Match(block, "\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
                    if (urlMatch.Success) info.DownloadUrl = urlMatch.Groups[1].Value;

                    Match sizeMatch = Regex.Match(block, "\"size\"\\s*:\\s*([0-9]+)");
                    if (sizeMatch.Success)
                    {
                        long size;
                        if (long.TryParse(sizeMatch.Groups[1].Value, out size)) info.Size = size;
                    }

                    Match digestMatch = Regex.Match(block, "\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]+)\"");
                    if (digestMatch.Success) info.ExpectedSha256 = digestMatch.Groups[1].Value;

                    break;
                }
            }

            if (string.IsNullOrEmpty(info.DownloadUrl))
            {
                // Fallback default asset URL
                info.AssetName = "LatchSetup.msi";
                info.DownloadUrl = string.Format(
                    "https://github.com/vinnovateit/latch/releases/download/v{0}/LatchSetup.msi",
                    info.Version
                );
            }

            return info;
        }

        public static void DownloadFile(
            string url,
            string destinationPath,
            long expectedSize,
            Action<int, long, long> progressCallback,
            out string computedSha256)
        {
            string dir = Path.GetDirectoryName(destinationPath);
            if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);

            if (File.Exists(destinationPath))
            {
                FileInfo fi = new FileInfo(destinationPath);
                if ((expectedSize > 0 && fi.Length == expectedSize) || (expectedSize <= 0 && fi.Length > 10 * 1024 * 1024))
                {
                    computedSha256 = ComputeFileHash(destinationPath);
                    if (progressCallback != null)
                    {
                        progressCallback(100, fi.Length, fi.Length);
                    }
                    return;
                }
            }

            string resolvedUrl = url;
            long totalSize = expectedSize;

            for (int attempt = 1; attempt <= 3; attempt++)
            {
                try
                {
                    HttpWebRequest headReq = (HttpWebRequest)WebRequest.Create(url);
                    headReq.Method = "HEAD";
                    headReq.UserAgent = "LatchSetup";
                    headReq.Timeout = 15000;
                    headReq.AllowAutoRedirect = true;
                    using (HttpWebResponse headResp = (HttpWebResponse)headReq.GetResponse())
                    {
                        resolvedUrl = headResp.ResponseUri.AbsoluteUri;
                        if (headResp.ContentLength > 0)
                        {
                            totalSize = headResp.ContentLength;
                        }
                    }
                    break;
                }
                catch (Exception ex)
                {
                    Logger.Warn(string.Format("HEAD request attempt {0} failed: {1}", attempt, ex.Message));
                    if (attempt < 3 && IsNetworkOrDnsError(ex))
                    {
                        Thread.Sleep(1000);
                        continue;
                    }
                    break;
                }
            }

            if (totalSize >= 4 * 1024 * 1024)
            {
                DownloadFileMultiThreaded(resolvedUrl, destinationPath, totalSize, progressCallback, out computedSha256);
            }
            else
            {
                DownloadFileSingleStream(resolvedUrl, destinationPath, totalSize, progressCallback, out computedSha256);
            }
        }

        private static void DownloadFileMultiThreaded(
            string url,
            string destinationPath,
            long totalSize,
            Action<int, long, long> progressCallback,
            out string computedSha256)
        {
            const int threadCount = 4;
            long chunkSize = totalSize / threadCount;
            long[] starts = new long[threadCount];
            long[] ends = new long[threadCount];
            string[] partFiles = new string[threadCount];

            for (int i = 0; i < threadCount; i++)
            {
                starts[i] = i * chunkSize;
                ends[i] = (i == threadCount - 1) ? totalSize - 1 : (starts[i] + chunkSize - 1);
                partFiles[i] = string.Format("{0}.part{1}", destinationPath, i);
            }

            string metaFile = destinationPath + ".meta";
            string metaExpected = string.Format("{0}|{1}", url, totalSize);
            bool canResume = false;

            if (File.Exists(metaFile))
            {
                try
                {
                    string existingMeta = File.ReadAllText(metaFile).Trim();
                    if (string.Equals(existingMeta, metaExpected, StringComparison.Ordinal))
                    {
                        canResume = true;
                    }
                }
                catch { }
            }

            if (!canResume)
            {
                for (int i = 0; i < threadCount; i++)
                {
                    try { if (File.Exists(partFiles[i])) File.Delete(partFiles[i]); } catch { }
                }
                try { File.WriteAllText(metaFile, metaExpected); } catch { }
            }

            long[] downloadedPerPart = new long[threadCount];
            long totalDownloaded = 0;

            for (int i = 0; i < threadCount; i++)
            {
                if (File.Exists(partFiles[i]))
                {
                    long len = new FileInfo(partFiles[i]).Length;
                    long maxAllowed = ends[i] - starts[i] + 1;
                    if (len > maxAllowed)
                    {
                        try { File.Delete(partFiles[i]); } catch { }
                        len = 0;
                    }
                    downloadedPerPart[i] = len;
                    totalDownloaded += len;
                }
            }

            if (progressCallback != null && totalDownloaded > 0)
            {
                int initialPct = (int)((totalDownloaded * 100) / totalSize);
                progressCallback(Math.Min(100, initialPct), totalDownloaded, totalSize);
            }

            object progressLock = new object();
            long lastReportTick = 0;
            Exception threadEx = null;

            Action<int, int> onBytesRead = (threadIdx, bytes) =>
            {
                long current;
                lock (progressLock)
                {
                    downloadedPerPart[threadIdx] += bytes;
                    totalDownloaded += bytes;
                    current = totalDownloaded;
                }

                if (progressCallback != null)
                {
                    long now = Environment.TickCount;
                    if (now - lastReportTick > 30 || current >= totalSize)
                    {
                        lastReportTick = now;
                        int pct = (int)((current * 100) / totalSize);
                        if (pct > 100) pct = 100;
                        progressCallback(pct, current, totalSize);
                    }
                }
            };

            Thread[] threads = new Thread[threadCount];

            for (int t = 0; t < threadCount; t++)
            {
                int index = t;
                threads[t] = new Thread(() =>
                {
                    try
                    {
                        long chunkLength = ends[index] - starts[index] + 1;
                        long existing = downloadedPerPart[index];
                        int retries = 0;
                        const int maxRetries = 8;

                        while (existing < chunkLength)
                        {
                            try
                            {
                                long reqStart = starts[index] + existing;
                                long reqEnd = ends[index];

                                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                                req.Method = "GET";
                                req.UserAgent = "LatchSetup";
                                req.Timeout = 20000;
                                req.ReadWriteTimeout = 20000;
                                req.AddRange(reqStart, reqEnd);

                                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                                {
                                    if (resp.StatusCode != HttpStatusCode.PartialContent)
                                    {
                                        throw new NotSupportedException("Server does not support partial content ranges.");
                                    }

                                    using (Stream inStream = resp.GetResponseStream())
                                    using (FileStream outStream = new FileStream(partFiles[index], FileMode.Append, FileAccess.Write, FileShare.Read))
                                    {
                                        byte[] buf = new byte[32768];
                                        int read;
                                        while ((read = inStream.Read(buf, 0, buf.Length)) > 0)
                                        {
                                            outStream.Write(buf, 0, read);
                                            existing += read;
                                            onBytesRead(index, read);
                                        }
                                    }
                                }
                                retries = 0;
                            }
                            catch (NotSupportedException)
                            {
                                throw;
                            }
                            catch (Exception ex)
                            {
                                retries++;
                                Logger.Warn(string.Format("Thread {0} download retry {1}/{2}: {3}", index, retries, maxRetries, ex.Message));
                                if (retries >= maxRetries)
                                {
                                    throw;
                                }
                                Thread.Sleep(1000 * Math.Min(retries, 5));
                                if (File.Exists(partFiles[index]))
                                {
                                    existing = new FileInfo(partFiles[index]).Length;
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        lock (progressLock)
                        {
                            if (threadEx == null) threadEx = ex;
                        }
                    }
                })
                {
                    IsBackground = true
                };
                threads[t].Start();
            }

            for (int t = 0; t < threadCount; t++)
            {
                threads[t].Join();
            }

            if (threadEx != null)
            {
                if (threadEx is NotSupportedException)
                {
                    Logger.Warn("Range requests not supported by server. Falling back to single-stream download.");
                    for (int i = 0; i < threadCount; i++)
                    {
                        try { if (File.Exists(partFiles[i])) File.Delete(partFiles[i]); } catch { }
                    }
                    try { if (File.Exists(metaFile)) File.Delete(metaFile); } catch { }
                    DownloadFileSingleStream(url, destinationPath, totalSize, progressCallback, out computedSha256);
                    return;
                }
                if (IsNetworkOrDnsError(threadEx))
                {
                    throw new Exception("Download failed: No internet connection, please check your network and try again", threadEx);
                }
                throw threadEx;
            }

            string tempMerged = destinationPath + ".tmp";
            if (File.Exists(tempMerged)) File.Delete(tempMerged);

            using (FileStream outFs = new FileStream(tempMerged, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                byte[] copyBuf = new byte[65536];
                for (int i = 0; i < threadCount; i++)
                {
                    using (FileStream inFs = new FileStream(partFiles[i], FileMode.Open, FileAccess.Read, FileShare.Read))
                    {
                        int r;
                        while ((r = inFs.Read(copyBuf, 0, copyBuf.Length)) > 0)
                        {
                            outFs.Write(copyBuf, 0, r);
                        }
                    }
                }
            }

            for (int i = 0; i < threadCount; i++)
            {
                try { File.Delete(partFiles[i]); } catch { }
            }
            try { File.Delete(metaFile); } catch { }

            computedSha256 = ComputeFileHash(tempMerged);

            if (File.Exists(destinationPath)) File.Delete(destinationPath);
            File.Move(tempMerged, destinationPath);

            if (progressCallback != null)
            {
                progressCallback(100, totalSize, totalSize);
            }
        }

        private static void DownloadFileSingleStream(
            string url,
            string destinationPath,
            long expectedSize,
            Action<int, long, long> progressCallback,
            out string computedSha256)
        {
            string partPath = destinationPath + ".part";
            long existing = File.Exists(partPath) ? new FileInfo(partPath).Length : 0;
            long totalRead = existing;
            int retries = 0;
            const int maxRetries = 8;
            bool canResume = existing > 0;

            while (true)
            {
                try
                {
                    HttpWebRequest request = (HttpWebRequest)WebRequest.Create(url);
                    request.Method = "GET";
                    request.UserAgent = "LatchSetup";
                    request.Timeout = 30000;
                    request.ReadWriteTimeout = 30000;
                    if (canResume && existing > 0)
                    {
                        request.AddRange(existing);
                    }

                    using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                    {
                        bool isPartial = response.StatusCode == HttpStatusCode.PartialContent;
                        FileMode mode = (isPartial && existing > 0) ? FileMode.Append : FileMode.Create;
                        if (!isPartial)
                        {
                            existing = 0;
                            totalRead = 0;
                            canResume = false;
                        }

                        long contentLength = response.ContentLength > 0 ? (response.ContentLength + existing) : expectedSize;

                        using (Stream responseStream = response.GetResponseStream())
                        using (FileStream fileStream = new FileStream(partPath, mode, FileAccess.Write, FileShare.Read))
                        {
                            byte[] buffer = new byte[32768];
                            int bytesRead;
                            while ((bytesRead = responseStream.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                fileStream.Write(buffer, 0, bytesRead);
                                totalRead += bytesRead;
                                existing += bytesRead;

                                int percent = 0;
                                if (contentLength > 0)
                                {
                                    percent = (int)((totalRead * 100) / contentLength);
                                    if (percent > 100) percent = 100;
                                }

                                if (progressCallback != null)
                                {
                                    progressCallback(percent, totalRead, contentLength);
                                }
                            }
                        }

                        break;
                    }
                }
                catch (Exception ex)
                {
                    retries++;
                    Logger.Warn(string.Format("Single stream download retry {0}/{1}: {2}", retries, maxRetries, ex.Message));
                    if (retries >= maxRetries)
                    {
                        if (IsNetworkOrDnsError(ex))
                        {
                            throw new Exception("Download failed: No internet connection, please check your network and try again", ex);
                        }
                        throw;
                    }
                    Thread.Sleep(1000 * Math.Min(retries, 5));
                    if (File.Exists(partPath))
                    {
                        existing = new FileInfo(partPath).Length;
                        totalRead = existing;
                    }
                }
            }

            computedSha256 = ComputeFileHash(partPath);
            if (File.Exists(destinationPath)) File.Delete(destinationPath);
            File.Move(partPath, destinationPath);

            if (progressCallback != null)
            {
                progressCallback(100, totalRead, totalRead);
            }
        }

        private static string ComputeFileHash(string filePath)
        {
            using (FileStream fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (SHA256 sha256 = SHA256.Create())
            {
                byte[] hash = sha256.ComputeHash(fs);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < hash.Length; i++)
                {
                    sb.Append(hash[i].ToString("x2"));
                }
                return sb.ToString();
            }
        }

        public static int CompareVersions(string v1, string v2)
        {
            if (string.IsNullOrEmpty(v1) && string.IsNullOrEmpty(v2)) return 0;
            if (string.IsNullOrEmpty(v1)) return -1;
            if (string.IsNullOrEmpty(v2)) return 1;

            string[] p1 = v1.TrimStart('v', 'V').Split('.');
            string[] p2 = v2.TrimStart('v', 'V').Split('.');

            int max = Math.Max(p1.Length, p2.Length);
            for (int i = 0; i < max; i++)
            {
                int n1 = 0;
                int n2 = 0;
                if (i < p1.Length) int.TryParse(p1[i], out n1);
                if (i < p2.Length) int.TryParse(p2[i], out n2);

                if (n1 < n2) return -1;
                if (n1 > n2) return 1;
            }

            return 0;
        }

        public static bool IsNetworkOrDnsError(Exception ex)
        {
            if (ex == null) return false;
            try
            {
                if (!System.Net.NetworkInformation.NetworkInterface.GetIsNetworkAvailable()) return true;
            }
            catch { }

            if (ex is WebException)
            {
                WebException wex = (WebException)ex;
                if (wex.Status == WebExceptionStatus.NameResolutionFailure ||
                    wex.Status == WebExceptionStatus.ConnectFailure ||
                    wex.Status == WebExceptionStatus.Timeout ||
                    wex.Status == WebExceptionStatus.ProxyNameResolutionFailure ||
                    wex.Status == WebExceptionStatus.SendFailure ||
                    wex.Status == WebExceptionStatus.ReceiveFailure ||
                    wex.Status == WebExceptionStatus.ConnectionClosed)
                {
                    return true;
                }
            }
            if (ex is System.Net.Sockets.SocketException) return true;
            if (ex.InnerException != null && IsNetworkOrDnsError(ex.InnerException)) return true;
            return false;
        }
    }
}
