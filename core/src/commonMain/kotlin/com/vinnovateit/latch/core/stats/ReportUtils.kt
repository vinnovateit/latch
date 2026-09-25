package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.PortalSessionRecord
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Generates a clean light-themed monospace outline HTML report matching Session History layout.
 */
fun generatePortalHtmlReport(
    sessions: List<PortalSessionRecord>,
    outputStream: OutputStream,
    appVersion: String,
    userId: String? = ""
) {
    val writer = outputStream.bufferedWriter()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val dayDisplayFormat = SimpleDateFormat("d MMM, EEE", Locale.US)
    val generatedAt = dateFormat.format(Date())

    val totalBytes = sessions.sumOf { if (it.totalBytes > 0) it.totalBytes else (it.uploadBytes + it.downloadBytes) }
    val totalUpload = sessions.sumOf { it.uploadBytes }
    val totalDownload = sessions.sumOf { it.downloadBytes }
    val totalDurationMillis = sessions.sumOf { it.durationMillis }
    val sessionCount = sessions.size
    val sessionUnit = if (sessionCount == 1) "session" else "sessions"

    val topLocation = if (sessions.isEmpty()) {
        "N/A"
    } else {
        sessions
            .filter { it.location.isNotBlank() }
            .groupingBy { it.location }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: "N/A"
    }

    val totalDurationFormatted = formatDurationWords(totalDurationMillis)

    // Compute day aggregates grouped by month matching Session History
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    data class DaySummary(
        val timestamp: Long,
        val dateFormatted: String,
        val downloadBytes: Long,
        val uploadBytes: Long,
        val totalBytes: Long,
        val durationFormatted: String,
        val sessionCount: Int
    )

    val daysMap = linkedMapOf<String, MutableList<PortalSessionRecord>>()
    for (s in sessions) {
        if (s.loginTime > 0) {
            val key = dayKeyFormat.format(Date(s.loginTime))
            daysMap.getOrPut(key) { mutableListOf() }.add(s)
        }
    }

    val daySummaries = daysMap.map { (_, daySessions) ->
        val dayTimestamp = daySessions.minOf { it.loginTime }
        val dl = daySessions.sumOf { it.downloadBytes }
        val ul = daySessions.sumOf { it.uploadBytes }
        val tot = daySessions.sumOf { if (it.totalBytes > 0) it.totalBytes else (it.uploadBytes + it.downloadBytes) }
        val durMs = daySessions.sumOf { it.durationMillis }
        DaySummary(
            timestamp = dayTimestamp,
            dateFormatted = dayDisplayFormat.format(Date(dayTimestamp)),
            downloadBytes = dl,
            uploadBytes = ul,
            totalBytes = tot,
            durationFormatted = formatDurationWords(durMs),
            sessionCount = daySessions.size
        )
    }.sortedByDescending { it.timestamp }

    val monthGroups = linkedMapOf<String, MutableList<DaySummary>>()
    val cal = Calendar.getInstance()
    for (day in daySummaries) {
        cal.timeInMillis = day.timestamp
        val year = cal.get(Calendar.YEAR)
        val monthPattern = if (year == currentYear) "MMMM" else "MMMM yyyy"
        val monthTitle = SimpleDateFormat(monthPattern, Locale.US).format(Date(day.timestamp))
        monthGroups.getOrPut(monthTitle) { mutableListOf() }.add(day)
    }

    val userBadgeHtml = if (!userId.isNullOrBlank()) {
        """<div class="meta-badge user-badge">User: <strong>${escapeHtml(userId)}</strong></div>"""
    } else ""

    val monthGroupsHtml = buildString {
        if (monthGroups.isNotEmpty()) {
            appendLine("""        <section class="history-section">""")
            appendLine("""            <div class="history-title">Session History</div>""")
            monthGroups.forEach { (monthTitle, days) ->
                val monthTotal = formatBytes(days.sumOf { it.totalBytes })
                val dayCount = days.size
                val dayUnit = if (dayCount == 1) "day" else "days"
                appendLine("""            <div class="month-group">""")
                appendLine("""                <div class="month-header">""")
                appendLine("""                    <span class="month-title">${escapeHtml(monthTitle)}</span>""")
                appendLine("""                    <span class="month-meta">$monthTotal ($dayCount $dayUnit)</span>""")
                appendLine("""                </div>""")
                appendLine("""                <div class="day-list">""")
                days.forEach { day ->
                    val sessionUnit = if (day.sessionCount == 1) "session" else "sessions"
                    appendLine("""                    <div class="day-card">""")
                    appendLine("""                        <div class="day-info">""")
                    appendLine("""                            <div class="day-title-row">""")
                    appendLine("""                                <span class="day-date">${escapeHtml(day.dateFormatted)}</span>""")
                    appendLine("""                                <span class="sess-pill">${day.sessionCount} $sessionUnit</span>""")
                    appendLine("""                            </div>""")
                    appendLine("""                            <div class="day-sub-row">""")
                    appendLine("""                                <span class="dl">↓ ${formatBytes(day.downloadBytes)}</span>""")
                    appendLine("""                                <span class="ul">↑ ${formatBytes(day.uploadBytes)}</span>""")
                    if (day.durationFormatted.isNotBlank()) {
                        appendLine("""                                <span class="dur">${escapeHtml(day.durationFormatted)}</span>""")
                    }
                    appendLine("""                            </div>""")
                    appendLine("""                        </div>""")
                    appendLine("""                        <div class="day-total">${formatBytes(day.totalBytes)}</div>""")
                    appendLine("""                    </div>""")
                }
                appendLine("""                </div>""")
                appendLine("""            </div>""")
            }
            appendLine("""        </section>""")
        }
    }

    val rowsHtml = buildString {
        if (sessions.isEmpty()) {
            appendLine("""                        <tr><td colspan="8" class="empty-row">No session data available.</td></tr>""")
        } else {
            sessions.forEachIndexed { index, session ->
                val loginTimeStr = if (session.loginTime > 0) dateFormat.format(Date(session.loginTime)) else "-"
                val logoutTimeStr = if (session.logoutTime > 0) dateFormat.format(Date(session.logoutTime)) else "-"
                val durationStr = session.durationFormatted.ifBlank { formatDurationWords(session.durationMillis) }
                val rowTotal = if (session.totalBytes > 0) session.totalBytes else (session.uploadBytes + session.downloadBytes)
                val loc = session.location.ifBlank { "Unknown" }
                appendLine("""                        <tr>""")
                appendLine("""                            <td class="num-col">${index + 1}</td>""")
                appendLine("""                            <td><span class="badge badge-location">${escapeHtml(loc)}</span></td>""")
                appendLine("""                            <td>${escapeHtml(loginTimeStr)}</td>""")
                appendLine("""                            <td>${escapeHtml(logoutTimeStr)}</td>""")
                appendLine("""                            <td><span class="badge badge-duration">${escapeHtml(durationStr)}</span></td>""")
                appendLine("""                            <td class="bytes-cell">${formatBytes(session.uploadBytes)}</td>""")
                appendLine("""                            <td class="bytes-cell">${formatBytes(session.downloadBytes)}</td>""")
                appendLine("""                            <td class="bytes-cell">${formatBytes(rowTotal)}</td>""")
                appendLine("""                        </tr>""")
            }
        }
    }

    val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Latch Session Report</title>
    <style>
        :root {
            --bg: #ffffff;
            --text-primary: #111827;
            --text-secondary: #4b5563;
            --text-muted: #6b7280;
            --border: #e5e7eb;
            --border-strong: #d1d5db;
            --accent-blue: #2563eb;
            --accent-green: #16a34a;
            --radius-lg: 12px;
            --radius-md: 8px;
            --radius-sm: 6px;
        }
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }
        body {
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
            background-color: var(--bg);
            color: var(--text-primary);
            line-height: 1.5;
            padding: 32px 24px;
            -webkit-font-smoothing: antialiased;
        }
        .container {
            max-width: 960px;
            margin: 0 auto;
        }
        /* Header */
        .header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            flex-wrap: wrap;
            gap: 16px;
            margin-bottom: 24px;
            padding-bottom: 16px;
            border-bottom: 1px solid var(--border);
        }
        .header-title-group h1 {
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.01em;
            color: var(--text-primary);
        }
        .header-title-group .subtitle {
            color: var(--text-secondary);
            font-size: 13px;
            margin-top: 4px;
        }
        .meta-tags {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            align-items: center;
        }
        .meta-badge {
            display: inline-flex;
            align-items: center;
            padding: 4px 10px;
            border-radius: var(--radius-sm);
            font-size: 12px;
            background: transparent;
            border: 1px solid var(--border-strong);
            color: var(--text-secondary);
        }
        .meta-badge strong {
            color: var(--text-primary);
            margin-left: 4px;
        }
        .meta-badge.user-badge {
            border-color: var(--accent-blue);
            color: var(--accent-blue);
        }
        .meta-badge.user-badge strong {
            color: var(--accent-blue);
        }
        /* KPI Stat Cards (Borders only, no fill, no shadows) */
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 16px;
            margin-bottom: 28px;
        }
        .card {
            background: transparent;
            border: 1px solid var(--border);
            border-radius: var(--radius-lg);
            padding: 16px;
            box-shadow: none;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
        }
        .card-label {
            font-size: 11px;
            font-weight: 700;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.05em;
            margin-bottom: 6px;
        }
        .card-value {
            font-size: 22px;
            font-weight: 800;
            color: var(--text-primary);
        }
        .card-caption {
            font-size: 11px;
            color: var(--text-muted);
            margin-top: 6px;
        }
        .sub-pills {
            display: flex;
            gap: 8px;
            margin-top: 8px;
            flex-wrap: wrap;
        }
        .sub-pill {
            display: inline-flex;
            align-items: center;
            gap: 4px;
            font-size: 11px;
            font-weight: 600;
            padding: 2px 6px;
            border-radius: var(--radius-sm);
            background: transparent;
            border: 1px solid var(--border);
        }
        .sub-pill.upload {
            color: var(--accent-blue);
            border-color: var(--accent-blue);
        }
        .sub-pill.download {
            color: var(--accent-green);
            border-color: var(--accent-green);
        }
        /* Session History Layout: Month Groups & Day Cards */
        .history-section {
            margin-bottom: 28px;
        }
        .history-title {
            font-size: 13px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--text-secondary);
            margin-bottom: 12px;
        }
        .month-group {
            margin-bottom: 20px;
        }
        .month-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 8px 4px;
            margin-bottom: 8px;
            border-bottom: 1px solid var(--border);
        }
        .month-title {
            font-size: 14px;
            font-weight: 700;
            color: var(--text-primary);
        }
        .month-meta {
            font-size: 12px;
            color: var(--text-muted);
        }
        .day-list {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .day-card {
            background: transparent;
            border: 1px solid var(--border);
            border-radius: var(--radius-md);
            padding: 12px 16px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 12px;
        }
        .day-info {
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .day-title-row {
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .day-date {
            font-size: 13px;
            font-weight: 700;
            color: var(--text-primary);
        }
        .sess-pill {
            display: inline-block;
            font-size: 11px;
            font-weight: 600;
            padding: 1px 6px;
            border-radius: var(--radius-sm);
            background: transparent;
            border: 1px solid var(--border-strong);
            color: var(--text-secondary);
        }
        .day-sub-row {
            display: flex;
            align-items: center;
            gap: 12px;
            font-size: 12px;
            color: var(--text-muted);
            flex-wrap: wrap;
        }
        .day-sub-row .dl {
            color: var(--accent-green);
            font-weight: 600;
        }
        .day-sub-row .ul {
            color: var(--accent-blue);
            font-weight: 600;
        }
        .day-sub-row .dur {
            color: var(--text-secondary);
        }
        .day-total {
            text-align: right;
            font-size: 15px;
            font-weight: 800;
            color: var(--text-primary);
        }
        /* Data Table (Borders only, no fill, no shadows) */
        .table-container {
            background: transparent;
            border: 1px solid var(--border);
            border-radius: var(--radius-lg);
            overflow: hidden;
            box-shadow: none;
            margin-bottom: 28px;
        }
        .table-header-bar {
            padding: 12px 16px;
            border-bottom: 1px solid var(--border);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .table-header-bar h2 {
            font-size: 13px;
            font-weight: 700;
            color: var(--text-primary);
            text-transform: uppercase;
            letter-spacing: 0.05em;
        }
        .table-wrapper {
            overflow-x: auto;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
            text-align: left;
        }
        thead {
            background: transparent;
            border-bottom: 1px solid var(--border);
        }
        th {
            padding: 10px 14px;
            font-size: 11px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--text-secondary);
            white-space: nowrap;
        }
        td {
            padding: 10px 14px;
            border-bottom: 1px solid var(--border);
            color: var(--text-primary);
            vertical-align: middle;
            white-space: nowrap;
        }
        tbody tr:last-child td {
            border-bottom: none;
        }
        .num-col {
            color: var(--text-muted);
            width: 28px;
            font-weight: 500;
        }
        .badge {
            display: inline-flex;
            align-items: center;
            padding: 2px 6px;
            border-radius: var(--radius-sm);
            font-size: 11px;
            font-weight: 600;
            background: transparent;
            border: 1px solid var(--border);
        }
        .badge-location {
            color: var(--text-primary);
            border-color: var(--border-strong);
        }
        .badge-duration {
            color: var(--text-secondary);
        }
        .bytes-cell {
            font-size: 12px;
        }
        .empty-row {
            text-align: center;
            padding: 32px 16px;
            color: var(--text-muted);
        }
        /* Footer */
        .footer {
            text-align: center;
            font-size: 11px;
            color: var(--text-muted);
            padding-top: 12px;
            border-top: 1px solid var(--border);
        }
        /* Print Styles (Strict light outline, no shadows) */
        @media print {
            body {
                background: #ffffff !important;
                color: #000000 !important;
                padding: 12px !important;
            }
            .card, .table-container, .day-card {
                border-color: #000000 !important;
                box-shadow: none !important;
                page-break-inside: avoid;
            }
            .header {
                border-bottom: 1px solid #000000 !important;
            }
            th, td {
                color: #000000 !important;
                border-bottom: 1px solid #cccccc !important;
            }
            tbody tr {
                page-break-inside: avoid;
            }
        }
    </style>
</head>
<body>
    <div class="container">
        <header class="header">
            <div class="header-title-group">
                <h1>Latch Session Report</h1>
                <p class="subtitle">Network diagnostics and captive portal usage overview</p>
            </div>
            <div class="meta-tags">
                $userBadgeHtml
                <div class="meta-badge">Version: <strong>v${escapeHtml(appVersion)}</strong></div>
                <div class="meta-badge">Generated: <strong>${escapeHtml(generatedAt)}</strong></div>
            </div>
        </header>

        <section class="stats-grid">
            <div class="card">
                <div>
                    <div class="card-label">Total Data Transfer</div>
                    <div class="card-value">${formatBytes(totalBytes)}</div>
                </div>
                <div class="sub-pills">
                    <span class="sub-pill download">↓ ${formatBytes(totalDownload)}</span>
                    <span class="sub-pill upload">↑ ${formatBytes(totalUpload)}</span>
                </div>
            </div>
            <div class="card">
                <div>
                    <div class="card-label">Total Active Duration</div>
                    <div class="card-value">$totalDurationFormatted</div>
                </div>
                <div class="card-caption">Cumulative connected time</div>
            </div>
            <div class="card">
                <div>
                    <div class="card-label">Total Sessions Logged</div>
                    <div class="card-value">$sessionCount $sessionUnit</div>
                </div>
                <div class="card-caption">Historical portal connections</div>
            </div>
            <div class="card">
                <div>
                    <div class="card-label">Top Location</div>
                    <div class="card-value">${escapeHtml(topLocation)}</div>
                </div>
                <div class="card-caption">Most frequented portal network</div>
            </div>
        </section>

$monthGroupsHtml
        <section class="table-container">
            <div class="table-header-bar">
                <h2>Session Details</h2>
            </div>
            <div class="table-wrapper">
                <table>
                    <thead>
                        <tr>
                            <th class="num-col">#</th>
                            <th>Location</th>
                            <th>Login Time</th>
                            <th>Logout Time</th>
                            <th>Duration</th>
                            <th>Upload</th>
                            <th>Download</th>
                            <th>Total Data</th>
                        </tr>
                    </thead>
                    <tbody>
$rowsHtml
                    </tbody>
                </table>
            </div>
        </section>

        <footer class="footer">
            Generated automatically by Latch v${escapeHtml(appVersion)}
        </footer>
    </div>
</body>
</html>
""".trimIndent()

    writer.write(html)
    writer.flush()
    writer.close()
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.2f KB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
