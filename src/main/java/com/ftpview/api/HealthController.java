package com.ftpview.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    /** 提供前端联调所需的存活检查，不代表 FTP 连接已建立。 */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "FTPView");
    }
}
