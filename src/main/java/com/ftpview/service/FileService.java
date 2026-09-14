package com.ftpview.service;

import static com.ftpview.util.PathValidator.name;
import static com.ftpview.util.PathValidator.safe;

import com.ftpview.dto.FileEntry;
import com.ftpview.dto.WorkspaceRequest;
import com.ftpview.service.FtpSessionService.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Service;

@Service
public class FileService {
    private final FtpSessionService sessions;
    private final TransferService transfers;

    /** 注入会话与任务状态，目录操作不直接承担连接或传输实现。 */
    public FileService(FtpSessionService sessions, TransferService transfers) {
        this.sessions = sessions;
        this.transfers = transfers;
    }

    /** 自动打开用户主目录，默认排除隐藏文件及链接。 */
    public Map<String, Object> local(String directoryPath) throws Exception {
        Path directory =
                Paths.get(directoryPath.isBlank() ? System.getProperty("user.home") : directoryPath)
                        .toRealPath();
        List<FileEntry> list = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.collect(Collectors.toList())) {
                if (Files.isHidden(path) || Files.isSymbolicLink(path)) {
                    continue;
                }
                list.add(
                        new FileEntry(
                                path.getFileName().toString(),
                                Files.isDirectory(path) ? "folder" : "file",
                                Files.size(path),
                                Files.getLastModifiedTime(path).toInstant().toString()));
            }
        }
        return Map.of("path", directory.toString(), "files", list);
    }

    /** 同一浏览连接串行执行，避免多个 CWD/LIST 命令相互污染。 */
    public Map<String, Object> remote(WorkspaceRequest request) throws Exception {
        Session session = sessions.session(request.session);
        synchronized (session) {
            FTPClient ftpClient = session.client;
            if (!ftpClient.changeWorkingDirectory(safe(request.path))) {
                throw new IOException("目录不存在或无权限");
            }
            String current = ftpClient.printWorkingDirectory();
            List<FileEntry> list = new ArrayList<>();
            FTPFile[] entries = ftpClient.listFiles();
            if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())) {
                throw new IOException("读取目录失败");
            }
            for (FTPFile entry : entries) {
                if (entry.getName().equals(".") || entry.getName().equals("..")) {
                    continue;
                }
                boolean folder = entry.isDirectory();
                if (entry.isSymbolicLink() || entry.isUnknown()) {
                    folder = ftpClient.changeWorkingDirectory(safe(entry.getName()));
                    if (folder && !ftpClient.changeWorkingDirectory(current)) {
                        throw new IOException("无法恢复目录");
                    }
                }
                list.add(
                        new FileEntry(
                                entry.getName(),
                                folder ? "folder" : entry.isSymbolicLink() ? "link" : "file",
                                entry.getSize(),
                                entry.getTimestamp() == null
                                        ? null
                                        : entry.getTimestamp().toInstant().toString()));
            }
            return Map.of("path", current, "files", list);
        }
    }

    /** 仅删除明确选中的普通文件，拒绝递归删除。 */
    public void delete(WorkspaceRequest request) throws Exception {
        name(request.name);
        if (transfers.isTransferring()) {
            throw new IOException("请等待传输结束后删除");
        }
        if (request.session == null) {
            Path path = Paths.get(request.path).resolve(request.name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("仅支持普通文件");
            }
            Files.delete(path);
        } else {
            Session session = sessions.session(request.session);
            synchronized (session) {
                FTPClient ftpClient = session.client;
                if (!ftpClient.changeWorkingDirectory(safe(request.path))) {
                    throw new IOException("目录不可用");
                }
                FTPFile entry = ftpClient.mlistFile(request.name);
                if (entry == null) {
                    entry =
                            Arrays.stream(ftpClient.listFiles())
                                    .filter(exception -> exception.getName().equals(request.name))
                                    .findFirst()
                                    .orElse(null);
                }
                if (entry == null || !entry.isFile()) {
                    throw new IOException("仅支持普通文件");
                }
                if (!ftpClient.deleteFile(request.name)) {
                    throw new IOException("删除失败");
                }
            }
        }
    }
}
