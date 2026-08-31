package com.vinnovateit.latch.common.util

import com.vinnovateit.latch.domain.model.SessionSummary
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates an HTML report from a list of session summaries and writes it to an OutputStream.
 */
fun generateHtmlReport(sessions: List<SessionSummary>, outputStream: OutputStream, appVersion: String) {
  val writer = outputStream.bufferedWriter()
  val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
  val generatedAt = dateFormat.format(Date())

  val html = buildString {
    appendLine("<!DOCTYPE html>")
    appendLine("<html lang=\"en\">")
    appendLine("<head>")
    appendLine("    <meta charset=\"UTF-8\">")
    appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
    appendLine("    <title>Latch Session Report</title>")
    appendLine("    <style>")
    appendLine("        body { font-family: monospace; margin: 20px; background-color: #000; color: #fff; }")
    appendLine("        h1 { margin-top: 0; font-size: 24px; border-bottom: 1px solid #fff; padding-bottom: 10px; }")
    appendLine("        .summary { margin-bottom: 20px; font-size: 14px; }")
    appendLine("        .summary p { margin: 5px 0; }")
    appendLine("        table { width: 100%; border-collapse: collapse; margin-top: 20px; font-size: 14px; }")
    appendLine("        th, td { padding: 8px 10px; text-align: left; border: 1px solid #fff; }")
    appendLine("        th { font-weight: bold; }")
    appendLine("        .footer { margin-top: 30px; font-size: 12px; color: #555; }")
    appendLine("    </style>")
    appendLine("</head>")
    appendLine("<body>")
    appendLine("    <h1>Latch Network Usage Report</h1>")
    appendLine("    <div class=\"summary\">")
    appendLine("        <p><strong>App Version:</strong> $appVersion</p>")
    appendLine("        <p><strong>Generated At:</strong> $generatedAt</p>")
    appendLine("        <p><strong>Total Sessions Logged:</strong> ${sessions.size}</p>")
    appendLine("    </div>")
    appendLine("    <table>")
    appendLine("        <thead>")
    appendLine("            <tr>")
    appendLine("                <th>Start Time</th>")
    appendLine("                <th>End Time</th>")
    appendLine("                <th>Duration (Min)</th>")
    appendLine("                <th>Total Data (MB)</th>")
    appendLine("                <th>Download (MB)</th>")
    appendLine("                <th>Upload (MB)</th>")
    appendLine("                <th>Max DL (Mbps)</th>")
    appendLine("                <th>Max UL (Mbps)</th>")
    appendLine("            </tr>")
    appendLine("        </thead>")
    appendLine("        <tbody>")

    if (sessions.isEmpty()) {
      appendLine("            <tr><td colspan=\"8\" style=\"text-align: center;\">No session data available.</td></tr>")
    } else {
      sessions.forEach { session ->
        val startTime = dateFormat.format(Date(session.startTimestamp))
        val endTime = dateFormat.format(Date(session.endTimestamp))
        val durationMinutes = (session.endTimestamp - session.startTimestamp) / 60000.0
        val totalDataMb = (session.totalData.rxBytes + session.totalData.txBytes) / 1048576.0
        val downloadMb = session.totalData.rxBytes / 1048576.0
        val uploadMb = session.totalData.txBytes / 1048576.0
        val maxDownloadMbps = session.maxRxBps * 8 / 1000000.0
        val maxUploadMbps = session.maxTxBps * 8 / 1000000.0

        appendLine("            <tr>")
        appendLine("                <td>$startTime</td>")
        appendLine("                <td>$endTime</td>")
        appendLine("                <td>${"%.2f".format(durationMinutes)}</td>")
        appendLine("                <td>${"%.2f".format(totalDataMb)}</td>")
        appendLine("                <td>${"%.2f".format(downloadMb)}</td>")
        appendLine("                <td>${"%.2f".format(uploadMb)}</td>")
        appendLine("                <td>${"%.2f".format(maxDownloadMbps)}</td>")
        appendLine("                <td>${"%.2f".format(maxUploadMbps)}</td>")
        appendLine("            </tr>")
      }
    }

    appendLine("        </tbody>")
    appendLine("    </table>")
    appendLine("    <div class=\"footer\">Generated automatically by Latch</div>")
    appendLine("</body>")
    appendLine("</html>")
  }

  writer.write(html)
  writer.close()
}