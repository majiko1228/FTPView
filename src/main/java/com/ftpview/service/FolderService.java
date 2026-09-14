package com.ftpview.service;

import static com.ftpview.util.PathValidator.name;
import static com.ftpview.util.PathValidator.safe;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.apache.commons.net.ftp.*;
import org.springframework.stereotype.Service;

@Service
public class FolderService {
    public static class Item {
        final String relative;
        final boolean directory;
        final long size;
        final long modified;

        /** 清单保留相对路径和属性，用于完整性核对及空目录重建。 */
        Item(String relative, boolean directory, long size, long modified) {
            this.relative = relative;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }

        /** 目录时间会随文件变动而改变，因此一并比较。 */
        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Item)) return false;
            Item other = (Item) value;
            return relative.equals(other.relative)
                    && directory == other.directory
                    && size == other.size
                    && modified == other.modified;
        }

        /** 与清单相等性保持一致。 */
        @Override
        public int hashCode() {
            return Objects.hash(relative, directory, size, modified);
        }
    }

    /** 不跟随符号链接，隐藏文件、空目录也包含在递归清单中。 */
    public List<Item> localManifest(Path root, Runnable checkpoint) throws IOException {
        List<Item> items = new ArrayList<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    /** 逐目录检查取消请求，避免深层扫描长时间无响应。 */
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attrs) throws IOException {
                        checkpoint.run();
                        if (!directory.equals(root)) add(directory, attrs);
                        return FileVisitResult.CONTINUE;
                    }

                    /** 文件类型不明确时直接失败，不静默漏传链接或设备文件。 */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        checkpoint.run();
                        if (!attrs.isRegularFile())
                            throw new IOException("目录包含链接或特殊文件，操作已拒绝：" + file);
                        add(file, attrs);
                        return FileVisitResult.CONTINUE;
                    }

                    /** 校验路径组成部分，保证远端可安全重建同一目录结构。 */
                    private void add(Path path, BasicFileAttributes attrs) throws IOException {
                        Path relative = root.relativize(path);
                        for (Path segment : relative) name(segment.toString());
                        items.add(
                                new Item(
                                        relative.toString().replace('\\', '/'),
                                        attrs.isDirectory(),
                                        attrs.isDirectory() ? 0 : attrs.size(),
                                        attrs.lastModifiedTime().toMillis()));
                    }
                });
        items.sort(Comparator.comparing(item -> item.relative));
        return items;
    }

    /** 使用 CWD 到实际目录后 LIST，避免目录名称被解释为通配符。 */
    public List<Item> remoteManifest(FTPClient client, String root, Runnable checkpoint)
            throws IOException {
        String original = client.printWorkingDirectory();
        List<Item> items = new ArrayList<>();
        try {
            scan(client, safe(root), "", items, new HashSet<>(), checkpoint, 0);
        } finally {
            if (!client.changeWorkingDirectory(original)) throw new IOException("无法恢复远端工作目录");
        }
        items.sort(Comparator.comparing(item -> item.relative));
        return items;
    }

    /** 拒绝目录循环、重复条目及不确定类型，任何列目录错误都会阻止操作。 */
    private void scan(
            FTPClient client,
            String target,
            String relative,
            List<Item> items,
            Set<String> visited,
            Runnable checkpoint,
            int depth)
            throws IOException {
        checkpoint.run();
        if (depth > 128) throw new IOException("目录嵌套超过 128 层，操作已停止");
        if (!client.changeWorkingDirectory(target)) throw new IOException("无法进入目录：" + target);
        String current = client.printWorkingDirectory();
        if (current == null || !visited.add(current)) throw new IOException("发现远端目录循环或路径不可识别");
        FTPFile[] entries = checkedList(client);
        Set<String> names = new HashSet<>();
        for (FTPFile entry : entries) {
            checkpoint.run();
            String n = entry.getName();
            if (n.equals(".") || n.equals("..")) continue;
            name(n);
            if (!names.add(n)) throw new IOException("目录返回重复文件名：" + n);
            if (entry.isSymbolicLink() || (!entry.isDirectory() && !entry.isFile()))
                throw new IOException("目录包含链接或未知类型：" + n);
            String child = relative.isEmpty() ? n : relative + "/" + n;
            items.add(
                    new Item(
                            child,
                            entry.isDirectory(),
                            entry.isDirectory() ? 0 : entry.getSize(),
                            entry.getTimestamp() == null
                                    ? 0
                                    : entry.getTimestamp().getTimeInMillis()));
            if (entry.isDirectory()) {
                scan(client, n, child, items, visited, checkpoint, depth + 1);
                if (!client.changeWorkingDirectory(current)) throw new IOException("无法恢复父目录");
            }
        }
    }

    /** FTP 返回失败时不能把空数组解释成空目录。 */
    public FTPFile[] checkedList(FTPClient client) throws IOException {
        FTPFile[] entries = client.listFiles();
        if (!FTPReply.isPositiveCompletion(client.getReplyCode()))
            throw new IOException("FTP 目录读取失败：" + client.getReplyString());
        return entries;
    }

    /** 删除前完整预检，之后按子项到父目录顺序删除，不跟随链接。 */
    public void deleteLocal(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("不允许递归删除符号链接");
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target);
            return;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("文件或目录不存在");
        List<Item> items = localManifest(target, () -> {});
        items.sort(
                Comparator.comparingInt((Item item) -> item.relative.split("/").length).reversed());
        for (Item item : items) {
            Path child = target.resolve(item.relative);
            if (Files.isSymbolicLink(child)) throw new IOException("目录内容已变化，请刷新后重试");
            Files.delete(child);
        }
        Files.delete(target);
    }

    /** 仅删除用户明确选定的目录树，部分失败时报告失败路径。 */
    public void deleteRemote(FTPClient client, String target) throws IOException {
        name(target);
        FTPFile entry =
                Arrays.stream(checkedList(client))
                        .filter(item -> item.getName().equals(target))
                        .findFirst()
                        .orElseThrow(() -> new IOException("文件不存在"));
        if (entry.isFile()) {
            if (!client.deleteFile(target)) throw new IOException("删除失败：" + target);
            return;
        }
        if (!entry.isDirectory() || entry.isSymbolicLink()) throw new IOException("不允许删除链接或未知类型");
        List<Item> items = remoteManifest(client, target, () -> {});
        items.sort(
                Comparator.comparingInt((Item item) -> item.relative.split("/").length).reversed());
        for (Item item : items) {
            String path = target + "/" + item.relative;
            boolean ok = item.directory ? client.removeDirectory(path) : client.deleteFile(path);
            if (!ok) throw new IOException("部分删除失败，请刷新检查：" + path);
        }
        if (!client.removeDirectory(target)) throw new IOException("目录删除失败：" + target);
    }
}
