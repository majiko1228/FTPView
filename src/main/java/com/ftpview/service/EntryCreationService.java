package com.ftpview.service;

import static com.ftpview.util.PathValidator.name;
import static com.ftpview.util.PathValidator.safe;

import com.ftpview.entity.FileEntry;
import com.ftpview.entity.WorkspaceRequest;
import com.ftpview.service.FtpSessionService.Session;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.net.ftp.*;
import org.springframework.stereotype.Service;

@Service
public class EntryCreationService {
    private final FtpSessionService sessions;
    private final FolderService folders;

    /** 目录创建与重命名复用同一浏览会话，避免 CWD 命令交错。 */
    public EntryCreationService(FtpSessionService sessions, FolderService folders) {
        this.sessions = sessions;
        this.folders = folders;
    }

    /** 文件名要求有非空主体和扩展名，前后端均校验。 */
    private String validate(String value, boolean file) throws IOException {
        if (value == null) {
            throw new IOException("名称不能为空");
        }
        String normalized = value.trim();
        name(normalized);
        if (normalized.matches(".*[<>:\"|?*\\p{Cntrl}].*")) {
            throw new IOException("名称包含不支持的字符");
        }
        int dot = normalized.lastIndexOf('.');
        if (file
                && (dot <= 0
                        || dot == normalized.length() - 1
                        || !normalized.substring(dot + 1).matches("[\\p{L}\\p{N}_-]+"))) {
            throw new IOException("请输入带文件类型的名称，例如：说明.txt");
        }
        return normalized;
    }

    /** 切换到明确目录，校验编码可表示名称，避免文件名被替换成问号。 */
    private FTPClient directory(Session session, String path, String filename) throws IOException {
        if (!java.nio.charset.Charset.forName(session.config.encoding)
                .newEncoder()
                .canEncode(filename)) {
            throw new IOException("当前编码无法表示此名称，请切换编码");
        }
        if (!session.client.changeWorkingDirectory(safe(path))) {
            throw new IOException("当前目录不可用");
        }
        return session.client;
    }

    /** 返回实际创建的条目，用于前端即时定位和进入重命名。 */
    private FileEntry entry(String name, boolean directory) {
        return new FileEntry(
                name, directory ? "folder" : "file", 0, java.time.Instant.now().toString());
    }

    /** 创建空文件，已有同名文件或目录时拒绝覆盖。 */
    public FileEntry createFile(WorkspaceRequest request) throws Exception {
        String filename = validate(request.name, true);
        if (request.session == null) {
            Files.createFile(Paths.get(request.path).toRealPath().resolve(filename));
        } else {
            Session session = sessions.session(request.session);
            synchronized (session) {
                FTPClient client = directory(session, request.path, filename);
                if (exists(client, filename)) {
                    throw new IOException("同名文件或目录已存在");
                }
                String temporary = ".ftpview-create-" + UUID.randomUUID();
                try {
                    if (!client.storeFile(temporary, new ByteArrayInputStream(new byte[0]))) {
                        throw new IOException("新建文件失败");
                    }
                    if (exists(client, filename)) {
                        throw new IOException("同名文件已出现，已取消保存");
                    }
                    if (!client.rename(temporary, filename)) {
                        throw new IOException("无法保存新文件");
                    }
                } finally {
                    // 仅清理本操作生成的随机临时文件，不删除正式目标。
                    try {
                        client.deleteFile(temporary);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return entry(filename, false);
    }

    /** 自动避让全部已有条目，创建“新建文件夹”“新建文件夹1”等名称。 */
    public FileEntry createFolder(WorkspaceRequest request) throws Exception {
        if (request.session == null) {
            Path parent = Paths.get(request.path).toRealPath();
            for (int index = 0; index < 10000; index++) {
                String filename = "新建文件夹" + (index == 0 ? "" : index);
                try {
                    Files.createDirectory(parent.resolve(filename));
                    return entry(filename, true);
                } catch (FileAlreadyExistsException conflict) {
                    /* 重试下一个编号，不覆盖冲突条目。 */
                }
            }
        } else {
            Session session = sessions.session(request.session);
            synchronized (session) {
                FTPClient client = directory(session, request.path, "新建文件夹");
                Set<String> names = new HashSet<>();
                for (FTPFile item : folders.checkedList(client)) {
                    names.add(item.getName());
                }
                for (int index = 0; index < 10000; index++) {
                    String filename = "新建文件夹" + (index == 0 ? "" : index);
                    if (names.contains(filename)) {
                        continue;
                    }
                    if (client.makeDirectory(filename)) {
                        return entry(filename, true);
                    }
                    if (exists(client, filename)) {
                        continue;
                    }
                    throw new IOException("新建文件夹失败，请检查目录写入权限");
                }
            }
        }
        throw new IOException("同名目录过多，请先整理当前目录");
    }

    /** 行内重命名只作用于当前目录的文件夹，目标同名时保留原目录。 */
    public FileEntry renameFolder(WorkspaceRequest request) throws Exception {
        String original = validate(request.name, false);
        String target = validate(request.newName, false);
        if (request.session == null) {
            Path parent = Paths.get(request.path).toRealPath();
            Path source = parent.resolve(original);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("原文件夹不存在");
            }
            if (!original.equals(target)) {
                if (Files.exists(parent.resolve(target), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("目标名称已存在");
                }
                Files.move(source, parent.resolve(target));
            }
        } else {
            Session session = sessions.session(request.session);
            synchronized (session) {
                FTPClient client = directory(session, request.path, target);
                FTPFile source =
                        Arrays.stream(folders.checkedList(client))
                                .filter(item -> item.getName().equals(original))
                                .findFirst()
                                .orElse(null);
                if (source == null || !source.isDirectory() || source.isSymbolicLink()) {
                    throw new IOException("原文件夹不存在或为链接");
                }
                if (!original.equals(target)) {
                    if (exists(client, target)) {
                        throw new IOException("目标名称已存在");
                    }
                    if (!client.rename(original, target)) {
                        throw new IOException("重命名失败，原文件夹已保留");
                    }
                }
            }
        }
        return entry(target, true);
    }

    /** 使用完整列表比较名称，不把 FTP 通配符或失败响应当作不存在。 */
    private boolean exists(FTPClient client, String filename) throws IOException {
        return Arrays.stream(folders.checkedList(client))
                .anyMatch(item -> item.getName().equals(filename));
    }
}
