package com.ftpview.service;

import com.ftpview.entity.ConnectionConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;

@Service
public class FtpConnectionService {
    /** 建立二进制被动模式连接，超时失败不降级跳过验证。 */
    public FTPClient connect(ConnectionConfig config) throws Exception {
        if (config.host == null
                || config.host.isBlank()
                || config.port < 1
                || config.port > 65535) {
            throw new IOException("请填写有效的主机和端口");
        }
        if (!List.of("FTP", "FTPS", "FTPS implicit").contains(config.protocol)) {
            throw new IOException("不支持的协议");
        }
        if (!List.of("UTF-8", "GBK", "GB18030", "Big5", "ISO-8859-1").contains(config.encoding)) {
            throw new IOException("不支持的编码");
        }
        FTPClient ftpClient;
        if (config.protocol.equals("FTP")) {
            ftpClient = new FTPClient();
        } else {
            FTPSClient tls = new FTPSClient(config.protocol.equals("FTPS implicit"));
            tls.setEndpointCheckingEnabled(!config.ignoreCertificate);
            tls.setTrustManager(
                    config.ignoreCertificate
                            ? org.apache.commons.net.util.TrustManagerUtils
                                    .getAcceptAllTrustManager()
                            : org.apache.commons.net.util.TrustManagerUtils.getDefaultTrustManager(
                                    null));
            ftpClient = tls;
        }
        try {
            ftpClient.setControlEncoding(config.encoding);
            ftpClient.setConnectTimeout(10000);
            ftpClient.setDefaultTimeout(15000);
            ftpClient.setDataTimeout(Duration.ofSeconds(30));
            ftpClient.connect(config.host, config.port);
            ftpClient.setSoTimeout(15000);
            if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())
                    || !ftpClient.login(config.user, config.password)) {
                throw new IOException("FTP 登录失败");
            }
            if (ftpClient instanceof FTPSClient) {
                ((FTPSClient) ftpClient).execPBSZ(0);
                ((FTPSClient) ftpClient).execPROT("P");
            }
            ftpClient.enterLocalPassiveMode();
            if (!ftpClient.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IOException("无法启用二进制传输");
            }
            ftpClient.sendCommand("OPTS", config.encoding.equals("UTF-8") ? "UTF8 ON" : "UTF8 OFF");
            return ftpClient;
        } catch (Exception exception) {
            close(ftpClient);
            throw exception;
        }
    }

    /** 释放网络资源，不让清理错误掩盖原始异常。 */
    public void close(FTPClient ftpClient) {
        try {
            if (ftpClient.isConnected()) {
                ftpClient.disconnect();
            }
        } catch (IOException ignored) {
        }
    }
}
