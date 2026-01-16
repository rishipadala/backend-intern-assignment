package com.buyogo.assignment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatsResponse {
    private String machineId;
    private String start; // ISO String
    private String end;   // ISO String
    private long eventsCount;
    private long defectsCount;
    private double avgDefectRate;
    private String status; // "Healthy" or "Warning"
}