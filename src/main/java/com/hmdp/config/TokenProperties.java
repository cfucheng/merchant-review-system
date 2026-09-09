package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 *@Author: chengfu Chen
 *@CreateTime: 2026-09-09  19:34
 *@Description: TODO
 *@Version: 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.token")
public class TokenProperties {
    /** 前端携带token的请求头字段名 */
    private String headerName = "authorization";
    /** Redis中token的key前缀 */
    private String keyPrefix = "login:token:";
    /** token有效期(分钟) */
    private Long ttlMinutes = 36000L;
}
