package com.buyogo.assignment.dto;

import lombok.Builder;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class BatchSummary {
    private int accepted;
    private int deduped;
    private int updated;
    private int rejected;
    @Builder.Default
    private List<RejectionDetail> rejections = new ArrayList<>();

    public static class RejectionDetail {
        public String eventId;
        public String reason;

        public RejectionDetail(String eventId, String reason) {
            this.eventId = eventId;
            this.reason = reason;
        }
    }
}
