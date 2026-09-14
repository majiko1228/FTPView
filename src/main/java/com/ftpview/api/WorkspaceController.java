package com.ftpview.api;

import org.apache.commons.net.ftp.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    public static class Config { public String host, user="anonymous", password="", protocol="FTP", encoding="UTF-8"; public int port=21; public boolean ignoreCertificate=true; }
    public static class Request { public String session, path, name, direction, localPath, remotePath; }
    public static class Entry {
        public String name,type,modifiedAt; public long size;
        /** 用统一字段描述本地和远端列表。 */
        Entry(String n,String t,long s,String m){name=n;type=t;size=s;modifiedAt=m;}
    }
    public static class Session { final Config config; final FTPClient client; /** 会话连接专供目录操作，传输另建连接。 */ Session(Config c,FTPClient f){config=c;client=f;} }
    public static class Job {
        public String id=UUID.randomUUID().toString(),name,direction; public volatile String status="connecting",error="",destination="",recoveryPath="";
        public volatile long bytes,size; public long startedAt=System.currentTimeMillis();
    }
    /** 建立二进制被动模式连接，超时失败不降级跳过验证。 */
    protected FTPClient connect(Config c) throws Exception {
        if(c.host==null||c.host.isBlank()||c.port<1||c.port>65535)throw new IOException("请填写有效的主机和端口");
        if(!List.of("FTP","FTPS","FTPS implicit").contains(c.protocol))throw new IOException("不支持的协议");
        if(!List.of("UTF-8","GBK","GB18030","Big5","ISO-8859-1").contains(c.encoding))throw new IOException("不支持的编码");
        FTPClient f;
        if(c.protocol.equals("FTP"))f=new FTPClient();else{
            FTPSClient tls=new FTPSClient(c.protocol.equals("FTPS implicit"));
            tls.setEndpointCheckingEnabled(!c.ignoreCertificate);
            tls.setTrustManager(c.ignoreCertificate?org.apache.commons.net.util.TrustManagerUtils.getAcceptAllTrustManager():org.apache.commons.net.util.TrustManagerUtils.getDefaultTrustManager(null));f=tls;
        }
        try{
            f.setControlEncoding(c.encoding);f.setConnectTimeout(10000);f.setDefaultTimeout(15000);f.setDataTimeout(Duration.ofSeconds(30));
            f.connect(c.host,c.port);f.setSoTimeout(15000);
            if(!FTPReply.isPositiveCompletion(f.getReplyCode())||!f.login(c.user,c.password))throw new IOException("FTP 登录失败");
            if(f instanceof FTPSClient){((FTPSClient)f).execPBSZ(0);((FTPSClient)f).execPROT("P");}
            f.enterLocalPassiveMode();if(!f.setFileType(FTP.BINARY_FILE_TYPE))throw new IOException("无法启用二进制传输");
            f.sendCommand("OPTS",c.encoding.equals("UTF-8")?"UTF8 ON":"UTF8 OFF");return f;
        }catch(Exception e){close(f);throw e;}
    }
    /** 释放网络资源，不让清理错误掩盖原始异常。 */
    private void close(FTPClient f){try{if(f.isConnected())f.disconnect();}catch(IOException ignored){}}
    /** 拒绝路径注入及跨目录文件名。 */
    private void name(String n)throws IOException{if(n==null||n.isBlank()||n.equals(".")||n.equals("..")||n.matches(".*[/\\\\\\r\\n\\x00].*"))throw new IOException("无效文件名");}
    /** 所有远端路径禁止注入 FTP 控制命令。 */
    private String safe(String p)throws IOException{if(p==null||p.contains("\r")||p.contains("\n")||p.indexOf(0)>=0)throw new IOException("无效路径");return p;}
    /** 根据不可猜测的会话标识获取已登录连接。 */
    private Session session(String id)throws IOException{Session s=id==null?null:sessions.get(id);if(s==null)throw new IOException("连接已失效，请重新连接");return s;}
    /** 创建目录会话，避免每次切换目录重复登录。 */
    @PostMapping("/connect") public Map<String,String> login(@RequestBody Config c)throws Exception{if(sessions.size()>=20)throw new IOException("连接数已达上限，请断开旧连接");FTPClient f=connect(c);String id=UUID.randomUUID().toString();sessions.put(id,new Session(c,f));return Map.of("session",id);}
    /** 断开浏览会话；独立传输不受影响。 */
    @PostMapping("/disconnect") public void disconnect(@RequestBody Request r){Session s=sessions.remove(r.session);if(s!=null)synchronized(s){close(s.client);}}
    /** 自动打开用户主目录，默认排除隐藏文件及链接。 */
    @GetMapping("/local/list") public Map<String,Object> local(@RequestParam(defaultValue="") String path)throws Exception{
        Path dir=Paths.get(path.isBlank()?System.getProperty("user.home"):path).toRealPath();List<Entry> list=new ArrayList<>();
        try(Stream<Path> stream=Files.list(dir)){for(Path p:stream.collect(Collectors.toList())){
            if(Files.isHidden(p)||Files.isSymbolicLink(p))continue;
            list.add(new Entry(p.getFileName().toString(),Files.isDirectory(p)?"folder":"file",Files.size(p),Files.getLastModifiedTime(p).toInstant().toString()));
        }}return Map.of("path",dir.toString(),"files",list);
    }
    /** 同一浏览连接串行执行，避免多个 CWD/LIST 命令相互污染。 */
    @PostMapping("/remote/list") public Map<String,Object> remote(@RequestBody Request r)throws Exception{
        Session s=session(r.session);synchronized(s){FTPClient f=s.client;if(!f.changeWorkingDirectory(safe(r.path)))throw new IOException("目录不存在或无权限");String current=f.printWorkingDirectory();List<Entry> list=new ArrayList<>();
        FTPFile[] entries=f.listFiles();if(!FTPReply.isPositiveCompletion(f.getReplyCode()))throw new IOException("读取目录失败");
        for(FTPFile x:entries){if(x.getName().equals(".")||x.getName().equals(".."))continue;boolean folder=x.isDirectory();
            if(x.isSymbolicLink()||x.isUnknown()){folder=f.changeWorkingDirectory(safe(x.getName()));if(folder&&!f.changeWorkingDirectory(current))throw new IOException("无法恢复目录");}
            list.add(new Entry(x.getName(),folder?"folder":x.isSymbolicLink()?"link":"file",x.getSize(),x.getTimestamp()==null?null:x.getTimestamp().toInstant().toString()));
        }return Map.of("path",current,"files",list);}
    }
    /** 仅删除明确选中的普通文件，拒绝递归删除。 */
    @PostMapping("/delete") public void delete(@RequestBody Request r)throws Exception{
        name(r.name);if(jobs.values().stream().anyMatch(j->!List.of("completed","failed").contains(j.status)))throw new IOException("请等待传输结束后删除");
        if(r.session==null){Path p=Paths.get(r.path).resolve(r.name);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("仅支持普通文件");Files.delete(p);}
        else{Session s=session(r.session);synchronized(s){FTPClient f=s.client;if(!f.changeWorkingDirectory(safe(r.path)))throw new IOException("目录不可用");FTPFile x=f.mlistFile(r.name);if(x==null){x=Arrays.stream(f.listFiles()).filter(e->e.getName().equals(r.name)).findFirst().orElse(null);}if(x==null||!x.isFile())throw new IOException("仅支持普通文件");if(!f.deleteFile(r.name))throw new IOException("删除失败");}}
    }
    /** 返回内存任务快照，供前端独立轮询进度。 */
    @GetMapping("/transfers") public Collection<Job> progress(){return jobs.values();}
    /** 单任务传输避免同一客户端重复写入目标。 */
    @PostMapping("/transfers") public synchronized Job submit(@RequestBody Request r)throws Exception{
        name(r.name);Session s=session(r.session);if(!List.of("upload","download").contains(r.direction))throw new IOException("无效方向");
        if(jobs.values().stream().anyMatch(j->!List.of("completed","failed").contains(j.status)))throw new IOException("已有任务正在传输");
        if(jobs.size()>100)jobs.clear();Job j=new Job();j.name=r.name;j.direction=r.direction;jobs.put(j.id,j);worker.submit(()->transfer(j,r,s.config));return j;
    }
    /** 计算 SHA-256，可同时记录传输字节；流由调用者关闭。 */
    private byte[] copy(InputStream in,OutputStream out,Job j)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(out!=null)out.write(buffer,0,n);hash.update(buffer,0,n);if(j!=null)j.bytes+=n;}return hash.digest();}
    /** 回读远端文件校验内容，必须消费 FTP 完成响应。 */
    private byte[] remoteHash(FTPClient f,String p)throws Exception{InputStream in=f.retrieveFileStream(p);if(in==null)throw new IOException("无法回读校验");byte[] hash;try(InputStream stream=in){hash=copy(stream,null,null);}if(!f.completePendingCommand())throw new IOException("回读未完成");return hash;}
    /** 临时写入与哈希校验成功后发布，不删除源文件，不覆盖同名目标。 */
    void transfer(Job j,Request r,Config c){FTPClient f=null;try{
        name(r.name);safe(r.name);f=connect(c);if(!f.changeWorkingDirectory(safe(r.remotePath)))throw new IOException("远端目录不可用");Path local=Paths.get(r.localPath).resolve(r.name);j.status="transferring";
        if(r.direction.equals("upload")){
            if(!Files.isRegularFile(local,LinkOption.NOFOLLOW_LINKS))throw new IOException("仅支持普通文件");j.size=Files.size(local);long modified=Files.getLastModifiedTime(local).toMillis();
            String dir="FTPView-"+UUID.randomUUID(),temp=dir+"/.part",dest=dir+"/"+r.name;
            if(!f.makeDirectory(dir))throw new IOException("无法创建独立上传目录");j.recoveryPath=r.remotePath+"/"+temp;j.destination=r.remotePath+"/"+dest;
            OutputStream out=f.storeFileStream(temp);if(out==null)throw new IOException("无法写入临时文件");byte[] original;
            try(InputStream in=Files.newInputStream(local);OutputStream stream=out){original=copy(in,stream,j);}if(!f.completePendingCommand())throw new IOException("上传未完成");
            j.status="verifying";if(j.bytes!=j.size||!Arrays.equals(original,remoteHash(f,temp)))throw new IOException("内容校验失败");
            byte[] current;try(InputStream in=Files.newInputStream(local)){current=copy(in,null,null);}if(Files.size(local)!=j.size||Files.getLastModifiedTime(local).toMillis()!=modified||!Arrays.equals(original,current))throw new IOException("源文件已变化");
            if(Arrays.stream(f.listFiles(dir)).anyMatch(x->x.getName().equals(r.name)))throw new IOException("目标已存在");if(!f.rename(temp,dest))throw new IOException("发布文件失败");
        }else{
            FTPFile source=Arrays.stream(f.listFiles()).filter(x->x.getName().equals(r.name)).findFirst().orElseThrow(()->new IOException("文件不存在"));if(!source.isFile())throw new IOException("仅支持普通文件");j.size=source.getSize();if(Files.exists(local,LinkOption.NOFOLLOW_LINKS))throw new IOException("同名文件已存在，拒绝覆盖");
            Path temp=Files.createTempFile(local.getParent(),".ftpview-",".part");j.recoveryPath=temp.toString();j.destination=local.toString();InputStream in=f.retrieveFileStream(r.name);if(in==null)throw new IOException("下载失败");byte[] original;
            try(InputStream stream=in;OutputStream out=Files.newOutputStream(temp)){original=copy(stream,out,j);}if(!f.completePendingCommand())throw new IOException("下载未完成");j.status="verifying";
            byte[] disk;try(InputStream stored=Files.newInputStream(temp)){disk=copy(stored,null,null);}if(j.bytes!=j.size||!Arrays.equals(original,disk)||!Arrays.equals(original,remoteHash(f,r.name)))throw new IOException("内容校验失败");
            try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}Files.createLink(local,temp);Files.delete(temp);
        }j.recoveryPath="";j.status="completed";
    }catch(Exception e){j.error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();j.status="failed";}finally{if(f!=null)close(f);}}
    /** 将可预期错误转换为前端统一提示。 */
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> error(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"操作失败":e.getMessage()));}
    /** 应用退出时关闭浏览连接并停止任务线程。 */
    @PreDestroy public void shutdown(){sessions.values().forEach(s->close(s.client));worker.shutdownNow();}
}
