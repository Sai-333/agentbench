-- Default model provider: Grok (OpenAI-compatible xAI API).
-- Single Flyway stream; forward-only; do not edit V1–V23.

ALTER TABLE agents.agents DROP CONSTRAINT agents_adapter_type_check;
ALTER TABLE agents.agents ADD CONSTRAINT agents_adapter_type_check
    CHECK (adapter_type IN ('FAKE', 'HTTP', 'SPRING_AI', 'OPENAI_COMPATIBLE', 'CODING_CLI', 'GROK'));
