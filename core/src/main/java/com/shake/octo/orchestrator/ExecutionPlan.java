package com.shake.octo.orchestrator;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record ExecutionPlan(
        @ToolParam(description = "a short, human-readable summary of the execution flow: name the subagents" +
                " involved, and state which tasks run in parallel and which wait for others to finish." +
                " Must be consistent with the dependencies declared below.")
        String executionSummary,
        @ToolParam(description = "list of tasks to be executed")
        List<AgentTask> agentTasks)
{
}
