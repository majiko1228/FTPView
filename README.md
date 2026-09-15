# FTPView

FTPView 的 Java 后端，提供本机与 FTP/FTPS 目录浏览、文件及文件夹管理、传输队列、SHA-256 校验和任务取消 API。

前端项目：[FTPView-Web](https://github.com/majiko1228/FTPView-Web)。技术栈：JDK 11、Spring Boot 2.7.18、Apache Commons Net 3.11.1、Maven。

## 快速启动

准备 JDK 11 和 Maven，并确认 `java -version`、`mvn -version` 使用预期的 JDK。在本项目目录执行：

```sh
mvn clean verify
mvn spring-boot:run
```

默认监听 `127.0.0.1:5172`，可在另一个终端检查服务：

```sh
curl http://127.0.0.1:5172/api/health
```

健康检查返回 `code=0`，`data` 包含 `status: "UP"` 和 `service: "FTPView"`；仅表示后端可用，不代表已连接 FTP。

前端在同级 `FTPView-Web` 目录单独启动：

```sh
cd ../FTPView-Web
npm ci
npm start
```

前端建议使用 Node.js 22 LTS。浏览器打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)，Vite 将 `/api` 代理到本服务。

## 配置与打包

配置文件为 `src/main/resources/application.yml`，通过环境变量修改端口：

```sh
FTPVIEW_PORT=5174 mvn spring-boot:run
```

修改后需同步修改前端 `vite.config.js` 的 `/api` 代理目标并重启前端。

```sh
mvn clean package
java -jar target/ftpview-0.1.0-SNAPSHOT.jar
```

前后端分别运行和停止。传输期间不要关闭 Java 进程；重启后需要重新连接 FTP，内存任务记录也会清空。

## 文件管理

- 浏览本机与远端目录。本机未传 `path` 时打开用户主目录，本机列表默认隐藏隐藏文件和符号链接。
- 创建带扩展名的空文件，拒绝同名目标；自动创建“新建文件夹”“新建文件夹1”…，支持同目录文件夹改名。
- 上传、下载普通文件或完整文件夹；批量操作由前端逐项提交。
- 递归操作包含隐藏文件、空目录和零字节文件；遇到链接、特殊文件或无法读取的条目时明确失败，不静默跳过。
- 递归删除先预检整个目录树，再自下而上删除。删除不可恢复，执行中遇到权限或网络错误可能已删除部分内容。

## 传输与校验

浏览操作复用串行 FTP 会话，传输使用独立连接。最多同时执行两个任务，最多接受 100 个未完成任务；文件夹内的文件依次处理。使用二进制被动模式和 256 KiB 缓冲，单文件不做分片传输，实际吞吐取决于网络、磁盘和服务器限速。

同一服务器、端口、账号、协议、方向和源/目标路径的未完成任务会去重，更换会话 ID 也不能重复提交。失败或取消收尾后可重新提交。

| 操作             | 保存方式                                                                                     |
| ---------------- | -------------------------------------------------------------------------------------------- |
| 文件上传         | 在远端当前目录创建独立 `FTPView-UUID` 容器，校验后保存原文件名                               |
| 文件下载         | 先写本机临时文件，校验后通过硬链接发布为原文件名；拒绝同名目标，不支持硬链接的文件系统会失败 |
| 文件夹上传、下载 | 保存到独立 `FTPView-…/原目录名` 容器；文件夹下载仍检查本机当前目录的同名冲突                 |

传输保留源文件。上传先流式计算 SHA-256，再回读远端临时文件并重读本机源文件；下载先流式计算 SHA-256，再重读本机临时文件并回读远端源文件。文件夹逐文件校验，并在发布前核对源目录清单。校验读取总量约为文件大小的两倍，会增加网络和磁盘开销。

任务状态包括 `queued`、`connecting`、`transferring`、`verifying`、`publishing`、`cancelling`，结束状态为 `completed`、`failed`、`cancelled`。排队、连接、传输与校验阶段可请求取消，发布阶段拒绝取消；连接建立阶段需等连接返回或超时后收尾。

取消会关闭活动数据流，不发布不完整内容。失败或取消可能保留临时文件、目录，通过任务的 `recoveryPath` 查看；实际保存路径见 `destination`。当前不支持断点续传和任务持久化，已结束任务记录还可能随新任务提交被清理。传输期间不要修改源文件。

## API

所有接口统一返回 `{code,data,msg}`：成功 `code=0`；失败返回非零业务码及对应 HTTP 错误状态。POST 请求使用 JSON 请求体。

| 方法 | 路径                         | 参数与用途                                                                          |
| ---- | ---------------------------- | ----------------------------------------------------------------------------------- |
| GET  | `/api/health`                | 健康检查                                                                            |
| GET  | `/api/local/list?path=`      | 本机目录；`path` 可省略                                                             |
| POST | `/api/connect`               | 连接配置，返回 `data.session`                                                       |
| POST | `/api/disconnect`            | `session`，断开浏览会话                                                             |
| POST | `/api/remote/list`           | `session`、`path`，远端目录                                                         |
| POST | `/api/delete`                | `path`、`name`；远端另传 `session`，删除文件或完整目录                              |
| POST | `/api/entries/file`          | `path`、`name`；远端另传 `session`，创建空文件，名称需有主体及扩展名                |
| POST | `/api/entries/folder`        | `path`；远端另传 `session`，自动避重创建文件夹并返回实际条目                        |
| POST | `/api/entries/folder/rename` | `path`、`name`、`newName`；远端另传 `session`，文件夹改名                           |
| POST | `/api/transfers`             | `session`、`name`、`direction`、`localPath`、`remotePath`，提交一个文件或文件夹任务 |
| GET  | `/api/transfers`             | 查询任务列表和进度                                                                  |
| POST | `/api/transfers/{id}/cancel` | 请求取消指定任务，无需请求体                                                        |

本机操作省略 `session`。传输的 `localPath` 和 `remotePath` 均为所在目录，`name` 为选中条目名称；`direction` 为 `upload` 或 `download`。

连接参数：

| 字段                | 默认值      | 说明                                                                |
| ------------------- | ----------- | ------------------------------------------------------------------- |
| `host`              | 无          | 必填服务器地址                                                      |
| `port`              | `21`        | 服务器端口，切换协议时需自行指定正确端口                            |
| `user`              | `anonymous` | 登录账号                                                            |
| `password`          | 空字符串    | 登录密码                                                            |
| `protocol`          | `FTP`       | `FTP`、`FTPS`（显式 TLS）、`FTPS implicit`（隐式 TLS），不支持 SFTP |
| `encoding`          | `UTF-8`     | 可选 `UTF-8`、`GBK`、`GB18030`、`Big5`、`ISO-8859-1`                |
| `ignoreCertificate` | `true`      | FTPS 是否忽略证书验证；设为 `false` 启用证书及主机名检查            |

任务字段：`bytes/size` 为传输进度，`verifyBytes/verifyTotal` 为独立校验进度，`bytesPerSecond` 为当前处理速率，`updatedAt` 为更新时间；`phase` 为阶段说明，`error` 为失败原因。`startedAt` 与 `updatedAt` 使用毫秒时间戳。前端对超过两秒未更新的速率显示为 0。

## 代码结构与检查

下表中的业务包位于 `src/main/java/com/ftpview/` 下：

| 路径                           | 职责                               |
| ------------------------------ | ---------------------------------- |
| `api`                          | 路由委派、统一响应与异常处理       |
| `service/FtpConnectionService` | FTP/FTPS 配置、连接与资源释放      |
| `service/FtpSessionService`    | 浏览会话生命周期                   |
| `service/FileService`          | 目录查询与删除入口                 |
| `service/FolderService`        | 递归目录扫描、清单和删除           |
| `service/EntryCreationService` | 新建文件、文件夹与文件夹改名       |
| `service/TransferService`      | 任务队列、取消、上传下载与内容校验 |
| `dto`、`util/PathValidator`    | 数据契约与路径/名称校验            |

测试位于 `src/test/java/com/ftpview/`。Java 使用四空格缩进、独立语句换行和方法注释。

```sh
mvn spotless:apply
mvn clean verify
```

`verify` 包含格式检查和测试。测试使用 MockMvc 与模拟 FTP 流，无需真实 FTP 服务器；覆盖 API、创建、删除、递归目录、队列、取消、同名保护及内容校验。真实 FTP/FTPS 的权限、编码与网络兼容性仍需联调。

## 使用边界

服务限定本机使用，未实现多用户鉴权，勿暴露到公网。左侧文件系统属于运行 Java 服务的机器。FTP 普通协议不加密；FTPS 可在连接设置中启用证书检查。

普通 FTP 没有标准的原子“不覆盖改名”指令，远端通过会话串行操作和发布前检查避免本项目并发冲突；避免其他客户端同时修改相同目标名称。内容回读校验不能绝对保证存储设备故障或远端并发修改下的数据一致性。

Spring Boot 2.7 为兼容 JDK 11 选择，已结束开源常规维护。
