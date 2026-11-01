package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POSTs a JSON summary to the job's {@code callbackUrl} when it reaches a terminal state.
 * The call is deferred until the surrounding transaction commits and runs off-thread, so a
 * slow or dead callback endpoint never blocks the engine.
 */
@Component
public class WebhookNotifier implements JobEvents.JobListener {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final RestClient client = RestClient.create();
    private final JsonCodec json;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "jobforge-webhook");
        t.setDaemon(true);
        return t;
    });

    public WebhookNotifier(JsonCodec json) {
        this.json = json;
    }

    @Override
    public void onTerminal(Job job) {
        if (job.getCallbackUrl() == null || job.getCallbackUrl().isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId().toString());
        body.put("type", job.getType());
        body.put("status", job.getStatus().name());
        body.put("attempts", job.getAttempts());
        body.put("result", json.read(job.getResult()));
        body.put("error", job.getLastError());
        body.put("finishedAt", job.getFinishedAt() == null ? null : job.getFinishedAt().toString());
        String url = job.getCallbackUrl();

        Runnable send = () -> pool.execute(() -> deliver(url, body));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void deliver(String url, Map<String, Object> body) {
        try {
            client.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            log.debug("Webhook delivered to {} for job {}", url, body.get("jobId"));
        } catch (Exception e) {
            log.warn("Webhook to {} failed for job {}: {}", url, body.get("jobId"), e.toString());
        }
    }
}
