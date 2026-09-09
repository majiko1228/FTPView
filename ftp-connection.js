import iconv from 'iconv-lite';
export async function connectFTP(c,config){
 const {host,port=21,user='anonymous',password='',protocol='FTP',encoding='utf8',ignoreCertificate=false}=config;
 if(!host||!Number.isInteger(+port)||+port<1||+port>65535)throw new Error('请填写有效的主机和端口');
 if(!['utf8','gbk','gb18030','big5','latin1'].includes(encoding))throw new Error('不支持的编码');
 if(!['FTP','FTPS','FTPS implicit'].includes(protocol))throw new Error('不支持的协议');
 const wire=s=>{if(/[\r\n\0]/.test(s))throw new Error('名称或路径含不支持的控制字符');const bytes=iconv.encode(s,encoding);if(iconv.decode(bytes,encoding)!==s)throw new Error('所选编码无法表示此名称，请使用 UTF-8');return encoding==='utf8'?s:bytes.toString('latin1');};
 const decode=s=>encoding==='utf8'?s:iconv.decode(Buffer.from(s,'latin1'),encoding);
 c.ftp.encoding=encoding==='utf8'?'utf8':'latin1';
 await c.access({host,port:+port,user:wire(user),password:wire(password),secure:protocol==='FTP'?false:protocol==='FTPS implicit'?'implicit':true,secureOptions:{rejectUnauthorized:!ignoreCertificate}});
 if(encoding!=='utf8')await c.send('OPTS UTF8 OFF',true);
 return {wire,decode};
}
