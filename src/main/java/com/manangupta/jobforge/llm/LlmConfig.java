package com.manangupta.jobforge.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        String provider = props.getProvider() == null ? "auto" : props.getProvider().toLowerCase(Locale.ROOT);
        boolean hasKey = System.getenv("ANTHROPIC_API_KEY") != null && !System.getenv("ANTHROPIC_API_KEY").isBlank();
        if (provider.equals("anthropic") || (provider.equals("auto") && hasKey)) {
            if (!hasKey) {
                throw new IllegalStateException("jobforge.llm.provider=anthropic but ANTHROPIC_API_KEY is not set");
            }
            log.info("LLM provider: anthropic (default model {})", props.getModel());
            return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv());
        }
        log.info("LLM provider: mock (set ANTHROPIC_API_KEY to use Claude)");
        return new MockLlmClient(300);
    }
}
