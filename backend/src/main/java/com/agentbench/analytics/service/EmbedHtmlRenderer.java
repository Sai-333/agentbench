package com.agentbench.analytics.service;

import com.agentbench.analytics.api.dto.AnalyticsDtos;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal self-contained HTML for iframe embeds of a published leaderboard.
 * Includes read-only vote tallies + recent comments; interactive posting stays on the public page.
 */
@Component
public class EmbedHtmlRenderer {

    public String render(AnalyticsDtos.PublicLeaderboardResponse board) {
        return render(board, null, null);
    }

    public String render(
            AnalyticsDtos.PublicLeaderboardResponse board,
            AnalyticsDtos.PublicVoteSummaryResponse votes,
            AnalyticsDtos.PublicCommentListResponse comments) {
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

        String engagement = buildEngagement(board.publicSlug(), votes, comments);

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
                    .engagement { margin-top: 1.25rem; padding-top: 0.85rem; border-top: 1px solid #2a3440; }
                    .engagement h2 { font-size: 0.95rem; margin: 0 0 0.45rem; }
                    .votes { color: #9aa7b5; font-size: 0.85rem; margin-bottom: 0.75rem; }
                    .comment { margin: 0.45rem 0; font-size: 0.85rem; }
                    .comment .who { color: #9aa7b5; }
                    .cta { color: #8ec7ff; font-size: 0.85rem; }
                  </style>
                </head>
                <body>
                  <h1>%s</h1>
                  <p class="meta">%s · %s %s · %d entries</p>
                  <table>
                    <thead><tr><th>#</th><th>Agent</th><th>Task</th><th>Score</th><th>F2P/P2P/P2F/F2F</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                  %s
                </body>
                </html>
                """.formatted(
                escape(board.title()),
                escape(board.title()),
                escape(board.orgName()),
                escape(board.benchmarkName()),
                escape(board.benchmarkVersion()),
                board.page() == null ? 0 : board.page().total(),
                rows,
                engagement
        );
    }

    private static String buildEngagement(
            String publicSlug,
            AnalyticsDtos.PublicVoteSummaryResponse votes,
            AnalyticsDtos.PublicCommentListResponse comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"engagement\">");
        if (votes != null) {
            sb.append("<p class=\"votes\">Votes: ▲ ")
                    .append(votes.upCount())
                    .append(" · ▼ ")
                    .append(votes.downCount())
                    .append(" · score ")
                    .append(votes.score())
                    .append("</p>");
        }
        sb.append("<h2>Recent comments</h2>");
        if (comments == null || comments.comments() == null || comments.comments().isEmpty()) {
            sb.append("<p class=\"meta\">No comments yet.</p>");
        } else {
            for (AnalyticsDtos.PublicCommentResponse comment : comments.comments()) {
                sb.append("<div class=\"comment\"><span class=\"who\">")
                        .append(escape(comment.displayName()))
                        .append(comment.authenticatedAuthor() ? " ✓" : "")
                        .append(":</span> ")
                        .append(escape(comment.body()))
                        .append("</div>");
            }
        }
        sb.append("<p class=\"cta\"><a class=\"cta\" href=\"/public/leaderboards/")
                .append(escape(publicSlug))
                .append("\" target=\"_blank\" rel=\"noopener\">Comment or vote on the public page</a></p>");
        sb.append("</section>");
        return sb.toString();
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
