import fs from 'node:fs/promises';
import {createReadStream,createWriteStream} from 'node:fs';
import {createHash,randomUUID} from 'node:crypto';
import {Writable} from 'node:stream';
import path from 'node:path';
import * as ftp from 'basic-ftp';
import {connectFTP} from './ftp-connection.js';
export const jobs=new Map();
export const isTransferring=()=>[...jobs.values()].some(j=>!['completed','failed'].includes(j.status));
async function digestFile(file){const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return hash.digest('hex');}
async function digestRemote(c,file){const hash=createHash('sha256');await c.downloadTo(new Writable({write(chunk,enc,cb){hash.update(chunk);cb();}}),file);return hash.digest('hex');}
function validName(name){if(typeof name!=='string'||!name||name==='.'||name==='..'||/[\/\\\r\n\0]/.test(name))throw new Error('无效的文件名');}
export async function transfer(job,input,c=new ftp.Client(30000)){
 let temporary,ownedRemoteDir,handle;
 try{
  const {direction,name,localPath,remotePath}=input;
  validName(name);
  if(!['upload','download'].includes(direction))throw new Error('无效的传输方向');
  if(!path.isAbsolute(localPath)||typeof remotePath!=='string')throw new Error('请选择有效的目录');
  const {wire}=await connectFTP(c,input);
  await c.cd(wire(remotePath));
  const remoteName=wire(name),localName=path.join(localPath,name);
  job.status='transferring';
  c.trackProgress(p=>{if(job.status==='transferring')job.bytes=p.bytes;});
  if(direction==='upload'){
   const before=await fs.lstat(localName);
   if(!before.isFile())throw new Error('当前仅支持普通文件，请选择文件，文件夹不会被跳过传输');
   job.size=before.size;
   // Reserve a new server directory with MKD, never write into an existing destination.
   // FTP has no portable atomic no-replace rename, so each upload gets its own directory.
   ownedRemoteDir='FTPView-'+randomUUID();
   const candidate=ownedRemoteDir;ownedRemoteDir=undefined;
   await c.send('MKD '+candidate);ownedRemoteDir=candidate;
   temporary=candidate+'/.ftpview-part-'+randomUUID();
   job.destination=path.posix.join(remotePath,candidate,name);
   job.recoveryPath=path.posix.join(remotePath,temporary);
   await c.uploadFrom(createReadStream(localName),temporary);
   job.status='verifying';
   const after=await fs.stat(localName);
   if(before.size!==after.size||before.mtimeMs!==after.mtimeMs||before.ctimeMs!==after.ctimeMs)throw new Error('源文件在传输过程中发生变化，未发布目标文件');
   if(await c.size(temporary)!==before.size)throw new Error('上传大小校验失败');
   const sourceHash=await digestFile(localName);
   if(await digestRemote(c,temporary)!==sourceHash)throw new Error('上传 SHA-256 校验失败');
   const finalStat=await fs.stat(localName);
   if(finalStat.ino!==before.ino||finalStat.size!==before.size||finalStat.mtimeMs!==before.mtimeMs||finalStat.ctimeMs!==before.ctimeMs)throw new Error('源文件已变化，未发布目标文件');
   const target=candidate+'/'+remoteName;
   if((await c.list(candidate)).some(x=>x.name===remoteName))throw new Error('目标名称已存在，拒绝覆盖');
   await c.rename(temporary,target);temporary=undefined;
   job.sha256=sourceHash;
  }else{
   const entries=await c.list();const source=entries.find(x=>x.name===remoteName);
   if(!source?.isFile)throw new Error('请选择普通文件；文件夹和链接暂不支持传输');
   try{await fs.lstat(localName);throw new Error('本机存在同名文件，拒绝覆盖');}catch(e){if(e.code!=='ENOENT')throw e;}
   const beforeSize=await c.size(remoteName);job.size=beforeSize;
   temporary=path.join(localPath,'.ftpview-part-'+randomUUID());
   job.recoveryPath=temporary;job.destination=localName;
   await c.downloadTo(createWriteStream(temporary,{flags:'wx',mode:0o600}),remoteName);
   handle=await fs.open(temporary,'r+');
   await handle.sync();
   job.status='verifying';
   if((await handle.stat()).size!==beforeSize||await c.size(remoteName)!==beforeSize)throw new Error('下载大小校验失败');
   const hash=await digestFile(temporary);
   if(hash!==await digestRemote(c,remoteName))throw new Error('下载 SHA-256 校验失败，源文件可能发生变化');
   await handle.close();handle=undefined;
   // Hard-link publication is atomic and fails if the target appeared during download.
   await fs.link(temporary,localName);
   await fs.unlink(temporary);temporary=undefined;job.sha256=hash;
  }
  job.bytes=job.size;job.status='completed';delete job.recoveryPath;
 }catch(e){job.status='failed';job.error=e.message;if(!temporary)delete job.recoveryPath;}
 finally{if(handle)await handle.close().catch(()=>{});c.close();job.finishedAt=Date.now();}
}
