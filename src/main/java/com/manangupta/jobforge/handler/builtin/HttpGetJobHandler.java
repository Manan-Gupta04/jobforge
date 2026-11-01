package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Fetches {@code url} with HTTP GET and reports the status and body size. */
@Component
public class HttpGetJobHandler implements JobHandler {

    private final RestClient client = RestClient.create();

    @Override
    public String type() {
        return "http-get";
    }

    @Override
    public String description() {
        return "HTTP GET payload.url; records status code and body length. Non-2xx responses fail the job.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        String url = ctx.getString("url", null);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("payload.url is required");
        }
        ResponseEntity<String> response = client.get().uri(url).retrieve().toEntity(String.class);
        String body = response.getBody() == null ? "" : response.getBody();
        return JobResult.of(Map.of(
                "url", url,
                "status", response.getStatusCode().value(),
                "bodyLength", body.length()));
    }
}
