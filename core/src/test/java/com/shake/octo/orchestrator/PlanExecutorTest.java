package com.shake.octo.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanExecutorTest
{
    private static final String GREETING = "greeting-responder";
    private static final String FINDER = "file-worker";
    private static final String AI = "spring-ai-expert";

    private final List<String> capturedPrompts = new CopyOnWriteArrayList<>();
    private PlanExecutor executor;

    @BeforeEach
    void setUp()
    {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);

        when(builder.defaultAdvisors(any(Advisor.class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);

        when(chatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.tools(any(Object[].class))).thenReturn(reqSpec);
        // Each user(...) call records the prompt and returns its own tail bound to that
        // prompt's reply, so parallel tasks stay deterministic without shared mutable state.
        when(reqSpec.user(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            capturedPrompts.add(prompt);
            ChatClient.ChatClientRequestSpec afterUser = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
            when(afterUser.call()).thenReturn(call);
            when(call.content()).thenReturn(replyFor(prompt));
            return afterUser;
        });

        executor = new PlanExecutor(builder, List.of(), new ClassPathResource("agents"));
    }

    @Test
    void sequentialChain_passesEachResultToNext()
    {
        var a = new AgentTask("a", GREETING, "TASK_A", null);
        var b = new AgentTask("b", FINDER, "TASK_B", List.of("a"));
        var c = new AgentTask("c", AI, "TASK_C", List.of("b"));

        executor.execute(new ExecutionPlan("summary", List.of(a, b, c)));

        assertThat(promptFor("TASK_B"))
                .contains("=== Inputs from prior tasks ===")
                .contains("[" + GREETING + "] RESULT_A")
                .endsWith("=== Your task ===\nTASK_B");

        assertThat(promptFor("TASK_C"))
                .contains("[" + FINDER + "] RESULT_B")
                .doesNotContain("RESULT_A") // only the direct predecessor is injected, not transitive
                .endsWith("=== Your task ===\nTASK_C");
    }

    @Test
    void parallelTasks_resultsFeedJoinTask()
    {
        // (A, B) --> C
        var a = new AgentTask("a", GREETING, "TASK_A", null);
        var b = new AgentTask("b", FINDER, "TASK_B", null);
        var c = new AgentTask("c", AI, "TASK_C", List.of("a", "b"));

        executor.execute(new ExecutionPlan("summary", List.of(a, b, c)));

        // the two upstream tasks run with no injected inputs
        assertThat(promptFor("TASK_A")).isEqualTo("TASK_A");
        assertThat(promptFor("TASK_B")).isEqualTo("TASK_B");

        assertThat(promptFor("TASK_C"))
                .contains("=== Inputs from prior tasks ===")
                .contains("[" + GREETING + "] RESULT_A")
                .contains("[" + FINDER + "] RESULT_B")
                .endsWith("=== Your task ===\nTASK_C");
    }

    @Test
    void hybrid_mixesSequentialAndParallelInputs()
    {
        var a = new AgentTask("a", GREETING, "TASK_A", null);
        var b = new AgentTask("b", FINDER, "TASK_B", List.of("a"));   // sequential leg: B consumes A
        var c = new AgentTask("c", AI, "TASK_C", null);              // parallel leg
        var d = new AgentTask("d", GREETING, "TASK_D", List.of("b", "c")); // join: D consumes B and C

        executor.execute(new ExecutionPlan("summary", List.of(a, b, c, d)));

        assertThat(promptFor("TASK_B"))
                .contains("[" + GREETING + "] RESULT_A")
                .endsWith("=== Your task ===\nTASK_B");

        assertThat(promptFor("TASK_D"))
                .contains("[" + FINDER + "] RESULT_B")
                .contains("[" + AI + "] RESULT_C")
                .endsWith("=== Your task ===\nTASK_D");
    }

    private String promptFor(String taskText)
    {
        return capturedPrompts.stream()
                              .filter(p -> p.endsWith(taskText))
                              .findFirst()
                              .orElseThrow(() -> new AssertionError("no prompt captured for " + taskText));
    }

    // The prompt always ends with the task's own text ("TASK_x"); reply with the matching "RESULT_x".
    private static String replyFor(String prompt)
    {
        int marker = prompt.lastIndexOf("TASK_");
        return marker < 0 ? "RESULT_?" : "RESULT_" + prompt.substring(marker + "TASK_".length());
    }
}