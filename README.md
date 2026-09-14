# FTPView

FTPView 的独立 Java 后端，前端仓库：https://github.com/majiko1228/FTPView-Web

## 环境

- JDK 11
- Maven 3.6.3 或更新版本
- Spring Boot 2.7.18（兼容 JDK 11，已结束开源常规维护）
- Apache Commons Net 3.11.1（预留 FTP/FTPS 实现依赖）

## 开发

```sh
mvn clean verify
mvn spring-boot:run
```

默认监听 `127.0.0.1:8080`，可通过 `FTPVIEW_PORT` 环境变量修改端口。
健康接口：`GET http://127.0.0.1:8080/api/health`

```json
{"status":"UP","service":"FTPView"}
```

打包后运行：`java -jar target/ftpview-0.1.0-SNAPSHOT.jar`。

## 初始化范围

当前仅建立工程、配置、健康接口与 MockMvc 测试，测试不监听网络端口。
FTP 会话、目录浏览、传输与校验后续迁移。前端现有 Node.js 后端暂时保留，8080 端口不会与它的 3001 冲突。
默认分支：`dev`。
