package com.shake.assistant.orchestrator;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record ExecutionPlan(
        @ToolParam(description = "description of the plan")
        String description,
        @ToolParam(description = "list of tasks to be executed")
        List<AgentTask> agentTasks)
{
}
