package com.cloudx.biz.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ApiKeyVO {

    private Long id;
    private String accessKey;
    private String secretKey;
    private String name;
    private Integer status;
    private Integer quotaDaily;
    private Integer quotaTotal;
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;
}
