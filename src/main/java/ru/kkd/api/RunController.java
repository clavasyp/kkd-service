package ru.kkd.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.kkd.service.RunQueryService;

@RestController
public class RunController {

    private final RunQueryService runQueryService;

    public RunController(RunQueryService runQueryService) {
        this.runQueryService = runQueryService;
    }

    @GetMapping("/api/runs")
    public RunListResponse listRuns() {
        return RunListResponse.from(runQueryService.listRuns());
    }
}
