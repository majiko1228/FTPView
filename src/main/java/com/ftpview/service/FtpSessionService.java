package com.ftpview.service;

import com.ftpview.entity.ConnectionConfig;
import com.ftpview.entity.WorkspaceRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.stereotype.Service;

@Service
public class FtpSessionService {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final FtpConnectionService connections;

    /** 注入 FTP 连接工厂，独立管理浏览会话生命周期。 */
    public FtpSessionService(FtpConnectionService connections) {
        this.connections = connections;
    }

    public static class Session {
        final ConnectionConfig config;
        final FTPClient client;

        /** 保存会话配置，传输连接按需独立创建。 */
        Session(ConnectionConfig config, FTPClient client) {
            this.config = config;
            this.client = client;
        }
    }

    /** 根据不可猜测的会话标识获取已登录连接。 */
    public Session session(String id) throws IOException {
        Session session = id == null ? null : sessions.get(id);
        if (session == null) {
            throw new IOException("连接已失效，请重新连接");
        }
        return session;
    }

    /** 创建目录会话，避免每次切换目录重复登录。 */
    public Map<String, String> login(ConnectionConfig config) throws Exception {
        if (sessions.size() >= 20) {
            throw new IOException("连接数已达上限，请断开旧连接");
        }
        FTPClient ftpClient = connections.connect(config);
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(config, ftpClient));
        return Map.of("session", id);
    }

    /** 断开浏览会话；独立传输不受影响。 */
    public void disconnect(WorkspaceRequest request) {
        Session session = sessions.remove(request.session);
        if (session != null) {
            synchronized (session) {
                connections.close(session.client);
            }
        }
    }

    /** 释放全部浏览连接。 */
    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(session -> connections.close(session.client));
    }
}
