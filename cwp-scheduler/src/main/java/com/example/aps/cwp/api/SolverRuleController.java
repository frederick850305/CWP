package com.example.aps.cwp.api;

import com.example.aps.cwp.service.SolverRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/solver-rules")
public class SolverRuleController {
    private final SolverRuleService service;

    public SolverRuleController(SolverRuleService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> current() { return service.currentState(); }

    @PostMapping("/interpret")
    public Map<String, Object> interpret(@RequestBody JsonNode body) {
        return service.interpret(body.path("message").asText(null));
    }

    @PostMapping("/proposals/{proposalId}/apply")
    public Map<String, Object> apply(@PathVariable String proposalId) { return service.apply(proposalId); }
}
