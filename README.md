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
- POST /api/delete：删除普通文件
- POST /api/transfers：提交单文件复制任务
- GET /api/transfers：任务状态与字节进度

连接参数：host、port、user、password、protocol、encoding、ignoreCertificate。
目录参数：session、path。传输参数：session、name、direction(upload/download)、localPath、remotePath。

浏览操作复用串行 FTP 会话，传输使用独立连接。FTP 会话失效时请重新连接。
传输保持源文件，先临时写入，再进行 SHA-256 内容校验。上传创建独立目录；下载硬链接发布，禁止覆盖已有文件，文件系统不支持硬链接则失败。

当前不支持目录传输、取消、断点续传和任务持久化。请勿在传输期间关闭服务或修改源文件。失败时可能留下临时文件，进度接口提供位置。回读校验增加网络流量；不是存储设备故障或远端并发修改的绝对保证。
服务限定本机使用，未实现多用户鉴权，勿暴露到公网。Spring Boot 2.7 为兼容 JDK 11 选择，已结束开源常规维护。

测试使用 MockMvc 和模拟 FTP 流，不监听端口；覆盖健康接口、目录隐藏文件、删除保护、下载内容校验、同名拒绝和上传损坏不发布。真实 FTP/FTPS 服务器的权限、编码和网络兼容性仍需联调。

## 分层与代码格式

- `api`：Controller 仅处理路由和服务委派；`ApiExceptionHandler` 统一异常响应。
- `service/FtpConnectionService`：FTP/FTPS 连接配置及资源释放。
- `service/FtpSessionService`：浏览会话的创建、查询与销毁。
- `service/FileService`：本机/远端目录查询和普通文件删除。
- `service/TransferService`：任务调度、上传、下载和内容校验。
- `dto`：连接参数、操作请求、文件条目和任务数据。
- `util/PathValidator`：文件名及远端路径校验。

统一使用四空格缩进、独立语句换行和方法注释。`mvn spotless:apply` 自动格式化 Java 源码；`mvn verify` 会先检查格式，不符合规范则失败。
