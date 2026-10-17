package com.lerobot.workbench.model;

public record PipelineModule(
        String id,
        String title,
        String description,
        String status,
        String route) {}
