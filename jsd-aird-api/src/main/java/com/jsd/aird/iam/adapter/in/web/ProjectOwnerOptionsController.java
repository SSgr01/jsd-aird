package com.jsd.aird.iam.adapter.in.web;

import com.jsd.aird.iam.application.ProjectOwnerOptionsService;
import com.jsd.aird.platform.web.RequestIdHolder;
import com.jsd.aird.shared.api.ApiResponse;
import com.jsd.aird.shared.api.ResponseFactory;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProjectOwnerOptionsController {
    private final ProjectOwnerOptionsService service;

    public ProjectOwnerOptionsController(ProjectOwnerOptionsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/iam/project-owner-options")
    public ApiResponse<List<ProjectOwnerOptionsService.OwnerOption>> list() {
        return ResponseFactory.success(service.list(ActorContext.required()), RequestIdHolder.currentOrUnknown());
    }
}
