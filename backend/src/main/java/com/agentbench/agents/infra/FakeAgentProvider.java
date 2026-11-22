package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentInvocationRequest;
import com.agentbench.agents.domain.spi.AgentInvocationResult;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Deterministic Fake/Mock provider for tests and CI — no external API calls.
 * A successful call stores fixed sample token and cost figures so the costs
 * screen has a row. Those figures are not a measured provider bill.
 */
@Component
public class FakeAgentProvider implements AgentProvider {

    public static final String PROVIDER_VERSION = "fake-v1";

    /** Input tokens stored on {@code prompt_tokens}. Sample only. */
    public static final int SAMPLE_PROMPT_TOKENS = 128;

    /** Output tokens stored on {@code completion_tokens}. Sample only. */
    public static final int SAMPLE_COMPLETION_TOKENS = 64;

    /** {@link #SAMPLE_PROMPT_TOKENS} + {@link #SAMPLE_COMPLETION_TOKENS}. */
    public static final int SAMPLE_TOTAL_TOKENS = SAMPLE_PROMPT_TOKENS + SAMPLE_COMPLETION_TOKENS;

    /**
     * Sample estimate: $0.01 per 1k input tokens and $0.03 per 1k output tokens.
     * Stored on {@code estimated_cost_usd}. Not a provider invoice.
     */
    public static final BigDecimal SAMPLE_ESTIMATED_COST_USD = sampleEstimatedCostUsd(
            SAMPLE_PROMPT_TOKENS, SAMPLE_COMPLETION_TOKENS);

    public static final String SAMPLE_USAGE_LABEL =
            "Sample usage from the FAKE adapter, not a measured provider bill.";

    private final ObjectMapper objectMapper;

    public FakeAgentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String adapterType() {
        return AdapterType.FAKE.name();
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationRequest request) {
        try {
            JsonNode config = objectMapper.readTree(
                    request.agent().configJson() == null ? "{}" : request.agent().configJson());
            if (config.path("fail").asBoolean(false)) {
                return AgentInvocationResult.failure(
                        com.agentbench.agents.domain.spi.AgentInvocationStatus.FAILED,
                        "FAKE_FORCED_FAILURE",
                        "Fake provider configured to fail",
                        "fake",
                        Map.of("deterministic", true, "source", "FAKE")
                );
            }
        } catch (Exception ignored) {
            // fall through to patch resolution
        }
        String patch = resolvePatch(request);
        return AgentInvocationResult.success(
                patch,
                "fake",
                PROVIDER_VERSION,
                SAMPLE_PROMPT_TOKENS,
                SAMPLE_COMPLETION_TOKENS,
                SAMPLE_TOTAL_TOKENS,
                SAMPLE_ESTIMATED_COST_USD,
                Map.of(
                        "deterministic", true,
                        "source", "FAKE",
                        "usageKind", "sample",
                        "usageLabel", SAMPLE_USAGE_LABEL
                )
        );
    }

    /**
     * Fixed sample estimate used only by this adapter. Rates are illustrative.
     */
    static BigDecimal sampleEstimatedCostUsd(int promptTokens, int completionTokens) {
        BigDecimal prompt = BigDecimal.valueOf(promptTokens)
                .multiply(new BigDecimal("0.01"))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        BigDecimal completion = BigDecimal.valueOf(completionTokens)
                .multiply(new BigDecimal("0.03"))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        return prompt.add(completion);
    }

    private String resolvePatch(AgentInvocationRequest request) {
        try {
            JsonNode config = objectMapper.readTree(
                    request.agent().configJson() == null ? "{}" : request.agent().configJson());
            if (config.hasNonNull("patch")) {
                return config.get("patch").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (request.referencePatchHint() != null && !request.referencePatchHint().isBlank()) {
            return request.referencePatchHint();
        }
        return """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1,2 @@
                 AgentBench evaluation workspace
                +Patched by FakeAgentProvider
                """;
    }
}
