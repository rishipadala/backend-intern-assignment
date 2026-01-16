package com.buyogo.assignment.service;

import com.buyogo.assignment.dto.BatchSummary;
import com.buyogo.assignment.dto.EventInput;
import com.buyogo.assignment.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class BenchmarkTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository repository;

    @BeforeEach
    void setup() {
        repository.deleteAll(); // Ensure DB is clean before benchmark
    }

    @Test
    public void benchmarkIngestion() {
        List<EventInput> batch = new ArrayList<>();
        Instant now = Instant.now();

        // 1. Generate 1,000 synthetic events
        for (int i = 0; i < 1000; i++) {
            batch.add(new EventInput(
                    "BM-" + i,              // Unique ID: BM-0, BM-1...
                    now,                    // eventTime
                    "M-BENCHMARK",          // machineId
                    1000,                   // durationMs
                    0                       // defectCount
            ));
        }

        // 2. Measure Time
        long start = System.currentTimeMillis();

        BatchSummary summary = eventService.processBatch(batch);

        long end = System.currentTimeMillis();
        long duration = end - start;

        // 3. Print Results to Console
        System.out.println("==================================================");
        System.out.println("BENCHMARK RESULT");
        System.out.println("Events Processed: " + 1000);
        System.out.println("Time Taken: " + duration + " ms");
        System.out.println("Requirement: < 1000 ms");
        System.out.println("==================================================");

        // 4. Fail the test if it's too slow (Safety check)
        if (duration > 1000) {
            throw new AssertionError("Benchmark FAILED: Took " + duration + "ms, expected < 1000ms");
        }
    }
}
