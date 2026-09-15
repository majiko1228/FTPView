package com.ftpview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ftpview.entity.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.*;
import org.apache.commons.net.ftp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderServiceTest {
    @TempDir Path base;

    /** FTP 模拟以临时文件系统实现，测试不访问用户文件或真实服务器。 */
    private FTPClient ftp(Path root) throws Exception {
        FTPClient client = mock(FTPClient.class);
        AtomicReference<Path> current = new AtomicReference<>(root);
        when(client.changeWorkingDirectory(anyString()))
                .thenAnswer(
                        call -> {
                            String p = call.getArgument(0);
                            Path target =
                                    (p.startsWith("/")
                                                    ? root.resolve(p.substring(1))
                                                    : current.get().resolve(p))
                                            .normalize();
                            if (!target.startsWith(root)
                                    || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                                return false;
                            current.set(target);
                            return true;
                        });
        when(client.printWorkingDirectory())
                .thenAnswer(
                        call -> "/" + root.relativize(current.get()).toString().replace('\\', '/'));
        when(client.getReplyCode()).thenReturn(226);
        when(client.listFiles())
                .thenAnswer(
                        call -> {
                            try (Stream<Path> stream = Files.list(current.get())) {
                                return stream.map(
                                                p -> {
                                                    FTPFile f = new FTPFile();
                                                    f.setName(p.getFileName().toString());
                                                    f.setType(
                                                            Files.isSymbolicLink(p)
                                                                    ? FTPFile.SYMBOLIC_LINK_TYPE
                                                                    : Files.isDirectory(p)
                                                                            ? FTPFile.DIRECTORY_TYPE
                                                                            : FTPFile.FILE_TYPE);
                                                    try {
                                                        f.setSize(Files.size(p));
                                                    } catch (IOException e) {
                                                        throw new UncheckedIOException(e);
                                                    }
                                                    return f;
                                                })
                                        .toArray(FTPFile[]::new);
                            }
                        });
        when(client.makeDirectory(anyString()))
                .thenAnswer(
                        call -> {
                            Files.createDirectory(
                                    current.get().resolve((String) call.getArgument(0)));
                            return true;
                        });
        when(client.storeFileStream(anyString()))
                .thenAnswer(
                        call ->
                                Files.newOutputStream(
                                        current.get().resolve((String) call.getArgument(0)),
                                        StandardOpenOption.CREATE_NEW));
        when(client.retrieveFileStream(anyString()))
                .thenAnswer(
                        call ->
                                Files.newInputStream(
                                        current.get().resolve((String) call.getArgument(0))));
        when(client.completePendingCommand()).thenReturn(true);
        when(client.rename(anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            Files.move(
                                    current.get().resolve((String) call.getArgument(0)),
                                    current.get().resolve((String) call.getArgument(1)));
                            return true;
                        });
        when(client.deleteFile(anyString()))
                .thenAnswer(
                        call -> {
                            Files.delete(current.get().resolve((String) call.getArgument(0)));
                            return true;
                        });
        when(client.removeDirectory(anyString()))
                .thenAnswer(
                        call -> {
                            Files.delete(current.get().resolve((String) call.getArgument(0)));
                            return true;
                        });
        return client;
    }

    /** 样本包含中文、隐藏文件、空目录及零字节文件。 */
    private void populate(Path root) throws Exception {
        Files.createDirectories(root.resolve("子目录/空目录"));
        Files.writeString(root.resolve("子目录/说明.txt"), "hello 世界");
        Files.writeString(root.resolve(".hidden"), "secret");
        Files.createFile(root.resolve("empty.txt"));
    }

    /** 上传/下载保留完整目录内容，进度累计与校验总量正确。 */
    @Test
    void transfersCompleteDirectoryBothWays() throws Exception {
        Path local = Files.createDirectory(base.resolve("local")),
                remote = Files.createDirectory(base.resolve("remote"));
        populate(local.resolve("资料"));
        FTPClient client = ftp(remote);
        FtpConnectionService connections = mock(FtpConnectionService.class);
        when(connections.connect(any())).thenReturn(client);
        TransferService service = new TransferService(mock(FtpSessionService.class), connections);
        try {
            WorkspaceRequest up = new WorkspaceRequest();
            up.name = "资料";
            up.localPath = local.toString();
            up.remotePath = "/";
            up.direction = "upload";
            TransferJob uploaded = new TransferJob();
            service.transfer(uploaded, up, new ConnectionConfig());
            assertEquals("completed", uploaded.status, uploaded.error);
            Path uploadedRoot = remote.resolve(uploaded.destination.substring(1));
            assertTrue(Files.isDirectory(uploadedRoot.resolve("子目录/空目录")));
            assertEquals("secret", Files.readString(uploadedRoot.resolve(".hidden")));
            assertEquals(0, Files.size(uploadedRoot.resolve("empty.txt")));
            assertEquals(uploaded.size * 2, uploaded.verifyBytes);
            Path downloads = Files.createDirectory(base.resolve("downloads"));
            WorkspaceRequest down = new WorkspaceRequest();
            down.name = "资料";
            down.localPath = downloads.toString();
            down.remotePath = "/" + remote.relativize(uploadedRoot.getParent());
            down.direction = "download";
            TransferJob downloaded = new TransferJob();
            service.transfer(downloaded, down, new ConnectionConfig());
            assertEquals("completed", downloaded.status, downloaded.error);
            Path destination = Paths.get(downloaded.destination);
            assertEquals("hello 世界", Files.readString(destination.resolve("子目录/说明.txt")));
            assertTrue(Files.isDirectory(destination.resolve("子目录/空目录")));
            assertEquals("secret", Files.readString(destination.resolve(".hidden")));
            assertEquals(downloaded.size * 2, downloaded.verifyBytes);
        } finally {
            service.shutdown();
        }
    }

    /** 隐藏内容递归删除，目录外的兄弟文件不受影响。 */
    @Test
    void deletesLocalAndRemoteTrees() throws Exception {
        Path local = Files.createDirectory(base.resolve("local"));
        populate(local.resolve("tree"));
        Files.writeString(local.resolve("keep"), "safe");
        FolderService folders = new FolderService();
        folders.deleteLocal(local.resolve("tree"));
        assertFalse(Files.exists(local.resolve("tree")));
        assertEquals("safe", Files.readString(local.resolve("keep")));
        populate(local.resolve("tree"));
        folders.deleteRemote(ftp(local), "tree");
        assertFalse(Files.exists(local.resolve("tree")));
        assertEquals("safe", Files.readString(local.resolve("keep")));
    }

    /** 符号链接预检失败，不能部分删除目录或越界读取。 */
    @Test
    void refusesLinksBeforeDeletionOrTransfer() throws Exception {
        Path root = Files.createDirectory(base.resolve("root"));
        populate(root.resolve("tree"));
        Path outside = base.resolve("outside.txt");
        Files.writeString(outside, "keep");
        Files.createSymbolicLink(root.resolve("tree/link"), outside);
        FolderService folders = new FolderService();
        assertThrows(IOException.class, () -> folders.deleteLocal(root.resolve("tree")));
        assertTrue(Files.exists(root.resolve("tree/.hidden")));
        assertEquals("keep", Files.readString(outside));
        assertThrows(IOException.class, () -> folders.remoteManifest(ftp(root), "tree", () -> {}));
        FTPClient client = ftp(root);
        FtpConnectionService connections = mock(FtpConnectionService.class);
        when(connections.connect(any())).thenReturn(client);
        TransferService service = new TransferService(mock(FtpSessionService.class), connections);
        try {
            WorkspaceRequest request = new WorkspaceRequest();
            request.name = "tree";
            request.localPath = root.toString();
            request.remotePath = "/";
            request.direction = "upload";
            TransferJob job = new TransferJob();
            service.transfer(job, request, new ConnectionConfig());
            assertEquals("failed", job.status);
            verify(client, never()).makeDirectory(anyString());
        } finally {
            service.shutdown();
        }
    }

    /** 子文件损坏后不发布完整目录，源目录保持完整。 */
    @Test
    void corruptFolderUploadRemainsTemporary() throws Exception {
        Path local = Files.createDirectory(base.resolve("local")),
                remote = Files.createDirectory(base.resolve("remote"));
        populate(local.resolve("tree"));
        FTPClient client = ftp(remote);
        when(client.retrieveFileStream(anyString()))
                .thenReturn(new ByteArrayInputStream("bad".getBytes()));
        FtpConnectionService connections = mock(FtpConnectionService.class);
        when(connections.connect(any())).thenReturn(client);
        TransferService service = new TransferService(mock(FtpSessionService.class), connections);
        try {
            WorkspaceRequest request = new WorkspaceRequest();
            request.name = "tree";
            request.localPath = local.toString();
            request.remotePath = "/";
            request.direction = "upload";
            TransferJob job = new TransferJob();
            service.transfer(job, request, new ConnectionConfig());
            assertEquals("failed", job.status);
            verify(client, never()).rename(anyString(), anyString());
            assertTrue(Files.exists(local.resolve("tree/.hidden")));
        } finally {
            service.shutdown();
        }
    }
}
