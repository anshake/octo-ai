package com.shake.assistant.orchestrator;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public record AgentTask(
        @ToolParam(description = "name of the agent that is going to perform the task")
        String agentName,
        @ToolParam(description = "the prompt describing the work this subagent should do")
        String task,
        @ToolParam(description = "list of dependencies for the task; dependent tasks can not start until this task is" +
                " finished", required = false)
        List<AgentTask> dependencies)
{
}
