package com.ftpview.api;

import com.ftpview.dto.ConnectionConfig;
import com.ftpview.dto.TransferJob;
import com.ftpview.dto.WorkspaceRequest;
import com.ftpview.service.FileService;
import com.ftpview.service.FtpSessionService;
import com.ftpview.service.TransferService;
import java.util.Collection;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final FtpSessionService sessions;
    private final FileService files;
    private final TransferService transfers;

    /** 控制层仅负责请求委派，业务逻辑由服务层处理。 */
    public WorkspaceController(
            FtpSessionService sessions, FileService files, TransferService transfers) {
        this.sessions = sessions;
        this.files = files;
        this.transfers = transfers;
    }

    /** 建立 FTP 浏览会话。 */
    @PostMapping("/connect")
    public Map<String, String> login(@RequestBody ConnectionConfig config) throws Exception {
        return sessions.login(config);
    }

    /** 断开指定浏览会话。 */
    @PostMapping("/disconnect")
    public void disconnect(@RequestBody WorkspaceRequest request) {
        sessions.disconnect(request);
    }

    /** 获取本机目录列表。 */
    @GetMapping("/local/list")
    public Map<String, Object> local(@RequestParam(defaultValue = "") String path)
            throws Exception {
        return files.local(path);
    }

    /** 获取远端目录列表。 */
    @PostMapping("/remote/list")
    public Map<String, Object> remote(@RequestBody WorkspaceRequest request) throws Exception {
        return files.remote(request);
    }

    /** 删除选中的普通文件。 */
    @PostMapping("/delete")
    public void delete(@RequestBody WorkspaceRequest request) throws Exception {
        files.delete(request);
    }

    /** 查询传输任务。 */
    @GetMapping("/transfers")
    public Collection<TransferJob> progress() {
        return transfers.progress();
    }

    /** 创建文件传输任务。 */
    @PostMapping("/transfers")
    public TransferJob submit(@RequestBody WorkspaceRequest request) throws Exception {
        return transfers.submit(request);
    }
}
