package com.ftpview.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ftpview.entity.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.net.ftp.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryCreationServiceTest {
    @TempDir Path directory;

    /** 校验文件名及扩展名，创建空文件且不覆盖已有内容。 */
    @Test
    void validatesFileNameAndPreservesExistingFile() throws Exception {
        EntryCreationService service =
                new EntryCreationService(mock(FtpSessionService.class), new FolderService());
        WorkspaceRequest request = request();
        for (String invalid :
                Arrays.asList(null, "", "  ", "readme", ".txt", "file.", "../a.txt", "file.tx t")) {
            request.name = invalid;
            assertThrows(IOException.class, () -> service.createFile(request));
        }
        request.name = "说明.txt";
        FileEntry entry = service.createFile(request);
        assertEquals("说明.txt", entry.name);
        assertEquals(0, Files.size(directory.resolve(entry.name)));
        Files.writeString(directory.resolve(entry.name), "keep");
        assertThrows(IOException.class, () -> service.createFile(request));
        assertEquals("keep", Files.readString(directory.resolve(entry.name)));
    }

    /** 默认名称避让文件和文件夹，重命名冲突后保留原文件夹。 */
    @Test
    void numbersFoldersAndRejectsRenameCollision() throws Exception {
        EntryCreationService service =
                new EntryCreationService(mock(FtpSessionService.class), new FolderService());
        WorkspaceRequest request = request();
        assertEquals("新建文件夹", service.createFolder(request).name);
        Files.createFile(directory.resolve("新建文件夹1"));
        assertEquals("新建文件夹2", service.createFolder(request).name);
        request.name = "新建文件夹2";
        request.newName = "新建文件夹";
        assertThrows(IOException.class, () -> service.renameFolder(request));
        assertTrue(Files.isDirectory(directory.resolve(request.name)));
        request.newName = "项目资料";
        assertEquals("项目资料", service.renameFolder(request).name);
        assertTrue(Files.isDirectory(directory.resolve("项目资料")));
        assertFalse(Files.exists(directory.resolve("新建文件夹2")));
        request.name = "项目资料";
        request.newName = "  ";
        assertThrows(IOException.class, () -> service.renameFolder(request));
    }

    /** 远端同名文件必须在写入前拒绝，自动编号使用服务器实际列表。 */
    @Test
    void remoteCreationChecksConflicts() throws Exception {
        FTPClient client = mock(FTPClient.class);
        when(client.changeWorkingDirectory("/")).thenReturn(true);
        when(client.getReplyCode()).thenReturn(226);
        FTPFile file = new FTPFile();
        file.setName("existing.txt");
        file.setType(FTPFile.FILE_TYPE);
        FTPFile folder = new FTPFile();
        folder.setName("新建文件夹");
        folder.setType(FTPFile.DIRECTORY_TYPE);
        when(client.listFiles()).thenReturn(new FTPFile[] {file, folder});
        when(client.makeDirectory("新建文件夹1")).thenReturn(true);
        FtpSessionService sessions = mock(FtpSessionService.class);
        when(sessions.session("test"))
                .thenReturn(new FtpSessionService.Session(new ConnectionConfig(), client));
        EntryCreationService service = new EntryCreationService(sessions, new FolderService());
        WorkspaceRequest request = request();
        request.session = "test";
        request.path = "/";
        request.name = "existing.txt";
        assertThrows(IOException.class, () -> service.createFile(request));
        verify(client, never()).storeFile(anyString(), any());
        assertEquals("新建文件夹1", service.createFolder(request).name);
        request.name = "new.txt";
        when(client.storeFile(anyString(), any())).thenReturn(true);
        when(client.rename(anyString(), eq("new.txt"))).thenReturn(true);
        assertEquals("new.txt", service.createFile(request).name);
        verify(client).rename(startsWith(".ftpview-create-"), eq("new.txt"));
    }

    /** 服务器列目录失败不能误判为目标不存在并继续写入。 */
    @Test
    void remoteListFailureStopsCreation() throws Exception {
        FTPClient client = mock(FTPClient.class);
        when(client.changeWorkingDirectory("/")).thenReturn(true);
        when(client.listFiles()).thenReturn(new FTPFile[0]);
        when(client.getReplyCode()).thenReturn(550);
        FtpSessionService sessions = mock(FtpSessionService.class);
        when(sessions.session("test"))
                .thenReturn(new FtpSessionService.Session(new ConnectionConfig(), client));
        EntryCreationService service = new EntryCreationService(sessions, new FolderService());
        WorkspaceRequest request = request();
        request.session = "test";
        request.path = "/";
        request.name = "new.txt";
        assertThrows(IOException.class, () -> service.createFile(request));
        verify(client, never()).storeFile(anyString(), any());
    }

    /** 测试仅操作 JUnit 临时目录。 */
    private WorkspaceRequest request() {
        WorkspaceRequest request = new WorkspaceRequest();
        request.path = directory.toString();
        return request;
    }
}
