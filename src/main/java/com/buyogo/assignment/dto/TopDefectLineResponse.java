package com.buyogo.assignment.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TopDefectLineResponse {
    private String lineId; // We will map machineId to this
    private long totalDefects;
    private long eventCount;
    private double defectsPercent;
}
