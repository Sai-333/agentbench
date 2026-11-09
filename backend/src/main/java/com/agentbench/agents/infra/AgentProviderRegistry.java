package com.agentbench.agents.infra;

import com.agentbench.agents.domain.AdapterType;
import com.agentbench.agents.domain.spi.AgentProvider;
import com.agentbench.platform.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Extensible adapter SPI registry — maps adapter type → {@link AgentProvider}.
 */
@Component
public class AgentProviderRegistry {

    private final Map<String, AgentProvider> providers;

    public AgentProviderRegistry(List<AgentProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(
                        p -> p.adapterType().trim().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate AgentProvider for " + a.adapterType());
                        }));
    }

    public AgentProvider require(String adapterType) {
        AdapterType type;
        try {
            type = AdapterType.fromString(adapterType).normalize();
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(ex.getMessage());
        }
        AgentProvider provider = providers.get(type.name());
        if (provider == null) {
            throw ApiException.validation("No AgentProvider registered for " + type.name());
        }
        return provider;
    }

    public boolean supports(String adapterType) {
        try {
            AdapterType type = AdapterType.fromString(adapterType).normalize();
            return providers.containsKey(type.name());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
