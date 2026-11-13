const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../dist');
const port = Number(process.env.FRONTEND_V2_PORT || 4173);
http.createServer((req,res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return; }
  const pathname = new URL(req.url,'http://localhost').pathname;
  if (!['/','/index.html'].includes(pathname)) { res.writeHead(404); res.end('Not found'); return; }
  res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});
  if (req.method === 'HEAD') res.end(); else fs.createReadStream(path.join(root,'index.html')).pipe(res);
}).listen(port,'127.0.0.1',() => console.log(`Frontend V2: http://127.0.0.1:${port}`));
