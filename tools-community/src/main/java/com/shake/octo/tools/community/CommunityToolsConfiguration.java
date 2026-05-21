package com.shake.octo.tools.community;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ListDirectoryTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Configuration
public class CommunityToolsConfiguration
{
    @Bean
    public List<ToolCallback> communityToolCallbacks()
    {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"));
        var globTool = GlobTool.builder().workingDirectory(workingDirectory).build();
        var grepTool = GrepTool.builder().workingDirectory(workingDirectory).build();
        var fileSystemTools = new FileSystemTools.Builder().build();
        var listDirectoryTool = ListDirectoryTool.builder().workingDirectory(workingDirectory).build();
        return List.of(ToolCallbacks.from(globTool, grepTool, fileSystemTools, listDirectoryTool));
    }
}
