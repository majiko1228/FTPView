package com.ftpview.dto;

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
    public long startedAt = System.currentTimeMillis();
}
