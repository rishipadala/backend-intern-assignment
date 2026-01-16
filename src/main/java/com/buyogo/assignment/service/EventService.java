package com.buyogo.assignment.service;

import com.buyogo.assignment.dto.BatchSummary;
import com.buyogo.assignment.dto.EventInput;
import com.buyogo.assignment.entity.MachineEvent;
import com.buyogo.assignment.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository repository;

    @Transactional
    public BatchSummary processBatch(List<EventInput> inputs) {
        int accepted = 0;
        int deduped = 0;
        int updated = 0;
        int rejected = 0;
        List<BatchSummary.RejectionDetail> rejections = new ArrayList<>();

        Instant now = Instant.now();
        Instant futureThreshold = now.plus(15, ChronoUnit.MINUTES);

        // 1. OPTIMIZATION: Extract all IDs to fetch in bulk
        List<String> incomingIds = inputs.stream()
                .map(EventInput::eventId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 2. OPTIMIZATION: Fetch all existing records in ONE query
        Map<String, MachineEvent> existingMap = repository.findByEventIdIn(incomingIds)
                .stream()
                .collect(Collectors.toMap(MachineEvent::getEventId, Function.identity()));

        // We will collect all changes here and save them in ONE go at the end
        List<MachineEvent> toSave = new ArrayList<>();
        // Set to track IDs we've processed in this batch to handle intra-batch duplicates
        Set<String> processedInBatch = new HashSet<>();

        for (EventInput input : inputs) {
            // --- Validation Logic ---
            if (input.durationMs() < 0 || input.durationMs() > 21_600_000) {
                rejected++;
                rejections.add(new BatchSummary.RejectionDetail(input.eventId(), "INVALID_DURATION"));
                continue;
            }
            if (input.eventTime().isAfter(futureThreshold)) {
                rejected++;
                rejections.add(new BatchSummary.RejectionDetail(input.eventId(), "FUTURE_EVENT_TIME"));
                continue;
            }
            if (input.eventId() == null || input.machineId() == null) {
                rejected++;
                rejections.add(new BatchSummary.RejectionDetail(input.eventId(), "MISSING_MANDATORY_FIELDS"));
                continue;
            }

            // --- Logic ---
            MachineEvent existing = existingMap.get(input.eventId());

            if (existing != null) {
                // Check payload equality
                boolean isPayloadIdentical =
                        existing.getMachineId().equals(input.machineId()) &&
                                existing.getEventTime().equals(input.eventTime()) &&
                                existing.getDurationMs() == input.durationMs() &&
                                existing.getDefectCount() == input.defectCount();

                if (isPayloadIdentical) {
                    deduped++;
                } else {
                    // Update Rule: Only if DB record is OLDER than 'now'
                    if (existing.getReceivedTime().isAfter(now)) {
                        deduped++;
                    } else {
                        updateEventInMemory(existing, input, now);
                        // Add to save list only if not already added (avoid duplicates in list)
                        if (!processedInBatch.contains(existing.getEventId())) {
                            toSave.add(existing);
                            processedInBatch.add(existing.getEventId());
                        }
                        updated++;
                    }
                }
            } else {
                // Create New
                MachineEvent newEvent = createEventInMemory(input, now);
                // Put into map so subsequent items in THIS batch see it
                existingMap.put(newEvent.getEventId(), newEvent);

                toSave.add(newEvent);
                processedInBatch.add(newEvent.getEventId());
                accepted++;
            }
        }

        // 3. OPTIMIZATION: Save everything in ONE query
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }

        return BatchSummary.builder()
                .accepted(accepted)
                .deduped(deduped)
                .updated(updated)
                .rejected(rejected)
                .rejections(rejections)
                .build();
    }

    private MachineEvent createEventInMemory(EventInput input, Instant receivedTime) {
        return MachineEvent.builder()
                .eventId(input.eventId())
                .eventTime(input.eventTime())
                .receivedTime(receivedTime)
                .machineId(input.machineId())
                .durationMs(input.durationMs())
                .defectCount(input.defectCount())
                .build();
    }

    private void updateEventInMemory(MachineEvent existing, EventInput input, Instant receivedTime) {
        existing.setMachineId(input.machineId());
        existing.setEventTime(input.eventTime());
        existing.setReceivedTime(receivedTime);
        existing.setDurationMs(input.durationMs());
        existing.setDefectCount(input.defectCount());
    }

    public com.buyogo.assignment.dto.StatsResponse getStats(String machineId, Instant start, Instant end) {
        // 1. Fetch valid events in the time window (Start Inclusive, End Exclusive)
        List<MachineEvent> events = repository.findEventsForStats(machineId, start, end);

        // 2. Calculate Events Count
        long eventsCount = events.size();

        // 3. Calculate Defects Count (Ignore -1)
        long defectsCount = events.stream()
                .filter(MachineEvent::isDefectKnown) // Helper method we added to Entity
                .mapToInt(MachineEvent::getDefectCount)
                .sum();

        // 4. Calculate Duration in Hours
        // Assignment says: windowHours = duration in seconds / 3600.0
        long windowSeconds = java.time.Duration.between(start, end).getSeconds();

        // Safety check to avoid division by zero
        double windowHours = (windowSeconds == 0) ? 0 : windowSeconds / 3600.0;

        // 5. Calculate Average Defect Rate
        double avgDefectRate = 0.0;
        if (windowHours > 0) {
            avgDefectRate = defectsCount / windowHours;
        }

        // 6. Determine Status
        String status = (avgDefectRate < 2.0) ? "Healthy" : "Warning";

        // 7. Return Response
        return com.buyogo.assignment.dto.StatsResponse.builder()
                .machineId(machineId)
                .start(start.toString())
                .end(end.toString())
                .eventsCount(eventsCount)
                .defectsCount(defectsCount)
                .avgDefectRate(Double.parseDouble(String.format("%.2f", avgDefectRate))) // Round to 2 decimals for clean output
                .status(status)
                .build();
    }

    public List<com.buyogo.assignment.dto.TopDefectLineResponse> getTopDefectLines(Instant start, Instant end, int limit) {
        List<Object[]> results = repository.findTopDefects(start, end);

        return results.stream()
                .limit(limit)
                .map(row -> {
                    String machineId = (String) row[0];
                    long eventCount = ((Number) row[1]).longValue();
                    long totalDefects = ((Number) row[2]).longValue();

                    // Calculation: (Defect Count / Event Count) * 100
                    double defectPercent = (eventCount == 0) ? 0.0 : ((double) totalDefects / eventCount) * 100.0;

                    return com.buyogo.assignment.dto.TopDefectLineResponse.builder()
                            .lineId(machineId) // Mapping Machine -> Line
                            .totalDefects(totalDefects)
                            .eventCount(eventCount)
                            .defectsPercent(Double.parseDouble(String.format("%.2f", defectPercent)))
                            .build();
                })
                .collect(java.util.stream.Collectors.toList());
    }
}
