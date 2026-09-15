package com.ftpview.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FTPViewApplication {
    /** 启动本机 FTPView 后端服务。 */
    public static void main(String[] args) {
        SpringApplication.run(FTPViewApplication.class, args);
    }
}
