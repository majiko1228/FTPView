package com.ftpview.service;

import static com.ftpview.util.PathValidator.name;
import static com.ftpview.util.PathValidator.safe;

import com.ftpview.dto.ConnectionConfig;
import com.ftpview.dto.TransferJob;
import com.ftpview.dto.WorkspaceRequest;
import com.ftpview.service.FtpSessionService.Session;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PreDestroy;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
    private final Map<String, TransferJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FtpSessionService sessions;
    private final FtpConnectionService connections;

    /** 传输使用独立连接，避免阻塞目录浏览会话。 */
    public TransferService(FtpSessionService sessions, FtpConnectionService connections) {
        this.sessions = sessions;
        this.connections = connections;
    }

    /** 判断是否仍有未结束任务，用于写操作保护。 */
    public boolean isTransferring() {
        return jobs.values().stream()
                .anyMatch(job -> !List.of("completed", "failed").contains(job.status));
    }

    /** 返回内存任务快照，供前端独立轮询进度。 */
    public Collection<TransferJob> progress() {
        return jobs.values();
    }

    /** 单任务传输避免同一客户端重复写入目标。 */
    public synchronized TransferJob submit(WorkspaceRequest request) throws Exception {
        name(request.name);
        Session session = sessions.session(request.session);
        if (!List.of("upload", "download").contains(request.direction)) {
            throw new IOException("无效方向");
        }
        long pending =
                jobs.values().stream()
                        .filter(job -> !List.of("completed", "failed").contains(job.status))
                        .count();
        if (pending >= 100) {
            throw new IOException("等待队列已满，请稍后重试");
        }
        if (jobs.size() >= 200) {
            jobs.entrySet()
                    .removeIf(
                            entry ->
                                    List.of("completed", "failed")
                                            .contains(entry.getValue().status));
        }
        TransferJob job = new TransferJob();
        job.status = "queued";
        job.name = request.name;
        job.direction = request.direction;
        jobs.put(job.id, job);
        worker.submit(() -> transfer(job, request, session.config));
        return job;
    }

    /** 计算 SHA-256，可同时记录传输字节；流由调用者关闭。 */
    private byte[] copy(InputStream in, OutputStream out, TransferJob job) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out != null) {
                out.write(buffer, 0, count);
            }
            hash.update(buffer, 0, count);
            if (job != null) {
                job.bytes += count;
            }
        }
        return hash.digest();
    }

    /** 回读远端文件校验内容，必须消费 FTP 完成响应。 */
    private byte[] remoteHash(FTPClient ftpClient, String path) throws Exception {
        InputStream in = ftpClient.retrieveFileStream(path);
        if (in == null) {
            throw new IOException("无法回读校验");
        }
        byte[] hash;
        try (InputStream stream = in) {
            hash = copy(stream, null, null);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("回读未完成");
        }
        return hash;
    }

    /** 临时写入与哈希校验成功后发布，不删除源文件，不覆盖同名目标。 */
    void transfer(TransferJob job, WorkspaceRequest request, ConnectionConfig config) {
        job.status = "connecting";
        FTPClient ftpClient = null;
        try {
            name(request.name);
            safe(request.name);
            ftpClient = connections.connect(config);
            if (!ftpClient.changeWorkingDirectory(safe(request.remotePath))) {
                throw new IOException("远端目录不可用");
            }
            Path local = Paths.get(request.localPath).resolve(request.name);
            job.status = "transferring";
            if (request.direction.equals("upload")) {
                upload(job, request, ftpClient, local);
            } else {
                download(job, request, ftpClient, local);
            }
            job.recoveryPath = "";
            job.status = "completed";
        } catch (Exception exception) {
            job.error =
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
            job.status = "failed";
        } finally {
            if (ftpClient != null) {
                connections.close(ftpClient);
            }
        }
    }

    /** 上传到独立临时目录，回读校验后发布正式文件。 */
    private void upload(TransferJob job, WorkspaceRequest request, FTPClient ftpClient, Path local)
            throws Exception {

        if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("仅支持普通文件");
        }
        job.size = Files.size(local);
        long modified = Files.getLastModifiedTime(local).toMillis();
        String directory = "FTPView-" + UUID.randomUUID(),
                temporary = directory + "/.part",
                destination = directory + "/" + request.name;
        if (!ftpClient.makeDirectory(directory)) {
            throw new IOException("无法创建独立上传目录");
        }
        job.recoveryPath = request.remotePath + "/" + temporary;
        job.destination = request.remotePath + "/" + destination;
        OutputStream out = ftpClient.storeFileStream(temporary);
        if (out == null) {
            throw new IOException("无法写入临时文件");
        }
        byte[] original;
        try (InputStream in = Files.newInputStream(local);
                OutputStream stream = out) {
            original = copy(in, stream, job);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("上传未完成");
        }
        job.status = "verifying";
        if (job.bytes != job.size || !Arrays.equals(original, remoteHash(ftpClient, temporary))) {
            throw new IOException("内容校验失败");
        }
        byte[] current;
        try (InputStream in = Files.newInputStream(local)) {
            current = copy(in, null, null);
        }
        if (Files.size(local) != job.size
                || Files.getLastModifiedTime(local).toMillis() != modified
                || !Arrays.equals(original, current)) {
            throw new IOException("源文件已变化");
        }
        if (Arrays.stream(ftpClient.listFiles(directory))
                .anyMatch(entry -> entry.getName().equals(request.name))) {
            throw new IOException("目标已存在");
        }
        if (!ftpClient.rename(temporary, destination)) {
            throw new IOException("发布文件失败");
        }
    }

    /** 下载到本机临时文件，校验并落盘后原子发布。 */
    private void download(
            TransferJob job, WorkspaceRequest request, FTPClient ftpClient, Path local)
            throws Exception {

        FTPFile source =
                Arrays.stream(ftpClient.listFiles())
                        .filter(entry -> entry.getName().equals(request.name))
                        .findFirst()
                        .orElseThrow(() -> new IOException("文件不存在"));
        if (!source.isFile()) {
            throw new IOException("仅支持普通文件");
        }
        job.size = source.getSize();
        if (Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("同名文件已存在，拒绝覆盖");
        }
        Path temporary = Files.createTempFile(local.getParent(), ".ftpview-", ".part");
        job.recoveryPath = temporary.toString();
        job.destination = local.toString();
        InputStream in = ftpClient.retrieveFileStream(request.name);
        if (in == null) {
            throw new IOException("下载失败");
        }
        byte[] original;
        try (InputStream stream = in;
                OutputStream out = Files.newOutputStream(temporary)) {
            original = copy(stream, out, job);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("下载未完成");
        }
        job.status = "verifying";
        byte[] disk;
        try (InputStream stored = Files.newInputStream(temporary)) {
            disk = copy(stored, null, null);
        }
        if (job.bytes != job.size
                || !Arrays.equals(original, disk)
                || !Arrays.equals(original, remoteHash(ftpClient, request.name))) {
            throw new IOException("内容校验失败");
        }
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.createLink(local, temporary);
        Files.delete(temporary);
    }

    /** 应用退出时停止任务执行器。 */
    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
