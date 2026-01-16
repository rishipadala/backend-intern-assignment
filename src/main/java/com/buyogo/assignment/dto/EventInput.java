package com.buyogo.assignment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true) // Safer if they add extra fields
public record EventInput(
        String eventId,
        Instant eventTime,
        String machineId,
        long durationMs,
        int defectCount
) {}