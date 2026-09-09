import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {transfer} from '../transfers.js';
class FakeFTP{
 ftp={};files=new Map();dirs=new Set();
 async access(){}async cd(){}trackProgress(){}close(){}
 async send(cmd){if(cmd.startsWith('MKD ')){const dir=cmd.slice(4);if(this.dirs.has(dir))throw Error('exists');this.dirs.add(dir);}}
 async list(dir){return [...this.files.keys()].filter(n=>dir?n.startsWith(dir+'/'):!n.includes('/')).map(n=>({name:dir?n.slice(dir.length+1):n,isFile:true}));}
 async uploadFrom(stream,dest){const chunks=[];for await(const c of stream)chunks.push(c);this.files.set(dest,Buffer.concat(chunks));}
 async downloadTo(stream,src){await pipeline(Readable.from([this.files.get(src)]),stream);}
 async size(src){return this.files.get(src).length;}
 async rename(src,dst){this.files.set(dst,this.files.get(src));this.files.delete(src);}
}
async function fixture(run){const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ftpview-transfer-'));try{await run(dir);}finally{await fs.rm(dir,{recursive:true});}}
const input=(dir,direction)=>({host:'fake',direction,name:'中文.bin',localPath:dir,remotePath:'/'});
test('upload read-back verification preserves existing remote files and source',()=>fixture(async dir=>{const c=new FakeFTP();const bytes=Buffer.from([0,1,255,4]);await fs.writeFile(path.join(dir,'中文.bin'),bytes);c.files.set('中文.bin',Buffer.from('existing'));const job={};await transfer(job,input(dir,'upload'),c);assert.equal(job.status,'completed');assert.equal(c.files.get('中文.bin').toString(),'existing');assert.deepEqual(c.files.get(job.destination.slice(1)),bytes);assert.deepEqual(await fs.readFile(path.join(dir,'中文.bin')),bytes);}));
test('corrupt upload is never renamed to final filename',()=>fixture(async dir=>{const c=new FakeFTP();await fs.writeFile(path.join(dir,'中文.bin'),'hello');c.uploadFrom=async(stream,dest)=>{for await(const _ of stream){}c.files.set(dest,Buffer.from('wrong'));};const job={};await transfer(job,input(dir,'upload'),c);assert.equal(job.status,'failed');assert.match(job.error,/SHA-256/);assert.ok([...c.files.keys()].every(n=>n.includes('.ftpview-part-')));}));
test('download verifies binary data and refuses overwrite',()=>fixture(async dir=>{const c=new FakeFTP();const bytes=Buffer.from([255,0,5]);c.files.set('中文.bin',bytes);const job={};await transfer(job,input(dir,'download'),c);assert.equal(job.status,'completed');assert.deepEqual(await fs.readFile(path.join(dir,'中文.bin')),bytes);c.files.set('中文.bin',Buffer.from('new'));const duplicate={};await transfer(duplicate,input(dir,'download'),c);assert.equal(duplicate.status,'failed');assert.deepEqual(await fs.readFile(path.join(dir,'中文.bin')),bytes);}));
test('interrupted download never publishes a partial file',()=>fixture(async dir=>{const c=new FakeFTP();c.files.set('中文.bin',Buffer.from('hello'));c.downloadTo=async(stream)=>{await pipeline(Readable.from(['he']),stream);throw Error('Connection lost');};const job={};await transfer(job,input(dir,'download'),c);assert.equal(job.status,'failed');await assert.rejects(fs.access(path.join(dir,'中文.bin')));assert.ok(job.recoveryPath);}));
test('download rejects same-size corruption and concurrent target creation',()=>fixture(async dir=>{const c=new FakeFTP();c.files.set('中文.bin',Buffer.from('hello'));let reads=0;const original=c.downloadTo.bind(c);c.downloadTo=async(stream,src)=>{await original(stream,src);if(++reads===1)c.files.set(src,Buffer.from('wrong'));};const job={};await transfer(job,input(dir,'download'),c);assert.equal(job.status,'failed');assert.match(job.error,/SHA-256/);await assert.rejects(fs.access(path.join(dir,'中文.bin')));
 c.downloadTo=async(stream,src)=>{await original(stream,src);await fs.writeFile(path.join(dir,'中文.bin'),'keep');};const race={};await transfer(race,input(dir,'download'),c);assert.equal(race.status,'failed');assert.equal(await fs.readFile(path.join(dir,'中文.bin'),'utf8'),'keep');}));
test('zero-byte upload and download complete after verification',()=>fixture(async dir=>{const c=new FakeFTP();await fs.writeFile(path.join(dir,'中文.bin'),'');const up={};await transfer(up,input(dir,'upload'),c);assert.equal(up.status,'completed');await fs.unlink(path.join(dir,'中文.bin'));c.files.set('中文.bin',Buffer.alloc(0));const down={};await transfer(down,input(dir,'download'),c);assert.equal(down.status,'completed');assert.equal((await fs.stat(path.join(dir,'中文.bin'))).size,0);}));
test('upload refuses directory and changed source',()=>fixture(async dir=>{const c=new FakeFTP();await fs.mkdir(path.join(dir,'中文.bin'));const folder={};await transfer(folder,input(dir,'upload'),c);assert.equal(folder.status,'failed');assert.equal(c.dirs.size,0);await fs.rmdir(path.join(dir,'中文.bin'));await fs.writeFile(path.join(dir,'中文.bin'),'original');const original=c.uploadFrom.bind(c);c.uploadFrom=async(stream,dest)=>{await original(stream,dest);await fs.writeFile(path.join(dir,'中文.bin'),'changed');};const changed={};await transfer(changed,input(dir,'upload'),c);assert.equal(changed.status,'failed');assert.ok([...c.files.keys()].every(n=>n.includes('.ftpview-part-')));}));
