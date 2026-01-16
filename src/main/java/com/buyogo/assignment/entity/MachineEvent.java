package com.buyogo.assignment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "machine_events", indexes = {
        @Index(name = "idx_event_id", columnList = "eventId") // Crucial for fast deduplication lookups
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The ID provided by the machine (e.g., "E-123")
    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private Instant eventTime; // "2026-01-15T10:12:03.123Z"

    @Column(nullable = false)
    private String machineId; // "M-001"

    private long durationMs;

    private int defectCount;

    // We need to store this to handle the "newer receivedTime updates" rule [cite: 160]
    @Column(nullable = false)
    private Instant receivedTime;

    // Helper method to determine if defect should be counted
    public boolean isDefectKnown() {
        return this.defectCount != -1; // Rule: defectCount = -1 means "unknown" -> ignore [cite: 102]
    }
}
