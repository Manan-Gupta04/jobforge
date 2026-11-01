package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches {@code url} with HTTP GET and returns the body as plain text (HTML tags stripped,
 * truncated to {@code maxChars}, default 20,000) so downstream LLM steps can consume it.
 */
@Component
public class HttpGetJobHandler implements JobHandler {

    private final RestClient client = RestClient.builder()
            .defaultHeader("User-Agent", "JobForge/1.0 (+https://github.com/Manan-Gupta04/jobforge)")
            .defaultHeader("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .build();

    @Override
    public String type() {
        return "http-get";
    }

    @Override
    public String description() {
        return "HTTP GET payload.url; returns status, bodyLength and the body as text (tags stripped, "
                + "truncated to payload.maxChars, default 20000). Non-2xx responses fail the job.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        String url = ctx.getString("url", null);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("payload.url is required");
        }
        int maxChars = Math.max(100, ctx.getInt("maxChars", 20_000));
        ResponseEntity<String> response = client.get().uri(url).retrieve().toEntity(String.class);
        String body = response.getBody() == null ? "" : response.getBody();
        String text = toText(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("status", response.getStatusCode().value());
        out.put("bodyLength", body.length());
        out.put("text", text.length() > maxChars ? text.substring(0, maxChars) : text);
        out.put("truncated", text.length() > maxChars);
        return JobResult.of(out);
    }

    /** Good-enough HTML to text: drop scripts/styles, strip tags, collapse whitespace. */
    static String toText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?s)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|</tr>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll("\\s*\\n\\s*", "\n");
        return s.trim();
    }
}
