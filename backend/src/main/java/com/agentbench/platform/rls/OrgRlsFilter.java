package com.agentbench.platform.rls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets {@link RlsContext} org id from {@code /api/v1/orgs/{orgId}/...} so each
 * request transaction carries {@code SET LOCAL app.current_org_id}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OrgRlsFilter extends OncePerRequestFilter {

    private static final Pattern ORG_PATH = Pattern.compile(
            "^/api/v1/orgs/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/|$)");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Matcher matcher = ORG_PATH.matcher(request.getRequestURI());
        if (!matcher.find()) {
            filterChain.doFilter(request, response);
            return;
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            filterChain.doFilter(request, response);
            return;
        }
        RlsContext.setOrgId(orgId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RlsContext.clear();
        }
    }
}
