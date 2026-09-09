import express from 'express';
import * as ftp from 'basic-ftp';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import iconv from 'iconv-lite';
import {fileURLToPath} from 'node:url';
export const app=express();
app.use(express.json());
app.get('/api/local/list',async(req,res)=>{
  try {
    const dir=path.resolve(req.query.path||os.homedir());
    const entries=await fs.readdir(dir,{withFileTypes:true});
    const files=(await Promise.all(entries.filter(e=>!e.name.startsWith('.')&&!e.isSymbolicLink()).map(async e=>{
      try {const s=await fs.stat(path.join(dir,e.name));return {name:e.name,type:s.isDirectory()?'folder':'file',size:s.size,modifiedAt:s.mtime};}catch{return null;}
    }))).filter(Boolean).sort((a,b)=>(a.type===b.type?0:a.type==='folder'?-1:1)||a.name.localeCompare(b.name));
    res.json({path:dir,files});
  }catch(e){res.status(400).json({error:e.message});}
});
app.post('/api/ftp/list',async(req,res)=>{
  const c=new ftp.Client(15000);
  try {
    const {host,port=21,user='anonymous',password='',remotePath='/',protocol='FTP',encoding='utf8',ignoreCertificate=false}=req.body;
    if(!host||!Number.isInteger(+port)||+port<1||+port>65535) throw new Error('请填写有效的主机和端口');
    if(!['utf8','gbk','gb18030','big5','latin1'].includes(encoding)) throw new Error('不支持的编码');
    if(!['FTP','FTPS','FTPS implicit'].includes(protocol)) throw new Error('不支持的协议');
    const wire=s=>encoding==='utf8'?s:iconv.encode(s,encoding).toString('latin1');
    const decode=s=>encoding==='utf8'?s:iconv.decode(Buffer.from(s,'latin1'),encoding);
    c.ftp.encoding=encoding==='utf8'?'utf8':'latin1';
    await c.access({host,port:+port,user:wire(user),password:wire(password),secure:protocol==='FTP'?false:protocol==='FTPS implicit'?'implicit':true,secureOptions:{rejectUnauthorized:!ignoreCertificate}});
    if(encoding!=='utf8') await c.send('OPTS UTF8 OFF',true);
    await c.cd(wire(remotePath));
    const current=decode(await c.pwd());
    const files=await c.list();
    const result=[];
    for(const x of files){
      if(x.name==='.'||x.name==='..') continue;
      let directory=x.isDirectory;
      // Directory symlinks and unknown LIST types need a CWD probe.
      if(x.isSymbolicLink||x.type===ftp.FileType.Unknown){
        let entered=false;
        try{await c.cd(x.name);entered=true;directory=true;}
        catch(e){if(!(e instanceof ftp.FTPError)) throw e;}
        // A failed restore must abort the listing to avoid probing the wrong directory.
        if(entered) await c.cd(wire(current));
      }
      result.push({name:decode(x.name),type:directory?'folder':x.isSymbolicLink?'link':'file',size:x.size,modifiedAt:x.modifiedAt});
    }
    res.json({path:current,files:result.sort((a,b)=>(a.type===b.type?0:a.type==='folder'?-1:b.type==='folder'?1:0)||a.name.localeCompare(b.name))});
  }catch(e){res.status(400).json({error:e.message});}finally{c.close();}
});
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) app.listen(3001,'127.0.0.1',()=>console.log('FTP API on 3001'));
