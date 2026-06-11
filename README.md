# Octo AI

A demo personal AI assistant you host yourself and talk to through Telegram.

This is a learning project, inspired by [OpenClaw](https://openclaw.ai) and [spring-ai-agent-utils](https://github.com/spring-ai-community/spring-ai-agent-utils). Its goal is to demonstrate the orchestration patterns and use of subagents behind those projects — and to show they're relatively easy to build yourself with [Spring AI](https://docs.spring.io/spring-ai/reference/). Octo's agents are built directly on spring-ai-agent-utils. Many thanks to its maintainers, especially to [Christian Tzolov](https://github.com/tzolov).

You message it like you'd text a friend — through Telegram, for now — and it carries out tasks for you. Ask it a question, have it dig through the files of the project it's running in, or just say hello, and the right helper picks it up and replies.

Today it can answer questions about the Spring AI framework, work with files in your project (find, read, and edit them), and greet you with a joke. It's built so that adding the next skill is easy.

## The main idea

Octo is a small team, not a single chatbot:

- **The Orchestrator** sits at the top. It does no work itself — it reads your request and decides who handles it.
- **Subagents** each pursue one goal — answer Spring AI questions, work with project files, greet you with a joke. A subagent is given a goal and runs it in an isolated context (a fresh conversation, no memory of other tasks) with only the tools it needs to reach that goal.
- **Tools** are concrete actions: find a file, read it, search file contents, list a folder, write or edit a file — all inside the project folder. Plain, predictable code.

What happens when you send a message:

1. Your Telegram message reaches the **Orchestrator**.
2. The Orchestrator builds a plan of subagent tasks. Tasks with no dependencies run in parallel; a task that depends on another waits and is handed that task's output. A plan can run subagents fully in parallel, in sequence, or any combination of the two.
3. Each subagent runs as its own Claude call — isolated context, only its allowed tools — to reach the goal it was given.
4. The results are combined and sent back to you on Telegram.

The only thing Claude does at the top level is draw up the plan. The Orchestrator hands it back through its single tool, `executePlan`, which runs the subagent tasks. Each subagent runs in full isolation — its own fresh context, its own system prompt, and its own fixed set of tools, all defined up front in its file rather than chosen at runtime. None of them see each other's conversations or memory; they receive only the inputs the plan passes them. What's *not* AI is the glue: scheduling the tasks and the tools themselves (reading and writing files) are plain code.

Since subagents can read and write files in the folder Octo runs in, you can run the whole thing inside a Docker container if you want stronger isolation and to keep it away from the rest of your machine.

### Why this matters

The clever part is how easy it is to grow.

Each subagent is just a short text file naming its goal and which tools it may use. Teaching Octo a new skill means **writing one more file like that** — no code, for most additions. Add the file, restart, and the Orchestrator can call on it.

The same goes for how you talk to it. Telegram is the first way in, but the code is laid out so new channels can be added later without rebuilding what's already there. Each request remembers which channel it came in on, so the reply always goes back the same way — a message sent over Telegram is answered on Telegram.

## What's under the hood

You don't need to know any of this to understand the project, but for the curious:

- **Powered by Claude** — it uses Anthropic's Claude models (Claude Haiku by default) through the Claude API. You bring your own API key.
- **Built in Java** with Spring Boot and Spring AI.
- **Organized in clean, separate pieces** so each part (the channels you talk through, the subagents, the tools) can grow on its own.
- **Remembers the recent conversation** (the last several messages, stored in a database) so it can follow along across messages.
