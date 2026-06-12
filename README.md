# Octo AI

A demo personal AI assistant you host yourself and talk to through Telegram.

This is a learning project, inspired by [OpenClaw](https://openclaw.ai) and [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils). Its goal is to demonstrate the orchestration patterns and use of subagents behind those projects, and to show they're relatively easy to build yourself with [Spring AI](https://docs.spring.io/spring-ai/reference/). Octo's agents are built directly on spring-ai-agent-utils. Many thanks to its maintainers, especially to [Christian Tzolov](https://github.com/tzolov).

Telegram is the only supported channel for now.

## The main idea

- **The Orchestrator** sits at the top. It does no work itself. Instead it reads your request and decides who 
  handles it.
- **Subagents** each pursue one goal. A subagent is given a goal and runs it in an isolated context (a fresh conversation, no memory of other tasks) with only the tools it needs to reach that goal.
- **Tools** are concrete actions: find a file, read it, search file contents, list a folder, write or edit a file — all scoped to the project folder.

What happens when you send a message:

1. Your Telegram message reaches the **Orchestrator**.
2. The Orchestrator builds a plan of subagent tasks. Tasks with no dependencies run in parallel; a task that depends on another waits and is handed that task's output. A plan can run subagents fully in parallel, in sequence, or any combination of the two.
3. Each subagent is given its own goal.
4. The results are then aggregated and sent back to you on the same channel.

The only thing AI does at the top level is draw up the plan. The Orchestrator hands it back through its single tool, 
`executePlan`, which runs the subagent tasks. Each subagent runs in full isolation: its own fresh context, its own system prompt, and its own fixed set of tools, all defined up front in its file rather than chosen at runtime. None of them see each other's conversations or memory; they receive only the inputs the plan passes them. What's *not* AI is the glue: scheduling the tasks and the tools themselves (reading and writing files) are plain code.

You can run the whole thing inside a Docker container if you want stronger isolation and to keep it away from the 
rest of your machine.

## Agents

| Agent | What it does |
|---|---|
| `spring-ai-expert` | Answers questions about the Spring AI framework — features, configuration, APIs, RAG, tool calling, provider integrations. Consults the official docs and source code for accuracy. |
| `file-worker` | Locates, reads, and edits files in the project folder. Searches by name, path pattern, or content. |
| `greeting-responder` | Responds to greetings with a short, friendly joke. |

Adding a new agent means writing one more file like these (no code, for most additions). Add it, restart, and the Orchestrator can call on it.

## What's under the hood

You don't need to know any of this to understand the project, but for the curious:

- Uses Anthropic Haiku by default. You bring your own API key.
- Built in Java with Spring Boot 4 and Spring AI 2.
- The channels, subagents, and tools are each in their own module so they can grow independently.
- Conversation history (the last several messages) is stored in a database, so it can follow a thread across messages.
