package com.ftpview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ftpview.dto.ConnectionConfig;
import com.ftpview.dto.TransferJob;
import com.ftpview.dto.WorkspaceRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;

class TransferQueueTest {
    /** 第一个任务未结束时第二个保持排队，结束后继续执行。 */
    @Test
    void serializesMultipleFiles() throws Exception {
        FtpSessionService sessions = mock(FtpSessionService.class);
        when(sessions.session("test"))
                .thenReturn(new FtpSessionService.Session(config(), new FTPClient()));
        CountDownLatch firstStarted = new CountDownLatch(1),
                release = new CountDownLatch(1),
                secondStarted = new CountDownLatch(1);
        TransferService service =
                new TransferService(
                        sessions,
                        mock(FtpConnectionService.class),
                        java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    /** 用可控任务隔离队列调度验证，不访问 FTP 服务器。 */
                    @Override
                    void transfer(
                            TransferJob job, WorkspaceRequest request, ConnectionConfig config) {
                        try {
                            if (request.name.equals("one.txt")) {
                                firstStarted.countDown();
                                release.await(5, TimeUnit.SECONDS);
                            } else {
                                secondStarted.countDown();
                            }
                            job.status = "completed";
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            job.status = "failed";
                        }
                    }
                };
        try {
            WorkspaceRequest first = new WorkspaceRequest();
            first.session = "test";
            first.direction = "upload";
            first.name = "one.txt";
            first.localPath = System.getProperty("java.io.tmpdir");
            first.remotePath = "/";
            service.submit(first);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            WorkspaceRequest second = new WorkspaceRequest();
            second.session = "test";
            second.direction = "upload";
            second.name = "two.txt";
            second.localPath = first.localPath;
            second.remotePath = "/";
            TransferJob queued = service.submit(second);
            assertEquals("queued", queued.status);
            assertEquals(1, secondStarted.getCount());
            release.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    /** 提供合法的连接标识供任务去重测试使用。 */
    private ConnectionConfig config() {
        ConnectionConfig c = new ConnectionConfig();
        c.host = "localhost";
        return c;
    }
}
