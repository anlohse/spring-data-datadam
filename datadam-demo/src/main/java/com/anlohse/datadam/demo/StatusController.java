package com.anlohse.datadam.demo;

import java.util.Map;
import com.anlohse.datadam.spring.DatadamCommands;
import org.springframework.web.bind.annotation.*;

@RestController
public class StatusController {
    private final DatadamCommands commands;
    public StatusController(DatadamCommands commands) { this.commands = commands; }
    @GetMapping("/") public Map<String, Object> status() {
        return Map.of("application", "Spring Data Datadam demo", "datadam", commands.execute("PING"),
            "customers", "/customers?city=SP", "search", "/customers/search?city=SP", "derived", "/customers/derived?city=SP",
            "temporaryNotes", "/temporary-notes?category=demo", "auditedNotes", "/audited-notes?category=demo",
            "versionedNotes", "/versioned-notes?category=demo");
    }
}
