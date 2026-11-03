package com.rideshare.notification_service.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkAllReadResponse {
    private int markedCount;
}
