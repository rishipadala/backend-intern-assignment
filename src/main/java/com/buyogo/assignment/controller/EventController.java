package com.buyogo.assignment.controller;

import com.buyogo.assignment.dto.BatchSummary;
import com.buyogo.assignment.dto.EventInput;
import com.buyogo.assignment.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping("/batch")
    public ResponseEntity<BatchSummary> ingestBatch(@RequestBody List<EventInput> events) {
        BatchSummary summary = eventService.processBatch(events);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/stats")
    public ResponseEntity<com.buyogo.assignment.dto.StatsResponse> getStats(
            @RequestParam String machineId,
            @RequestParam String start, // Input as ISO string (e.g., 2026-01-15T10:00:00Z)
            @RequestParam String end
    ) {
        // Convert Strings to Instant
        Instant startInstant = Instant.parse(start);
        Instant endInstant = Instant.parse(end);

        com.buyogo.assignment.dto.StatsResponse response = eventService.getStats(machineId, startInstant, endInstant);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats/top-defect-lines")
    public ResponseEntity<List<com.buyogo.assignment.dto.TopDefectLineResponse>> getTopDefectLines(
            @RequestParam(required = false) String factoryId, // Not used but required by API spec
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(
                eventService.getTopDefectLines(Instant.parse(from), Instant.parse(to), limit)
        );
    }
}
