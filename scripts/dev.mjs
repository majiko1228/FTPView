import {spawn} from 'node:child_process';
import fs from 'node:fs/promises';
import {watchFile,unwatchFile} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import http from 'node:http';
import {randomUUID} from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const stateDir=path.join(root,'.ftpview-runtime'),stateFile=path.join(stateDir,'owner.json');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const restart=process.argv.includes('--restart');
let saved;
try{
 saved=JSON.parse(await fs.readFile(stateFile,'utf8'));
 const r=await fetch(`http://127.0.0.1:${saved.port}/${restart?'stop':'status'}`,{method:restart?'POST':'GET',headers:{authorization:saved.token},signal:AbortSignal.timeout(3000)});
 if(!r.ok)throw new Error(await r.text());
 if(!restart){console.log('FTPView 已运行：http://localhost:5173/ 。重启请执行 npm run restart');process.exit(0);}
 for(let i=0;i<50;i++){try{await fs.access(stateDir);}catch{break;}await delay(100);}
}catch(e){if(e.code!=='ENOENT'){
 let dead=false;
 if(saved?.pid){try{process.kill(saved.pid,0);}catch(err){dead=err.code==='ESRCH';}}
 if(dead){await fs.rm(stateFile,{force:true});await fs.rmdir(stateDir);}
 else{console.error('无法安全重启：'+e.message+'。不会自动结束未知进程。');process.exit(1);}
}}
try{await fs.mkdir(stateDir);}catch{console.error('另一个启动操作正在运行，或上次异常退出。请检查 .ftpview-runtime；不会强制结束进程。');process.exit(1);}
const children=new Set();let backend,stopping=false,reloading=false,pending=false;
async function busy(){if(!backend||backend.exitCode!==null)return false;const r=await fetch('http://127.0.0.1:3001/api/runtime/drain',{method:'POST',signal:AbortSignal.timeout(2000)});if(r.status===409)return true;if(!r.ok)throw new Error('后端状态无法确认');const d=await r.json();if(d.project!==root)throw new Error('3001 端口属于其他项目');return d.busy;}
function launch(args){const child=spawn(process.execPath,args,{cwd:root,stdio:'inherit',detached:process.platform!=='win32'});children.add(child);child.once('exit',()=>{children.delete(child);if(!stopping&&!reloading)void stop(1);});return child;}
async function terminate(child){if(child.exitCode!==null||child.signalCode)return;const done=new Promise(r=>child.once('exit',r));child.kill('SIGTERM');const timer=setTimeout(()=>{if(child.exitCode===null&&!child.signalCode)child.kill('SIGKILL');},3000);await done;clearTimeout(timer);}
async function stop(code=0){if(stopping)return;stopping=true;clearInterval(tick);for(const f of watched)unwatchFile(path.join(root,f));await Promise.all([...children].map(terminate));control.close();await fs.rm(stateFile,{force:true});await fs.rmdir(stateDir);process.exit(code);}
const control=http.createServer(async(req,res)=>{
 if(req.headers.authorization!==token){res.writeHead(403);return res.end('Forbidden');}
 if(req.url==='/status'){return res.end('running');}
 if(req.url!=='/stop'||req.method!=='POST'){res.writeHead(404);return res.end();}
 try{if(await busy()){res.writeHead(409);return res.end('有文件正在传输或校验，请等待完成后再重启');}res.end('stopping');void stop();}catch(e){res.writeHead(409);res.end(e.message);}
});
const token=randomUUID();await new Promise(r=>control.listen(0,'127.0.0.1',r));await fs.writeFile(stateFile,JSON.stringify({pid:process.pid,port:control.address().port,token}),{mode:0o600});
const watched=['server.js','transfers.js','ftp-connection.js'];
const tick=setInterval(async()=>{if(!pending||stopping||reloading)return;try{if(await busy())return;pending=false;reloading=true;await terminate(backend);backend=launch(['server.js']);reloading=false;console.log('后端代码已自动重载');}catch{}},1500);
backend=launch(['server.js']);launch(['node_modules/vite/bin/vite.js']);
for(const f of watched)watchFile(path.join(root,f),{interval:1000},()=>{pending=true;});
for(const sig of ['SIGINT','SIGTERM'])process.on(sig,async()=>{try{if(await busy()){console.log('文件正在传输，停止请求已拒绝；请等待任务完成。');return;}await stop();}catch(e){console.error(e.message);}});
