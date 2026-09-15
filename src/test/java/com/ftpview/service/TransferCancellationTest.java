package com.ftpview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ftpview.entity.ConnectionConfig;
import com.ftpview.entity.TransferJob;
import com.ftpview.entity.WorkspaceRequest;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferCancellationTest {
    @TempDir Path directory;

    /** 排队时拒绝跨会话重复任务，取消后允许再次提交。 */
    @Test
    void rejectsDuplicateAndCancelsQueued() throws Exception {
        FtpConnectionService connections = mock(FtpConnectionService.class);
        FtpSessionService sessions = sessions();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(
                () -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        TransferService service = new TransferService(sessions, connections, executor);
        try {
            WorkspaceRequest first = request();
            TransferJob job = service.submit(first);
            WorkspaceRequest duplicate = request();
            duplicate.session = "other-session";
            assertThrows(IOException.class, () -> service.submit(duplicate));
            assertEquals("cancelled", service.cancel(job.id).status);
            TransferJob retry = service.submit(duplicate);
            assertNotEquals(job.id, retry.id);
            service.cancel(retry.id);
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            verifyNoInteractions(connections);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    /** 校验阶段取消必须中断回读，且不能把临时文件发布为正式文件。 */
    @Test
    void cancelsVerificationWithoutPublishing() throws Exception {
        Files.writeString(directory.resolve("sample.txt"), "hello");
        FTPClient ftp = mock(FTPClient.class);
        FtpConnectionService connections = mock(FtpConnectionService.class);
        when(connections.connect(any())).thenReturn(ftp);
        when(ftp.changeWorkingDirectory("/")).thenReturn(true);
        when(ftp.makeDirectory(anyString())).thenReturn(true);
        when(ftp.storeFileStream(anyString())).thenReturn(new ByteArrayOutputStream());
        when(ftp.completePendingCommand()).thenReturn(true);
        CountDownLatch reading = new CountDownLatch(1), closed = new CountDownLatch(1);
        InputStream stalled =
                new InputStream() {
                    /** 模拟服务器回读停顿，关闭流后立即结束等待。 */
                    @Override
                    public int read() throws IOException {
                        reading.countDown();
                        try {
                            closed.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IOException("stream closed");
                    }

                    /** 取消动作唤醒正在等待的读操作。 */
                    @Override
                    public void close() {
                        closed.countDown();
                    }
                };
        when(ftp.retrieveFileStream(anyString())).thenReturn(stalled);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransferService service = new TransferService(sessions(), connections, executor);
        try {
            TransferJob job = service.submit(request());
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertEquals("verifying", job.status);
            service.cancel(job.id);
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals("cancelled", job.status);
            assertEquals(0, job.bytesPerSecond);
            assertEquals(5, job.bytes);
            verify(ftp, never()).rename(anyString(), anyString());
            assertEquals("hello", Files.readString(directory.resolve("sample.txt")));
        } finally {
            closed.countDown();
            service.shutdown();
        }
    }

    /** 为不同会话返回同一服务器账号，验证身份去重不依赖会话标识。 */
    private FtpSessionService sessions() throws Exception {
        ConnectionConfig config = new ConnectionConfig();
        config.host = "localhost";
        FtpSessionService sessions = mock(FtpSessionService.class);
        when(sessions.session(anyString()))
                .thenReturn(new FtpSessionService.Session(config, new FTPClient()));
        return sessions;
    }

    /** 使用临时本机目录，测试不访问真实 FTP 或用户文件。 */
    private WorkspaceRequest request() {
        WorkspaceRequest r = new WorkspaceRequest();
        r.session = "test";
        r.localPath = directory.toString();
        r.remotePath = "/";
        r.direction = "upload";
        r.name = "sample.txt";
        return r;
    }
}
