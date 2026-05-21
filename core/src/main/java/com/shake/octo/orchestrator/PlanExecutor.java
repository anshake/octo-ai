package com.shake.octo.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PlanExecutor
{

    private final Map<String, ClaudeSubagentDefinition> definitions = new HashMap<>();
    private final Map<String, ToolCallback[]> toolsByAgent = new HashMap<>();
    private final ChatClient chatClient;

    public PlanExecutor(ChatClient.Builder chatClientBuilder,
                        List<ToolCallback> availableTools,
                        @Value("classpath:agents") Resource agentPaths)
    {
        this.chatClient = chatClientBuilder.defaultAdvisors(new SimpleLoggerAdvisor()).build();

        var resolver = new ClaudeSubagentResolver();
        for (var ref : ClaudeSubagentReferences.fromResources(agentPaths))
        {
            var def = (ClaudeSubagentDefinition) resolver.resolve(ref);
            this.definitions.put(def.getName(), def);
        }

        final var toolsByName = new HashMap<>();
        for (ToolCallback t : availableTools)
        {
            toolsByName.put(t.getToolDefinition().name(), t);
        }

        for (var def : definitions.values())
        {
            this.toolsByAgent.put(def.getName(), def.tools().stream()
                                                    .map(toolsByName::get)
                                                    .filter(java.util.Objects::nonNull)
                                                    .toArray(ToolCallback[]::new));
        }

        log.info("Loaded {} subagent definitions: {}", definitions.size(), definitions.keySet());
        log.info("Available subagent tools: {}",
                 availableTools.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    public String catalog()
    {
        StringBuilder sb = new StringBuilder();
        for (var def : definitions.values())
        {
            sb.append("- ").append(def.getName()).append(": ").append(def.getDescription()).append('\n');
        }
        return sb.toString();
    }

    public String execute(ExecutionPlan plan)
    {
        if (plan == null || plan.agentTasks() == null || plan.agentTasks().isEmpty())
        {
            return "Plan is empty.";
        }

        var byId = plan.agentTasks().stream()
                       .collect(Collectors.toMap(AgentTask::id, t -> t, (a, b) -> a));
        var results = new ConcurrentHashMap<AgentTask, TaskResult>();
        var futures = new HashMap<AgentTask, CompletableFuture<Void>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            plan.agentTasks().forEach(t -> scheduleTask(t, byId, futures, results, executor));
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).join();
        }

        return aggregate(plan, byId, results);
    }

    private CompletableFuture<Void> scheduleTask(AgentTask task,
                                                 Map<String, AgentTask> byId,
                                                 Map<AgentTask, CompletableFuture<Void>> futures,
                                                 Map<AgentTask, TaskResult> results,
                                                 ExecutorService executor)
    {
        CompletableFuture<Void> existing = futures.get(task);
        if (existing != null)
        {
            return existing;
        }
        List<AgentTask> deps = dependenciesOf(task, byId);
        var gate = deps.isEmpty()
                ? CompletableFuture.<Void>completedFuture(null)
                : CompletableFuture.allOf(deps.stream()
                                              .map(d -> scheduleTask(d, byId, futures, results, executor))
                                              .toArray(CompletableFuture[]::new));
        var future = gate.thenRunAsync(() -> runTask(task, byId, results), executor);
        futures.put(task, future);
        return future;
    }

    private void runTask(AgentTask task, Map<String, AgentTask> byId, Map<AgentTask, TaskResult> results)
    {
        String agentName = task.agentName();
        for (AgentTask dep : dependenciesOf(task, byId))
        {
            if (results.get(dep).status() != Status.SUCCEEDED)
            {
                String reason = "upstream " + dep.agentName() + " failed";
                results.put(task, new TaskResult(Status.SKIPPED, reason));
                log.info("Task {} SKIPPED ({})", agentName, reason);
                return;
            }
        }

        final var def = definitions.get(agentName);
        if (def == null)
        {
            results.put(task, new TaskResult(Status.FAILED, "unknown agent: " + agentName));
            log.warn("Task FAILED: unknown agent: {}", agentName);
            return;
        }
        if (task.task() == null || task.task().isBlank())
        {
            results.put(task, new TaskResult(Status.FAILED, "task description is empty"));
            log.warn("Task {} FAILED: task description is empty", agentName);
            return;
        }

        try
        {
            String prompt = composePrompt(task, byId, results);
            log.debug("Invoking agent={} prompt=\n{}", agentName, prompt);
            final var content = chatClient.prompt()
                                          .system(def.getContent())
                                          .toolCallbacks(toolsByAgent.get(agentName))
                                          .user(prompt)
                                          .call().content();
            results.put(task, new TaskResult(Status.SUCCEEDED, content));
            log.info("Task {} SUCCEEDED", agentName);
        }
        catch (Exception e)
        {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            results.put(task, new TaskResult(Status.FAILED, reason));
            log.warn("Task {} FAILED: {}", agentName, reason, e);
        }
    }

    private String composePrompt(AgentTask task, Map<String, AgentTask> byId, Map<AgentTask, TaskResult> results)
    {
        List<AgentTask> deps = dependenciesOf(task, byId);
        if (deps.isEmpty())
        {
            return task.task();
        }
        final var sb = new StringBuilder("=== Inputs from prior tasks ===\n");
        for (AgentTask p : deps)
        {
            String content = results.get(p).result();
            sb.append('[').append(p.agentName()).append("] ")
              .append(content == null ? "" : content).append("\n\n");
        }
        sb.append("=== Your task ===\n").append(task.task());
        return sb.toString();
    }

    private String aggregate(ExecutionPlan plan, Map<String, AgentTask> byId, Map<AgentTask, TaskResult> results)
    {
        Set<AgentTask> intermediate = results.keySet().stream()
                                             .flatMap(t -> dependenciesOf(t, byId).stream())
                                             .collect(Collectors.toSet());

        String body = results.entrySet().stream()
                             .filter(e -> !intermediate.contains(e.getKey()))
                             .map(e -> format(e.getKey(), e.getValue()))
                             .collect(Collectors.joining("\n\n"));

        return "Plan: " + plan.executionSummary() + "\n\n" + body;
    }

    private static String format(AgentTask task, TaskResult r)
    {
        final var tail = switch (r.status())
        {
            case SUCCEEDED -> "(SUCCEEDED)\n" + r.result();
            default -> "(" + r.status() + " " + r.result() + ")";
        };
        return "[" + task.agentName() + "] " + tail;
    }

    private static List<AgentTask> dependenciesOf(AgentTask task, Map<String, AgentTask> byId)
    {
        if (task.dependencies() == null)
        {
            return List.of();
        }
        return task.dependencies().stream()
                   .map(byId::get)
                   .filter(Objects::nonNull)
                   .toList();
    }

    private enum Status
    {SUCCEEDED, FAILED, SKIPPED}

    private record TaskResult(Status status, String result) {}
}
