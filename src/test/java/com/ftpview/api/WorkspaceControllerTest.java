package com.ftpview.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ftpview.service.FileService;
import com.ftpview.service.FtpSessionService;
import com.ftpview.service.TransferService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkspaceController.class)
class WorkspaceControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private FileService files;
    @MockBean private FtpSessionService sessions;
    @MockBean private TransferService transfers;

    /** 验证控制层将请求交给服务层，并保持目录 JSON 响应格式。 */
    @Test
    void delegatesLocalListing() throws Exception {
        when(files.local("/tmp")).thenReturn(Map.of("path", "/tmp", "files", List.of()));
        mvc.perform(get("/api/local/list").param("path", "/tmp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data.path").value("/tmp"))
                .andExpect(jsonPath("$.data.files").isArray());
        verify(files).local("/tmp");
    }

    /** 验证抽离异常处理后，前端仍收到 code/data/msg 字段和失败状态码。 */
    @Test
    void preservesErrorContract() throws Exception {
        when(files.local("/missing")).thenThrow(new IOException("目录不存在"));
        mvc.perform(get("/api/local/list").param("path", "/missing"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("目录不存在"));
    }

    /** 无响应体的删除接口也必须返回统一结构，便于批量客户端确认结果。 */
    @Test
    void wrapsEmptyDeleteResponse() throws Exception {
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/delete")
                                .contentType("application/json")
                                .content("{\"path\":\"/tmp\",\"name\":\"test.txt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("操作成功"));
    }
}
