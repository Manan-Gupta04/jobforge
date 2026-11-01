package com.manangupta.jobforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:jobforge-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void submitReturns201ThenIdempotentResubmitReturns200WithSameId() throws Exception {
        String key = "order-" + UUID.randomUUID();
        String body = """
                {"type":"echo","payload":{"orderId":42},"idempotencyKey":"%s","runAt":"2099-01-01T00:00:00Z"}
                """.formatted(key);

        MvcResult first = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payload.orderId").value(42))
                .andReturn();
        String id = mapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void unknownTypeIsRejectedWithProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"teleport\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", containsString("Unknown job type")))
                .andExpect(jsonPath("$.detail", containsString("echo")));
    }

    @Test
    void validationErrorsListTheOffendingFields() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"echo\",\"priority\":99,\"maxAttempts\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.priority").exists())
                .andExpect(jsonPath("$.fields.maxAttempts").exists());
    }

    @Test
    void unknownJobIs404() throws Exception {
        mvc.perform(get("/api/v1/jobs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void cancelThenRetryRoundTrip() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"echo\",\"runAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/jobs/" + id + "/cancel").param("reason", "because"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.lastError").value("because"));

        mvc.perform(post("/api/v1/jobs/" + id + "/cancel"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/v1/jobs/" + id + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
    }

    @Test
    void statsAndHandlersEndpointsDescribeTheCluster() throws Exception {
        MvcResult stats = mvc.perform(get("/api/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workers").value(8))
                .andExpect(jsonPath("$.byStatus.PENDING").isNumber())
                .andExpect(jsonPath("$.handlerTypes", hasItem("echo")))
                .andReturn();
        JsonNode node = mapper.readTree(stats.getResponse().getContentAsString());
        assertThat(node.get("nodeId").asText()).isNotBlank();

        mvc.perform(get("/api/v1/handlers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItems("echo", "sleep", "flaky", "fail", "primes", "http-get")));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"echo\",\"runAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/jobs").param("status", "PENDING").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", not(empty())))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("PENDING"))))
                .andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void bulkSubmitCreatesTheRequestedNumberOfJobs() throws Exception {
        mvc.perform(post("/api/v1/jobs/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"echo\",\"count\":25,\"payload\":{\"b\":1}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submitted").value(25))
                .andExpect(jsonPath("$.elapsedMs").isNumber());
    }
}
