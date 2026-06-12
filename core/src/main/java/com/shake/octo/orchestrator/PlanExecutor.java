package com.shake.octo.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentResolver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

/**
 * Executes an {@link ExecutionPlan} produced by the orchestrator LLM.
 *
 * <p>On startup, loads subagent definitions from {@code classpath:agents} and maps each
 * agent's declared tool names to the available {@link ToolCallback} beans.
 *
 * <p>At runtime, {@link #execute(ExecutionPlan)} schedules each {@link AgentTask} on a
 * virtual-thread executor. Tasks with no dependencies run immediately in parallel; tasks
 * that declare dependencies wait for their upstream tasks to finish and receive their
 * outputs prepended to their prompt. Only leaf tasks (those not depended on by others)
 * are included in the aggregated result returned to the orchestrator.
 *
 * <p>A task is skipped if any of its upstream tasks failed.
 */
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
        // new clean ChatModel instance
        this.chatClient = chatClientBuilder
//                .defaultAdvisors(new SimpleLoggerAdvisor())
            .build();

        var resolver = new ClaudeSubagentResolver();
        for (var ref : ClaudeSubagentReferences.fromResources(agentPaths))
        {
            var def = (ClaudeSubagentDefinition) resolver.resolve(ref);
            this.definitions.put(def.getName(), def);
        }

        Map<String, ToolCallback> toolsByName = new HashMap<>();
        for (ToolCallback t : availableTools)
        {
            toolsByName.put(t.getToolDefinition().name(), t);
        }

        for (var def : definitions.values())
        {
            this.toolsByAgent.put(def.getName(), def.tools().stream()
                                                    .map(toolsByName::get)
                                                    .filter(Objects::nonNull)
                                                    .toArray(ToolCallback[]::new));
        }

        log.info("Loaded {} subagent definitions: {}", definitions.size(), definitions.keySet());
        log.info("Available subagent tools: {}",
                 availableTools.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    public String listAgents()
    {
        final var sb = new StringBuilder();
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
                       .collect(Collectors.toMap(AgentTask::id, t -> t, (a, _) -> a));
        var results = new ConcurrentHashMap<AgentTask, TaskResult>();
        var futures = new HashMap<AgentTask, CompletableFuture<Void>>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            plan.agentTasks().forEach(t -> scheduleTask(t, byId, futures, results, executor));
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).join();
        }

        return aggregate(byId, results);
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
                log.warn("Task {} SKIPPED ({})", agentName, reason);
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
        if (!StringUtils.hasText(task.taskPrompt()))
        {
            results.put(task, new TaskResult(Status.FAILED, "task description is empty"));
            log.warn("Task {} FAILED: task description is empty", agentName);
            return;
        }

        try
        {
            var prompt = composePrompt(task, byId, results);
            log.debug("Invoking agent={} prompt=\n{}", agentName, prompt);
            final var content = chatClient.prompt()
                                          .system(def.getContent())
                                          .tools((Object[]) toolsByAgent.get(agentName))
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
        final List<AgentTask> deps = dependenciesOf(task, byId);
        if (deps.isEmpty())
        {
            return task.taskPrompt();
        }
        final var sb = new StringBuilder("=== Inputs from prior tasks ===\n");
        for (AgentTask p : deps)
        {
            String content = results.get(p).result();
            sb.append('[').append(p.agentName()).append("] ")
              .append(content == null ? "" : content).append("\n\n");
        }
        sb.append("=== Your task ===\n").append(task.taskPrompt());
        return sb.toString();
    }

    private String aggregate(Map<String, AgentTask> byId, Map<AgentTask, TaskResult> results)
    {
        final Set<AgentTask> intermediate = results.keySet().stream()
                                                   .flatMap(t -> dependenciesOf(t, byId).stream())
                                                   .collect(Collectors.toSet());

        return results.entrySet().stream()
                      .filter(e -> !intermediate.contains(e.getKey()))
                      .map(e -> render(e.getKey(), e.getValue()))
                      .collect(Collectors.joining("\n\n"));
    }

    private static String render(AgentTask task, TaskResult result)
    {
        if (result.status() == Status.SUCCEEDED)
        {
            return result.result();
        }
        return task.agentName() + " " + result.status().name().toLowerCase() + ": " + result.result();
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

    private record TaskResult(Status status, String result)
    {
    }
}
