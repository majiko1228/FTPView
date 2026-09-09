import express from 'express';
import * as ftp from 'basic-ftp';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {connectFTP} from './ftp-connection.js';
import {jobs,isTransferring,transfer} from './transfers.js';
import {randomUUID} from 'node:crypto';
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
    const {remotePath='/'}=req.body;
    const {wire,decode}=await connectFTP(c,req.body);
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
app.get('/api/transfers',(req,res)=>res.json({jobs:[...jobs.values()]}));
let draining=false;
app.post('/api/transfers',(req,res)=>{
 if(draining)return res.status(409).json({error:'服务正在重启，请稍后重试'});
 if(isTransferring())return res.status(409).json({error:'已有文件正在传输，请等待完成'});
 const {name,direction}=req.body;
 const job={id:randomUUID(),name,direction,status:'connecting',bytes:0,size:0,startedAt:Date.now()};
 jobs.set(job.id,job);
 if(jobs.size>100)jobs.delete(jobs.keys().next().value);
 res.status(202).json(job);
 void transfer(job,req.body);
});
app.get('/api/runtime',(req,res)=>res.json({project:process.cwd(),busy:isTransferring()}));
app.post('/api/runtime/drain',(req,res)=>{
 if(isTransferring())return res.status(409).json({error:'有文件正在传输或校验，请等待完成'});
 draining=true;res.json({project:process.cwd(),busy:false});
});
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const server=app.listen(3001,'127.0.0.1');
  server.on('listening',()=>console.log('FTP API on 3001'));
  server.on('error',error=>{
    console.error(error.code==='EADDRINUSE'?'启动失败：3001 端口已被占用，请停止旧的 FTPView 实例。':error.message);
    process.exitCode=1;
  });
}
