package com.ftpview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ftpview.dto.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.net.ftp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAndTransferServiceTest {
    @TempDir Path dir;

    /** 本机列表隐藏点文件，支持目录删除并拒绝路径穿越。 */
    @Test
    void localListingAndDeletion() throws Exception {
        FtpConnectionService connections = new FtpConnectionService();
        FtpSessionService sessions = new FtpSessionService(connections);
        TransferService transfers = new TransferService(sessions, connections);
        FileService controller = new FileService(sessions, transfers);
        try {
            Files.writeString(dir.resolve("a.txt"), "keep");
            Files.writeString(dir.resolve(".hidden"), "secret");
            Files.createDirectory(dir.resolve("folder"));
            List<?> entries = (List<?>) controller.local(dir.toString()).get("files");
            assertEquals(2, entries.size());
            WorkspaceRequest r = new WorkspaceRequest();
            r.path = dir.toString();
            r.name = "folder";
            controller.delete(r);
            assertFalse(Files.exists(dir.resolve("folder")));
            r.name = "../a.txt";
            assertThrows(IOException.class, () -> controller.delete(r));
            r.name = "a.txt";
            controller.delete(r);
            assertFalse(Files.exists(dir.resolve("a.txt")));
        } finally {
            transfers.shutdown();
            sessions.shutdown();
        }
    }

    /** 模拟 FTP 流验证完整下载，并确认同名文件不会被覆盖。 */
    @Test
    void downloadVerifiedAndNoOverwrite() throws Exception {
        FTPClient ftp = mock(FTPClient.class);
        byte[] content = {0, 1, 2, (byte) 255};
        FTPFile file = new FTPFile();
        file.setName("test.bin");
        file.setType(FTPFile.FILE_TYPE);
        file.setSize(content.length);
        when(ftp.changeWorkingDirectory("/")).thenReturn(true);
        when(ftp.listFiles()).thenReturn(new FTPFile[] {file});
        when(ftp.retrieveFileStream("test.bin")).thenAnswer(i -> new ByteArrayInputStream(content));
        when(ftp.completePendingCommand()).thenReturn(true);
        FtpConnectionService connections =
                new FtpConnectionService() {
                    @Override
                    public FTPClient connect(ConnectionConfig c) {
                        return ftp;
                    }
                };
        FtpSessionService sessions = new FtpSessionService(connections);
        TransferService transfers = new TransferService(sessions, connections);
        TransferService controller = transfers;
        try {
            WorkspaceRequest r = new WorkspaceRequest();
            r.name = "test.bin";
            r.direction = "download";
            r.remotePath = "/";
            r.localPath = dir.toString();
            TransferJob j = new TransferJob();
            controller.transfer(j, r, new ConnectionConfig());
            assertEquals("completed", j.status);
            assertArrayEquals(content, Files.readAllBytes(dir.resolve("test.bin")));
            TransferJob duplicate = new TransferJob();
            controller.transfer(duplicate, r, new ConnectionConfig());
            assertEquals("failed", duplicate.status);
            assertArrayEquals(content, Files.readAllBytes(dir.resolve("test.bin")));
        } finally {
            transfers.shutdown();
            sessions.shutdown();
        }
    }

    /** 上传校验失败必须保留源文件，并禁止发布临时文件。 */
    @Test
    void corruptUploadIsNotPublished() throws Exception {
        Files.writeString(dir.resolve("test.txt"), "hello");
        FTPClient ftp = mock(FTPClient.class);
        when(ftp.changeWorkingDirectory("/")).thenReturn(true);
        when(ftp.makeDirectory(anyString())).thenReturn(true);
        when(ftp.storeFileStream(anyString())).thenReturn(new ByteArrayOutputStream());
        when(ftp.completePendingCommand()).thenReturn(true);
        when(ftp.retrieveFileStream(anyString()))
                .thenReturn(new ByteArrayInputStream("wrong".getBytes()));
        FtpConnectionService connections =
                new FtpConnectionService() {
                    @Override
                    public FTPClient connect(ConnectionConfig c) {
                        return ftp;
                    }
                };
        FtpSessionService sessions = new FtpSessionService(connections);
        TransferService transfers = new TransferService(sessions, connections);
        TransferService controller = transfers;
        try {
            WorkspaceRequest r = new WorkspaceRequest();
            r.name = "test.txt";
            r.direction = "upload";
            r.remotePath = "/";
            r.localPath = dir.toString();
            TransferJob j = new TransferJob();
            controller.transfer(j, r, new ConnectionConfig());
            assertEquals("failed", j.status);
            verify(ftp, never()).rename(anyString(), anyString());
            assertEquals("hello", Files.readString(dir.resolve("test.txt")));
        } finally {
            transfers.shutdown();
            sessions.shutdown();
        }
    }
}
