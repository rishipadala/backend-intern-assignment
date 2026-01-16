package com.buyogo.assignment.service;

import com.buyogo.assignment.dto.BatchSummary;
import com.buyogo.assignment.dto.EventInput;
import com.buyogo.assignment.dto.StatsResponse;
import com.buyogo.assignment.entity.MachineEvent;
import com.buyogo.assignment.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository repository;

    @BeforeEach
    void setup() {
        repository.deleteAll(); // clear DB before every test
    }

    // HELPER: Removed 'rxTime' because EventInput no longer has it
    private EventInput createEvent(String id, String machineId, long duration, int defectCount, Instant evtTime) {
        // FIX: Truncate to MILLIS to ensure Java matches the H2 Database precision exactly
        return new EventInput(id, evtTime.truncatedTo(ChronoUnit.MILLIS), machineId, duration, defectCount);
    }

    // --- Requirement 1: Identical duplicate eventId -> deduped [cite: 159] ---
    @Test
    void testIdenticalDuplicateDeduped() {
        Instant now = Instant.now();
        EventInput e1 = createEvent("E-1", "M1", 100, 0, now);

        // Send batch 1
        eventService.processBatch(List.of(e1));

        // Send batch 2 (Identical payload)
        BatchSummary summary = eventService.processBatch(List.of(e1));

        assertEquals(1, summary.getDeduped());
        assertEquals(0, summary.getAccepted());
        assertEquals(1, repository.count()); // Still only 1 record in DB
    }

    // --- Requirement 2: Different payload + newer receivedTime -> update happens [cite: 160] ---
    @Test
    void testUpdateWithNewerReceivedTime() throws InterruptedException {
        Instant now = Instant.now();
        EventInput oldVer = createEvent("E-1", "M1", 100, 0, now);

        // 1. Process First Version
        eventService.processBatch(List.of(oldVer));

        // 2. Wait 20ms to ensure the next batch gets a newer system time
        Thread.sleep(20);

        // 3. Process Newer Version
        EventInput newVer = createEvent("E-1", "M1", 200, 5, now);
        BatchSummary summary = eventService.processBatch(List.of(newVer));

        assertEquals(1, summary.getUpdated());
        MachineEvent stored = repository.findByEventId("E-1").get();
        assertEquals(200, stored.getDurationMs()); // Should be updated to 200
    }

    // --- Requirement 3: Different payload + older receivedTime -> ignored [cite: 161] ---
    @Test
    void testIgnoreOlderReceivedTime() {
        // Strategy: Since we force 'now()' in the service, we simulate an "older" request
        // by manually putting a record in the DB that looks like it is from the FUTURE.

        // 1. Inject "Future" Record into DB
        Instant future = Instant.now().plusSeconds(60);
        MachineEvent futureEvent = MachineEvent.builder()
                .eventId("E-OLD-TEST")
                .machineId("M1")
                .eventTime(Instant.now())
                .receivedTime(future) // DB has time T+60s
                .durationMs(500)
                .defectCount(0)
                .build();
        repository.save(futureEvent);

        // 2. Send "Now" Update (Time T)
        // The service compares T (Input) vs T+60s (DB). T is older, so it should ignore.
        EventInput oldUpdate = createEvent("E-OLD-TEST", "M1", 100, 0, Instant.now());
        BatchSummary summary = eventService.processBatch(List.of(oldUpdate));

        assertEquals(1, summary.getDeduped()); // Treated as duplicate/ignored
        assertEquals(0, summary.getUpdated());

        MachineEvent stored = repository.findByEventId("E-OLD-TEST").get();
        assertEquals(500, stored.getDurationMs()); // Should remain 500 (from future record)
    }

    // --- Requirement 4: Invalid duration rejected [cite: 162] ---
    @Test
    void testInvalidDurationRejected() {
        Instant now = Instant.now();
        // duration > 6 hours (21600000ms)
        EventInput invalid = createEvent("E-1", "M1", 22_000_000, 0, now);

        BatchSummary summary = eventService.processBatch(List.of(invalid));

        assertEquals(1, summary.getRejected());
        assertEquals("INVALID_DURATION", summary.getRejections().get(0).reason);
    }

    // --- Requirement 5: Future eventTime rejected [cite: 163] ---
    @Test
    void testFutureEventTimeRejected() {
        Instant future = Instant.now().plus(20, ChronoUnit.MINUTES); // > 15 mins limit
        EventInput invalid = createEvent("E-1", "M1", 100, 0, future);

        BatchSummary summary = eventService.processBatch(List.of(invalid));

        assertEquals(1, summary.getRejected());
        assertEquals("FUTURE_EVENT_TIME", summary.getRejections().get(0).reason);
    }

    // --- Requirement 6: DefectCount = -1 ignored in defect totals [cite: 164] ---
    @Test
    void testUnknownDefectsIgnored() {
        Instant now = Instant.now();
        EventInput e1 = createEvent("E-1", "M1", 3600000, 10, now); // 1 hr, 10 defects
        EventInput e2 = createEvent("E-2", "M1", 3600000, -1, now.plusSeconds(1)); // 1 hr, unknown defects

        eventService.processBatch(List.of(e1, e2));

        // Query stats
        StatsResponse stats = eventService.getStats("M1", now.minusSeconds(1), now.plus(2,ChronoUnit.HOURS));

        assertEquals(2, stats.getEventsCount()); // Both events exist
        assertEquals(10, stats.getDefectsCount()); // Only 10 counted, -1 ignored
    }

    // --- Requirement 7: Start/end boundary correctness [cite: 165] ---
    @Test
    void testTimeBoundaries() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        Instant end = Instant.parse("2026-01-01T11:00:00Z");

        EventInput inside = createEvent("E-1", "M1", 100, 0, start.plusSeconds(10));
        EventInput exactStart = createEvent("E-2", "M1", 100, 0, start);
        EventInput exactEnd = createEvent("E-3", "M1", 100, 0, end); // Should be excluded

        eventService.processBatch(List.of(inside, exactStart, exactEnd));

        StatsResponse stats = eventService.getStats("M1", start, end);

        assertEquals(2, stats.getEventsCount()); // E-1 and E-2 included. E-3 excluded (End Exclusive)
    }

    // --- Requirement 8: Thread-safety test [cite: 166] ---
    @Test
    void testConcurrency() throws InterruptedException {
        int threadCount = 10;
        int eventsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                List<EventInput> batch = new ArrayList<>();
                for (int j = 0; j < eventsPerThread; j++) {
                    // Unique ID per event: T-0-0, T-0-1, etc.
                    String id = "T-" + threadId + "-" + j;
                    batch.add(createEvent(id, "M1", 100, 0, Instant.now()));
                }
                eventService.processBatch(batch);
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS); // Wait for all threads
        assertEquals(threadCount * eventsPerThread, repository.count()); // Ensure no data lost
    }
}