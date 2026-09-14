package com.ftpview.util;

import java.io.IOException;

public final class PathValidator {
    private PathValidator() {}

    /** 拒绝路径注入及跨目录文件名。 */
    public static void name(String n) throws IOException {
        if (n == null
                || n.isBlank()
                || n.equals(".")
                || n.equals("..")
                || n.matches(".*[/\\\\\\r\\n\\x00].*")) {
            throw new IOException("无效文件名");
        }
    }

    /** 所有远端路径禁止注入 FTP 控制命令。 */
    public static String safe(String p) throws IOException {
        if (p == null || p.contains("\r") || p.contains("\n") || p.indexOf(0) >= 0) {
            throw new IOException("无效路径");
        }
        return p;
    }
}
