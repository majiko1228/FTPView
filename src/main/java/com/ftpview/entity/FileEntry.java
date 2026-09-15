package com.ftpview.entity;

public class FileEntry {
    public String name;
    public String type;
    public String modifiedAt;
    public long size;

    /** 用统一字段描述本地和远端列表。 */
    public FileEntry(String name, String type, long size, String modifiedAt) {
        this.name = name;
        this.type = type;
        this.size = size;
        this.modifiedAt = modifiedAt;
    }
}
