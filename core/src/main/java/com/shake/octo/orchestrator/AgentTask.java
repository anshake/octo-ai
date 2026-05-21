package com.shake.octo.orchestrator;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record AgentTask(
        @ToolParam(description = "unique id for this task, referenced by other tasks in their dependencies")
        String id,
        @ToolParam(description = "name of the agent that is going to perform the task")
        String agentName,
        @ToolParam(description = "the prompt describing the work this subagent should do")
        String task,
        @ToolParam(description = "ids of tasks that must finish before this one starts", required = false)
        List<String> dependencies)
{
}
