package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal self-contained HTML for iframe embeds of a published leaderboard.
 */
@Component
public class EmbedHtmlRenderer {

    public String render(AnalyticsDtos.PublicLeaderboardResponse board) {
        StringBuilder rows = new StringBuilder();
        if (board.entries() != null) {
            for (AnalyticsDtos.PublicLeaderboardEntry entry : board.entries()) {
                rows.append("<tr>")
                        .append("<td>").append(entry.rank()).append("</td>")
                        .append("<td>").append(escape(entry.agentName())).append("</td>")
                        .append("<td>").append(escape(entry.taskSlug())).append("</td>")
                        .append("<td>").append(formatScore(entry.weightedTotal())).append("</td>")
                        .append("<td>").append(escape(transitions(entry.transitionSummary()))).append("</td>")
                        .append("</tr>");
            }
        }
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1"/>
                  <title>%s</title>
                  <style>
                    :root { color-scheme: light dark; font-family: ui-sans-serif, system-ui, sans-serif; }
                    body { margin: 0; padding: 1rem; background: #0f1419; color: #e7ecf1; }
                    h1 { font-size: 1.15rem; margin: 0 0 0.35rem; }
                    .meta { color: #9aa7b5; font-size: 0.85rem; margin-bottom: 1rem; }
                    table { width: 100%%; border-collapse: collapse; font-size: 0.9rem; }
                    th, td { text-align: left; padding: 0.45rem 0.5rem; border-bottom: 1px solid #2a3440; }
                    th { color: #9aa7b5; font-weight: 600; }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  <p class="meta">%s · %s %s · %d entries</p>
                  <table>
                    <thead><tr><th>#</th><th>Agent</th><th>Task</th><th>Score</th><th>F2P/P2P/P2F/F2F</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                </body>
                </html>
                """.formatted(
                escape(board.title()),
                escape(board.title()),
                escape(board.orgName()),
                escape(board.benchmarkName()),
                escape(board.benchmarkVersion()),
                board.page() == null ? 0 : board.page().total(),
                rows
        );
    }

    private static String transitions(Map<String, Integer> summary) {
        if (summary == null) {
            return "0/0/0/0";
        }
        return summary.getOrDefault("F2P", 0) + "/"
                + summary.getOrDefault("P2P", 0) + "/"
                + summary.getOrDefault("P2F", 0) + "/"
                + summary.getOrDefault("F2F", 0);
    }

    private static String formatScore(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
