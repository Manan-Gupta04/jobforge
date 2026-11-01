package com.manangupta.jobforge.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI jobForgeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("JobForge API")
                .version("1.0.0")
                .description("Distributed, fault-tolerant job scheduler and workflow engine. "
                        + "Submit jobs, chain them into dependency graphs, schedule them on a cron, "
                        + "and observe retries, leases and dead-lettering in real time.")
                .contact(new Contact().name("Manan Gupta").url("https://github.com/Manan-Gupta04")));
    }
}
