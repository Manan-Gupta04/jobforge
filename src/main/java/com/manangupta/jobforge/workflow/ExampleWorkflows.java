package com.manangupta.jobforge.workflow;

import java.util.List;
import java.util.Map;

/** Ready-made pipelines used by the dashboard demo buttons and the docs. */
public final class ExampleWorkflows {

    private ExampleWorkflows() {
    }

    /**
     * Fetch a web page, summarise it, classify it and extract keywords with an LLM, then emit a
     * report. The classify and keyword steps run in parallel once the summary exists.
     */
    public static WorkflowSpec contentPipeline(String url, String callbackUrl) {
        return new WorkflowSpec("content-pipeline", List.of(
                new WorkflowSpec.Step("fetch", "http-get",
                        Map.of("url", url, "maxChars", 12_000), List.of(), 6, 3, 30_000L),
                new WorkflowSpec.Step("summarize", "llm",
                        Map.of("system", "You are a precise technical writer. Answer in plain text.",
                                "prompt", "Summarize the following page in 3 sentences.\n\n{{steps.fetch.output.text}}",
                                "maxTokens", 400, "effort", "low"),
                        List.of("fetch"), 5, 5, 120_000L),
                new WorkflowSpec.Step("classify", "llm",
                        Map.of("prompt", "Classify this summary into exactly one category: technology, business, "
                                        + "science or general. Reply with the category only.\n\n{{steps.summarize.output.text}}",
                                "maxTokens", 20, "effort", "low"),
                        List.of("summarize"), 5, 5, 60_000L),
                new WorkflowSpec.Step("keywords", "llm",
                        Map.of("prompt", "List the 5 most important keywords of this summary, comma separated, "
                                        + "nothing else.\n\n{{steps.summarize.output.text}}",
                                "maxTokens", 60, "effort", "low"),
                        List.of("summarize"), 5, 5, 60_000L),
                new WorkflowSpec.Step("report", "echo",
                        Map.of("url", url,
                                "category", "{{steps.classify.output.text}}",
                                "keywords", "{{steps.keywords.output.text}}",
                                "summary", "{{steps.summarize.output.text}}",
                                "tokensUsed", "{{steps.summarize.output.outputTokens}}"),
                        List.of("classify", "keywords"), 5, 3, 10_000L)
        ), callbackUrl);
    }

    /** Map-reduce over a list of texts: one LLM job per item in parallel, then a merge step. */
    public static WorkflowSpec batchSummaries(List<String> texts, String callbackUrl) {
        List<WorkflowSpec.Step> steps = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        Map<String, Object> merge = new java.util.LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            String id = "item-" + i;
            ids.add(id);
            steps.add(new WorkflowSpec.Step(id, "llm",
                    Map.of("prompt", "Summarize in one sentence:\n\n" + texts.get(i), "maxTokens", 80, "effort", "low"),
                    List.of(), 5, 5, 60_000L));
            merge.put("summary" + i, "{{steps." + id + ".output.text}}");
        }
        steps.add(new WorkflowSpec.Step("merge", "echo", merge, ids, 5, 3, 10_000L));
        return new WorkflowSpec("batch-summaries", steps, callbackUrl);
    }
}
