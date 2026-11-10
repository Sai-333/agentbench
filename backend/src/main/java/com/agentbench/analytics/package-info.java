/**
 * Analytics module — org-scoped dashboards, leaderboards, comparisons, cost reports, exports (M8+).
 *
 * <p>Aggregations query existing evaluation/score/invocation data. Exports support JSON, CSV, and PDF
 * (OpenPDF). Leaderboards stay org-private by default; OWNER/ADMIN may explicitly publish a sanitized
 * public snapshot by slug, mint HMAC-signed embed tokens, and serve CDN-friendly Cache-Control headers
 * on public/embed reads (no CDN required).
 */
package com.agentbench.analytics;
