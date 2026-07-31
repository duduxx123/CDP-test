package com.demo.cdp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集中管理应用配置，支持 IDEA 中直接编辑 application.yml。
 */
@ConfigurationProperties(prefix = "app")
public record AppConfig(
        String mode,
        Chrome chrome,
        Search search,
        Douyin douyin
) {
    public record Chrome(int port, String executable) {}
    public record Search(String query, int count) {}
    public record Douyin(boolean checkLogin, int typingDelayMs, int maxScrollRounds, Dm dm) {
        public record Dm(int sendWaitMs, int inputTimeoutMs) {}
    }
}
