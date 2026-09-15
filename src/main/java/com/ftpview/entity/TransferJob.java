package com.ftpview.entity;

import java.util.UUID;

public class TransferJob {
    public String id = UUID.randomUUID().toString();
    public String name;
    public String direction;
    public volatile String status = "connecting";
    public volatile String error = "";
    public volatile String destination = "";
    public volatile String recoveryPath = "";
    public volatile long bytes;
    public volatile long size;
    public volatile long verifyBytes;
    public volatile long verifyTotal;
    public volatile long bytesPerSecond;
    public volatile long updatedAt = System.currentTimeMillis();
    public volatile String phase = "等待开始";
    public long startedAt = System.currentTimeMillis();
}
