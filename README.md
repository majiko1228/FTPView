# FTPView

JDK 11 + Spring Boot 2.7.18 + Apache Commons Net 后端。
前端：[FTPView-Web](https://github.com/majiko1228/FTPView-Web)。

```sh
mvn clean verify
mvn spring-boot:run
```

默认 `127.0.0.1:5172`，环境变量 `FTPVIEW_PORT` 可修改。前端默认使用 5173。
打包启动：`java -jar target/ftpview-0.1.0-SNAPSHOT.jar`。

## 接口

- GET /api/health：健康检查
- GET /api/local/list?path=：本机目录
- POST /api/connect：创建 FTP 浏览会话
- POST /api/disconnect：断开会话
- POST /api/remote/list：读取远端目录
- POST /api/delete：删除文件或完整目录
- POST /api/transfers：提交单文件复制任务
- GET /api/transfers：任务状态与字节进度

连接参数：host、port、user、password、protocol、encoding、ignoreCertificate。
目录参数：session、path。传输参数：session、name、direction(upload/download)、localPath、remotePath。

浏览操作复用串行 FTP 会话，传输使用独立连接。FTP 会话失效时请重新连接。
传输保持源文件，先临时写入，再进行 SHA-256 内容校验。上传创建独立目录；下载硬链接发布，禁止覆盖已有文件，文件系统不支持硬链接则失败。

当前不支持断点续传和任务持久化。请勿在传输期间关闭服务或修改源文件。失败时可能留下临时文件，进度接口提供位置。回读校验增加网络流量；不是存储设备故障或远端并发修改的绝对保证。
服务限定本机使用，未实现多用户鉴权，勿暴露到公网。Spring Boot 2.7 为兼容 JDK 11 选择，已结束开源常规维护。

测试使用 MockMvc 和模拟 FTP 流，不监听端口；覆盖健康接口、目录隐藏文件、删除保护、下载内容校验、同名拒绝和上传损坏不发布。真实 FTP/FTPS 服务器的权限、编码和网络兼容性仍需联调。

## 分层与代码格式

- `api`：Controller 仅处理路由和服务委派；`ApiExceptionHandler` 统一异常响应。
- `service/FtpConnectionService`：FTP/FTPS 连接配置及资源释放。
- `service/FtpSessionService`：浏览会话的创建、查询与销毁。
- `service/FileService`：本机/远端目录查询和文件及目录删除。
- `service/TransferService`：任务调度、上传、下载和内容校验。
- `dto`：连接参数、操作请求、文件条目和任务数据。
- `util/PathValidator`：文件名及远端路径校验。

统一使用四空格缩进、独立语句换行和方法注释。`mvn spotless:apply` 自动格式化 Java 源码；`mvn verify` 会先检查格式，不符合规范则失败。

接口统一返回 `{code,data,msg}`：成功 `code=0`，失败返回非零业务码和对应 HTTP 错误状态。批量操作使用单文件接口逐项提交；传输后端最多两个独立文件并发执行（最多 100 个未完成任务），单文件失败不阻塞其他任务。


## 取消、速率与校验

- `POST /api/transfers/{id}/cancel` 取消排队、传输或校验任务。结束态为 `cancelled`；连接建立阶段会在连接返回或超时后收尾。发布阶段拒绝取消，避免保存结果不确定。
- 取消关闭活动数据流，保留源文件及可能存在的临时文件，不将不完整内容发布为正式文件。
- 未结束任务按服务器、端口、账号、协议、方向、本机路径和远端路径去重；更换会话 ID 也不能重复提交。取消收尾完成后可重试。
- 任务字段 `bytesPerSecond` 表示当前处理速率，`verifyBytes/verifyTotal` 表示独立校验进度，`phase` 表示当前阶段。前端对超过两秒无更新的速率显示 0。
- 上传：流式计算 SHA-256 → 回读远端临时文件比较 → 重读本机源文件检查变化 → 发布。下载：流式计算 SHA-256 → 重读本机临时文件 → 回读远端源文件 → 发布。本机和远端各读一遍，因此校验总量约为文件大小两倍。
- 两文件并发与 256 KiB 缓冲改善多文件吞吐，不对单文件进行分片上传；实际吞吐取决于网络、磁盘和服务器限速，未以真实服务器测量加速比例。


## 文件夹操作

勾选文件夹可递归上传、下载、删除；包含隐藏文件、空目录和零字节文件。目录树内出现链接、特殊文件或无法读取的条目时明确失败，不静默跳过。
目录传输逐文件进行 SHA-256 校验，并在发布前核对源目录清单。目录任务可取消，失败或取消时保留临时目录。
为避免目录整体发布覆盖已有内容，上传、目录下载均保存到独立 `FTPView-…/原目录名` 容器，完整路径显示在任务详情。文件夹下载仍拒绝左侧当前目录的同名冲突。
递归删除在确认后预检整个目录树，再自下而上删除；删除不可恢复，运行中遇到权限或网络错误可能已删除部分内容，请根据错误提示刷新检查。
