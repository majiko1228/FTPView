import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import ftp from 'basic-ftp';
import iconv from 'iconv-lite';
import {app} from '../server.js';
async function invoke(route,req){let status=200;let data;const handler=app.router.stack.find(s=>s.route?.path===route).route.stack[0].handle;await handler(req,{status(n){status=n;return this;},json(d){data=d;}});return {status,data};}
test('local directory metadata and invalid directory',async()=>{const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ftpview-test-'));try{await fs.mkdir(path.join(dir,'文件夹'));await fs.writeFile(path.join(dir,'a.txt'),'hello');await fs.writeFile(path.join(dir,'.hidden'),'secret');await fs.mkdir(path.join(dir,'.hidden-folder'));const r=await invoke('/api/local/list',{query:{path:dir}});assert.equal(r.status,200);assert.equal(r.data.files[0].type,'folder');assert.equal(r.data.files[1].size,5);assert.equal(r.data.files.length,2);assert.equal((await invoke('/api/local/list',{query:{path:path.join(dir,'missing')}})).status,400);}finally{await fs.rm(dir,{recursive:true});}});
test('FTPS certificate option, GBK paths and failure reporting',async()=>{const names=['access','send','cd','pwd','list','close'];const old=Object.fromEntries(names.map(k=>[k,ftp.Client.prototype[k]]));let options,folder;const wire=s=>iconv.encode(s,'gbk').toString('latin1');try{Object.assign(ftp.Client.prototype,{async access(o){options=o;},async send(){},async cd(p){folder=p;},async pwd(){return wire('/中文');},async list(){return [{name:wire('文件夹'),isDirectory:true,size:0}];},close(){}});const body={host:'localhost',protocol:'FTPS',ignoreCertificate:true,encoding:'gbk',remotePath:'/中文'};const r=await invoke('/api/ftp/list',{body});assert.equal(r.status,200);assert.equal(options.secure,true);assert.equal(options.secureOptions.rejectUnauthorized,false);assert.equal(folder,wire('/中文'));assert.equal(r.data.files[0].name,'文件夹');assert.equal(r.data.path,'/中文');ftp.Client.prototype.access=async()=>{throw new Error('Login failed');};assert.equal((await invoke('/api/ftp/list',{body})).status,400);}finally{Object.assign(ftp.Client.prototype,old);}});

test('FTP directory links and unknown entries are probed and restored',async()=>{
 const names=['access','cd','pwd','list','close'];const old=Object.fromEntries(names.map(k=>[k,ftp.Client.prototype[k]]));const moves=[];
 const entry=(name,type)=>Object.assign(new ftp.FileInfo(name),{type});
 try{
  Object.assign(ftp.Client.prototype,{async access(){},async cd(p){moves.push(p);if(p==='file-link')throw new ftp.FTPError({code:550,message:'Not a directory'});},async pwd(){return '/';},async list(){return [entry('normal',ftp.FileType.Directory),entry('dir-link',ftp.FileType.SymbolicLink),entry('unknown-dir',ftp.FileType.Unknown),entry('file-link',ftp.FileType.SymbolicLink),entry('plain',ftp.FileType.File)];},close(){}});
  const r=await invoke('/api/ftp/list',{body:{host:'localhost'}});
  assert.equal(r.status,200);
  const types=Object.fromEntries(r.data.files.map(x=>[x.name,x.type]));
  assert.deepEqual(types,{normal:'folder','dir-link':'folder','unknown-dir':'folder','file-link':'link',plain:'file'});
  assert.deepEqual(moves,['/','dir-link','/','unknown-dir','/','file-link']);
 }finally{Object.assign(ftp.Client.prototype,old);}
});
