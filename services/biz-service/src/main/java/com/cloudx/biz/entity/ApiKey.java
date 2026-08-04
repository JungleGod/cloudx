package com.cloudx.biz.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String accessKey;
    private String secretKey;
    private String name;
    private Integer status;
    private Integer quotaDaily;
    private Integer quotaTotal;
    private LocalDateTime expiredAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
