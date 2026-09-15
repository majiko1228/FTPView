package com.ftpview.service;

import static com.ftpview.util.PathValidator.name;
import static com.ftpview.util.PathValidator.safe;

import com.ftpview.entity.ConnectionConfig;
import com.ftpview.entity.TransferJob;
import com.ftpview.entity.WorkspaceRequest;
import com.ftpview.service.FtpSessionService.Session;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PreDestroy;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.springframework.stereotype.Service;

@Service
public class TransferService {
    private final Map<String, TransferJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private final Map<String, Control> controls = new ConcurrentHashMap<>();
    private final Map<String, String> activeKeys = new ConcurrentHashMap<>();

    private static class Control {
        volatile boolean cancelled;
        volatile FTPClient client;
        final List<Closeable> streams = new CopyOnWriteArrayList<>();
    }

    /** 包含校验与取消收尾的任务均占用文件锁。 */
    private boolean terminal(TransferJob job) {
        return List.of("completed", "failed", "cancelled").contains(job.status);
    }

    private final FolderService folders;
    private final FtpSessionService sessions;
    private final FtpConnectionService connections;

    /** 传输使用独立连接，避免阻塞目录浏览会话。 */
    public TransferService(FtpSessionService sessions, FtpConnectionService connections) {
        this(sessions, connections, new FolderService());
    }

    /** 注入目录清单服务，文件夹任务包含递归预检和完整性校验。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TransferService(
            FtpSessionService sessions, FtpConnectionService connections, FolderService folders) {
        this(sessions, connections, Executors.newFixedThreadPool(2), folders);
    }

    /** 测试可以注入单线程执行器，生产最多并发两个独立文件。 */
    TransferService(
            FtpSessionService sessions, FtpConnectionService connections, ExecutorService worker) {
        this(sessions, connections, worker, new FolderService());
    }

    /** 测试和生产共用任务资源初始化。 */
    TransferService(
            FtpSessionService sessions,
            FtpConnectionService connections,
            ExecutorService worker,
            FolderService folders) {
        this.folders = folders;
        this.worker = worker;
        this.sessions = sessions;
        this.connections = connections;
    }

    /** 判断是否仍有未结束任务，用于写操作保护。 */
    public boolean isTransferring() {
        return jobs.values().stream().anyMatch(job -> !terminal(job));
    }

    /** 返回内存任务快照，供前端独立轮询进度。 */
    public Collection<TransferJob> progress() {
        return new ArrayList<>(jobs.values());
    }

    /** 单任务传输避免同一客户端重复写入目标。 */
    public synchronized TransferJob submit(WorkspaceRequest request) throws Exception {
        name(request.name);
        Session session = sessions.session(request.session);
        if (!List.of("upload", "download").contains(request.direction)) {
            throw new IOException("无效方向");
        }
        String key = taskKey(request, session.config);
        String existing = activeKeys.get(key);
        if (existing != null) {
            throw new IOException("该文件已有未完成任务，请等待结束或取消后重试");
        }
        long pending = jobs.values().stream().filter(job -> !terminal(job)).count();
        if (pending >= 100) {
            throw new IOException("等待队列已满，请稍后重试");
        }
        if (jobs.size() >= 200) {
            jobs.entrySet().removeIf(entry -> terminal(entry.getValue()));
        }
        TransferJob job = new TransferJob();
        job.status = "queued";
        job.name = request.name;
        job.direction = request.direction;
        jobs.put(job.id, job);
        activeKeys.put(key, job.id);
        controls.put(job.id, new Control());
        worker.submit(
                () -> {
                    try {
                        if (!"cancelled".equals(job.status)) {
                            transfer(job, request, session.config);
                        }
                    } finally {
                        activeKeys.remove(key, job.id);
                        controls.remove(job.id);
                    }
                });
        return job;
    }

    /** 同一服务器、账号、方向和源/目标路径的任务只接受一次，不依赖会话 ID。 */
    private String taskKey(WorkspaceRequest request, ConnectionConfig config) throws IOException {
        safe(request.remotePath);
        Path localDirectory = Paths.get(request.localPath).toRealPath();
        String remote = Paths.get(request.remotePath).normalize().toString();
        return String.join(
                "\u0000",
                config.host.toLowerCase(java.util.Locale.ROOT),
                String.valueOf(config.port),
                config.user,
                config.protocol,
                request.direction,
                localDirectory.resolve(request.name).toString(),
                remote,
                request.name);
    }

    /** 取消排队任务，或关闭运行任务的数据流；发布阶段不可打断。 */
    public TransferJob cancel(String id) throws IOException {
        TransferJob job = jobs.get(id);
        if (job == null) {
            throw new IOException("任务不存在");
        }
        synchronized (job) {
            if (terminal(job)) {
                return job;
            }
            if ("publishing".equals(job.status)) {
                throw new IOException("文件已校验完毕，正在发布，请等待完成");
            }
            Control control = controls.get(id);
            if (control == null) {
                throw new IOException("任务正在结束，请稍后刷新");
            }
            control.cancelled = true;
            boolean queued = "queued".equals(job.status);
            job.status = queued ? "cancelled" : "cancelling";
            job.phase = queued ? "已取消" : "正在关闭连接";
            job.bytesPerSecond = 0;
            if (queued) {
                activeKeys.entrySet().removeIf(entry -> entry.getValue().equals(id));
            }
            for (Closeable stream : control.streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            if (control.client != null) {
                connections.close(control.client);
            }
            return job;
        }
    }

    /** 在每次读写及阶段切换前确认取消请求，防止取消后继续发布。 */
    private void checkCancelled(TransferJob job) {
        Control control = controls.get(job.id);
        if (control != null && control.cancelled) {
            throw new CancellationException("任务已取消，源文件未删除");
        }
    }

    /** 对取消操作暴露当前数据流，连接中断无需等待完整文件读取。 */
    private void register(TransferJob job, Closeable stream) throws IOException {
        Control control = controls.get(job.id);
        if (control != null && stream != null) {
            control.streams.add(stream);
            if (control.cancelled) {
                stream.close();
                checkCancelled(job);
            }
        }
    }

    /** 仅在全部校验通过后进入不可取消的短暂发布阶段。 */
    private void publishing(TransferJob job) {
        synchronized (job) {
            checkCancelled(job);
            job.status = "publishing";
            job.phase = "校验通过，正在保存";
            job.bytesPerSecond = 0;
        }
    }

    /** 计算 SHA-256，可同时记录传输字节；流由调用者关闭。 */
    private byte[] copy(InputStream in, OutputStream out, TransferJob job) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[262144];
        register(job, in);
        register(job, out);
        try {
            long lastTime = System.nanoTime();
            long windowBytes = 0;
            job.bytesPerSecond = 0;
            int count;
            while (true) {
                checkCancelled(job);
                count = in.read(buffer);
                if (count == -1) {
                    break;
                }
                checkCancelled(job);
                if (out != null) {
                    out.write(buffer, 0, count);
                }
                hash.update(buffer, 0, count);
                if ("transferring".equals(job.status)) {
                    job.bytes += count;
                } else {
                    job.verifyBytes += count;
                }
                windowBytes += count;
                long now = System.nanoTime();
                if (now - lastTime >= 250_000_000L) {
                    job.bytesPerSecond = (long) (windowBytes * 1_000_000_000.0 / (now - lastTime));
                    lastTime = now;
                    windowBytes = 0;
                }
                job.updatedAt = System.currentTimeMillis();
            }
            checkCancelled(job);
            return hash.digest();
        } finally {
            Control control = controls.get(job.id);
            if (control != null) {
                control.streams.remove(in);
                if (out != null) {
                    control.streams.remove(out);
                }
            }
        }
    }

    /** 回读远端文件校验内容，必须消费 FTP 完成响应。 */
    private byte[] remoteHash(FTPClient ftpClient, String path, TransferJob job) throws Exception {
        InputStream in = ftpClient.retrieveFileStream(path);
        if (in == null) {
            throw new IOException("无法回读校验");
        }
        byte[] hash;
        try (InputStream stream = in) {
            hash = copy(stream, null, job);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("回读未完成");
        }
        return hash;
    }

    /** 临时写入与哈希校验成功后发布，不删除源文件，不覆盖同名目标。 */
    void transfer(TransferJob job, WorkspaceRequest request, ConnectionConfig config) {
        FTPClient ftpClient = null;
        try {
            synchronized (job) {
                checkCancelled(job);
                job.status = "connecting";
                job.phase = "建立 FTP 连接";
            }
            name(request.name);
            safe(request.name);
            ftpClient = connections.connect(config);
            Control control = controls.get(job.id);
            if (control != null) {
                control.client = ftpClient;
            }
            checkCancelled(job);
            if (!ftpClient.changeWorkingDirectory(safe(request.remotePath))) {
                throw new IOException("远端目录不可用");
            }
            Path local = Paths.get(request.localPath).resolve(request.name);
            job.status = "transferring";
            job.phase = "传输文件";
            if (request.direction.equals("upload")) {
                if (Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)) {
                    uploadFolder(job, request, ftpClient, local);
                } else {
                    upload(job, request, ftpClient, local);
                }
            } else {
                FTPFile source =
                        Arrays.stream(ftpClient.listFiles())
                                .filter(item -> item.getName().equals(request.name))
                                .findFirst()
                                .orElseThrow(() -> new IOException("文件不存在"));
                if (source.isDirectory() && !source.isSymbolicLink()) {
                    downloadFolder(job, request, ftpClient, local);
                } else {
                    download(job, request, ftpClient, local);
                }
            }
            job.recoveryPath = "";
            job.status = "completed";
        } catch (Exception exception) {
            job.error =
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
            Control control = controls.get(job.id);
            job.status = control != null && control.cancelled ? "cancelled" : "failed";
            if ("cancelled".equals(job.status)) {
                job.error = "已取消，源文件保留，临时文件可能保留";
            }
        } finally {
            job.bytesPerSecond = 0;
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
        job.recoveryPath = request.remotePath.replaceAll("/+$", "") + "/" + temporary;
        job.destination = request.remotePath.replaceAll("/+$", "") + "/" + destination;
        OutputStream out = ftpClient.storeFileStream(temporary);
        if (out == null) {
            throw new IOException("无法写入临时文件");
        }
        byte[] original;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(local), 262144);
                OutputStream stream = new BufferedOutputStream(out, 262144)) {
            original = copy(in, stream, job);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("上传未完成");
        }
        checkCancelled(job);
        job.status = "verifying";
        job.verifyTotal = job.size * 2;
        job.bytesPerSecond = 0;
        job.phase = "回读远端 SHA-256 校验";
        if (job.bytes != job.size
                || !Arrays.equals(original, remoteHash(ftpClient, temporary, job))) {
            throw new IOException("内容校验失败");
        }
        job.phase = "检查本机源文件变化";
        byte[] current;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(local), 262144)) {
            current = copy(in, null, job);
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
        publishing(job);
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
                OutputStream out =
                        new BufferedOutputStream(Files.newOutputStream(temporary), 262144)) {
            original = copy(stream, out, job);
        }
        if (!ftpClient.completePendingCommand()) {
            throw new IOException("下载未完成");
        }
        checkCancelled(job);
        job.status = "verifying";
        job.verifyTotal = job.size * 2;
        job.bytesPerSecond = 0;
        job.phase = "校验本机临时文件";
        byte[] disk;
        try (InputStream stored = Files.newInputStream(temporary)) {
            disk = copy(stored, null, job);
        }
        job.phase = "回读远端 SHA-256 校验";
        if (job.bytes != job.size
                || !Arrays.equals(original, disk)
                || !Arrays.equals(original, remoteHash(ftpClient, request.name, job))) {
            throw new IOException("内容校验失败");
        }
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        publishing(job);
        Files.createLink(local, temporary);
        Files.delete(temporary);
    }

    /** 目录上传聚合总大小，保留隐藏文件和空目录，整个目录校验后再发布。 */
    private void uploadFolder(
            TransferJob job, WorkspaceRequest request, FTPClient client, Path local)
            throws Exception {
        job.phase = "扫描目录内容";
        List<FolderService.Item> manifest = folders.localManifest(local, () -> checkCancelled(job));
        job.size =
                manifest.stream()
                        .filter(item -> !item.directory)
                        .mapToLong(item -> item.size)
                        .sum();
        job.verifyTotal = job.size * 2;
        String container = "FTPView-" + UUID.randomUUID();
        if (!client.makeDirectory(container)) {
            throw new IOException("无法创建上传目录");
        }
        String temporary = container + "/.part";
        if (!client.makeDirectory(temporary)) {
            throw new IOException("无法创建临时目录");
        }
        job.destination =
                request.remotePath.replaceAll("/+$", "") + "/" + container + "/" + request.name;
        job.recoveryPath = request.remotePath.replaceAll("/+$", "") + "/" + temporary;
        Map<String, byte[]> hashes = new java.util.HashMap<>();
        for (FolderService.Item item : manifest) {
            checkCancelled(job);
            String destination = temporary + "/" + item.relative;
            Path source = local.resolve(item.relative);
            if (item.directory) {
                if (!client.makeDirectory(destination)) {
                    throw new IOException("创建子目录失败：" + item.relative);
                }
                continue;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("源文件类型已变化：" + item.relative);
            }
            job.status = "transferring";
            job.phase = "上传：" + item.relative;
            long before = job.bytes;
            OutputStream target = client.storeFileStream(destination);
            if (target == null) {
                throw new IOException("无法上传：" + item.relative);
            }
            byte[] hash;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(source), 262144);
                    OutputStream out = new BufferedOutputStream(target, 262144)) {
                hash = copy(in, out, job);
            }
            if (!client.completePendingCommand() || job.bytes - before != item.size) {
                throw new IOException("文件传输不完整：" + item.relative);
            }
            job.status = "verifying";
            job.phase = "回读校验：" + item.relative;
            if (!Arrays.equals(hash, remoteHash(client, destination, job))) {
                throw new IOException("内容校验失败：" + item.relative);
            }
            hashes.put(item.relative, hash);
        }
        job.status = "verifying";
        job.phase = "核对源目录清单";
        if (!manifest.equals(folders.localManifest(local, () -> checkCancelled(job)))) {
            throw new IOException("源目录在传输期间发生变化");
        }
        for (FolderService.Item item : manifest) {
            if (item.directory) {
                continue;
            }
            job.phase = "核对本机源文件：" + item.relative;
            try (InputStream in = Files.newInputStream(local.resolve(item.relative))) {
                if (!Arrays.equals(hashes.get(item.relative), copy(in, null, job))) {
                    throw new IOException("源文件已变化：" + item.relative);
                }
            }
        }
        publishing(job);
        if (!client.rename(temporary, container + "/" + request.name)) {
            throw new IOException("发布目录失败");
        }
    }

    /** 目录下载写入独立容器，保留目录结构；逐文件校验及清单核对通过后发布。 */
    private void downloadFolder(
            TransferJob job, WorkspaceRequest request, FTPClient client, Path local)
            throws Exception {
        if (Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("本机已有同名目录或文件，拒绝覆盖");
        }
        job.phase = "扫描远端目录";
        List<FolderService.Item> manifest =
                folders.remoteManifest(client, request.name, () -> checkCancelled(job));
        job.size =
                manifest.stream()
                        .filter(item -> !item.directory)
                        .mapToLong(item -> item.size)
                        .sum();
        job.verifyTotal = job.size * 2;
        Path container = Files.createTempDirectory(local.getParent(), "FTPView-");
        Path temporary = Files.createDirectory(container.resolve(".part"));
        job.destination = container.resolve(request.name).toString();
        job.recoveryPath = temporary.toString();
        Map<String, byte[]> hashes = new java.util.HashMap<>();
        for (FolderService.Item item : manifest) {
            checkCancelled(job);
            Path target = temporary.resolve(item.relative);
            if (item.directory) {
                Files.createDirectory(target);
                continue;
            }
            job.status = "transferring";
            job.phase = "下载：" + item.relative;
            long before = job.bytes;
            InputStream source = client.retrieveFileStream(request.name + "/" + item.relative);
            if (source == null) {
                throw new IOException("无法下载：" + item.relative);
            }
            byte[] hash;
            try (InputStream in = source;
                    OutputStream out =
                            new BufferedOutputStream(
                                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW),
                                    262144)) {
                hash = copy(in, out, job);
            }
            if (!client.completePendingCommand() || job.bytes - before != item.size) {
                throw new IOException("下载不完整：" + item.relative);
            }
            job.status = "verifying";
            job.phase = "校验本机内容：" + item.relative;
            try (InputStream in = Files.newInputStream(target)) {
                if (!Arrays.equals(hash, copy(in, null, job))) {
                    throw new IOException("本机内容校验失败：" + item.relative);
                }
            }
            try (java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(target, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            hashes.put(item.relative, hash);
        }
        job.status = "verifying";
        job.phase = "核对远端目录清单";
        if (!manifest.equals(
                folders.remoteManifest(client, request.name, () -> checkCancelled(job)))) {
            throw new IOException("远端目录在传输期间发生变化");
        }
        for (FolderService.Item item : manifest) {
            if (item.directory) {
                continue;
            }
            job.phase = "回读远端文件：" + item.relative;
            if (!Arrays.equals(
                    hashes.get(item.relative),
                    remoteHash(client, request.name + "/" + item.relative, job))) {
                throw new IOException("远端内容已变化：" + item.relative);
            }
        }
        publishing(job);
        Files.move(temporary, container.resolve(request.name));
    }

    /** 应用退出时停止任务执行器。 */
    @PreDestroy
    public void shutdown() {
        for (String id : jobs.keySet()) {
            try {
                cancel(id);
            } catch (IOException ignored) {
            }
        }
        worker.shutdownNow();
    }
}
