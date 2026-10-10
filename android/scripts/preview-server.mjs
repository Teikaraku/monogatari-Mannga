import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs/promises';
import crypto from 'node:crypto';
import path from 'node:path';
const run = promisify(execFile);
const adb = process.env.AIDOKU_ADB || 'D:/Android/Sdk/platform-tools/adb.exe';
const serial = process.env.AIDOKU_DEVICE || 'emulator-5580';
const port = Number(process.env.AIDOKU_PREVIEW_PORT || 8765);
const nonce = crypto.randomBytes(24).toString('hex');
const adbRun = (args, binary = false) => run(adb, ['-s', serial, ...args], { encoding: binary ? 'buffer' : 'utf8', maxBuffer: 24 * 1024 * 1024, timeout: 15000, windowsHide: true });
let latest = null, lastCapture = 0, pending = null;
let inputTail = Promise.resolve();
const serialInput = action => { const result=inputTail.then(action);inputTail=result.catch(()=>{});return result; };
async function screen() {
  if (latest && Date.now() - lastCapture < 400) return latest;
  if (!pending) pending = adbRun(['exec-out','screencap','-p'], true).then(r => {
    if (!r.stdout.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) throw new Error('Invalid emulator screenshot');
    latest = r.stdout; lastCapture = Date.now(); return latest;
  }).finally(() => { pending = null; });
  return pending;
}
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>物语 · 安卓实时预览</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f3f3f5;color:#222;font:14px system-ui,-apple-system,"Microsoft Yahei",sans-serif;display:flex;min-height:100vh;justify-content:center;align-items:center;padding:18px;gap:28px}.phone{height:min(90vh,860px);aspect-ratio:1080/2400;background:#fff;border-radius:20px;box-shadow:0 10px 40px #0002;overflow:hidden}img{display:block;width:100%;height:100%;object-fit:contain;touch-action:none;cursor:pointer}aside{max-width:250px}h1{font-size:23px;margin:0 0 12px}p{line-height:1.7;color:#777}button{border:1px solid #ddd;border-radius:10px;background:white;color:#ff2d55;padding:11px 16px;margin:6px 6px 0 0;cursor:pointer}small{color:#888}#status{font-size:12px}@media(max-width:700px){body{flex-direction:column;gap:10px;padding:10px}.phone{height:80vh}aside{text-align:center;max-width:100%}aside p{display:none}h1{font-size:16px;margin:0}}</style>
<div class="phone"><img id="display" alt="正在连接安卓模拟器…" tabindex="0"></div><aside><h1>物语 安卓预览</h1><small>运行中的 APK · Android 模拟器</small><p>点击、拖动屏幕操作应用。键盘可输入英文和数字；输入框支持粘贴中文、日文等文字。</p><div><button data-key="4">返回</button><button data-key="3">主页</button><button id="openApp">打开物语</button></div><div><input id="pasteText" aria-label="输入文字" placeholder="中文、日文或其他文字" style="width:100%;padding:10px;margin-top:12px;border:1px solid #ddd;border-radius:8px"><button id="paste">粘贴到应用</button></div><p id="status">连接中…</p></aside>
<script>const token='${nonce}', display=document.getElementById('display'), status=document.getElementById('status');let width=1080,height=2400,working=false,down=null;
async function action(data){try{const r=await fetch('/input',{method:'POST',headers:{'Content-Type':'application/json','X-Preview-Key':token},body:JSON.stringify(data)});if(!r.ok)throw Error(await r.text());}catch(e){status.textContent=e.message}}
async function update(){if(working)return;working=true;try{const r=await fetch('/screen?t='+Date.now(),{cache:'no-store'});if(!r.ok)throw Error(await r.text());const blob=await r.blob(),url=URL.createObjectURL(blob),old=display.src;await new Promise((resolve,reject)=>{display.onload=resolve;display.onerror=reject;display.src=url});width=display.naturalWidth;height=display.naturalHeight;resizePhone();if(old.startsWith('blob:'))URL.revokeObjectURL(old);status.textContent='已连接 · '+width+' × '+height;}catch(e){status.textContent='连接失败：'+e.message}finally{working=false}}setInterval(()=>{if(!document.hidden)update()},650);update();
function resizePhone(){const compact=innerWidth<700,scale=Math.min(Math.max(1,innerWidth-(compact?20:350))/width,Math.max(1,innerHeight-(compact?190:36))/height);display.parentElement.style.width=(width*scale)+"px";display.parentElement.style.height=(height*scale)+"px";}addEventListener("resize",resizePhone);function position(e){const r=display.getBoundingClientRect();return{x:Math.max(0,Math.min(width-1,Math.round((e.clientX-r.left)/r.width*width))),y:Math.max(0,Math.min(height-1,Math.round((e.clientY-r.top)/r.height*height)))}}display.onpointerdown=e=>{display.setPointerCapture(e.pointerId);down={...position(e),time:Date.now()};display.focus()};display.onpointerup=e=>{if(!down)return;const up=position(e);const distance=Math.hypot(up.x-down.x,up.y-down.y),duration=Math.min(1500,Math.max(120,Date.now()-down.time));action(distance>14||duration>500?{type:'swipe',x:down.x,y:down.y,x2:up.x,y2:up.y,duration}:{type:'tap',...up});down=null};display.onpaste=e=>{const value=e.clipboardData?.getData('text');if(value){e.preventDefault();action({type:'paste',text:value})}};display.onkeydown=e=>{if(e.ctrlKey||e.metaKey||e.isComposing)return;const keys={Backspace:67,Enter:66,Escape:4,ArrowLeft:21,ArrowRight:22,ArrowUp:19,ArrowDown:20,PageUp:92,PageDown:93,Tab:61};if(keys[e.key]){e.preventDefault();action({type:'key',key:keys[e.key]})}else if(e.key.length===1&&e.key.charCodeAt(0)<127){e.preventDefault();action({type:'text',text:e.key})}};document.querySelectorAll('[data-key]').forEach(b=>b.onclick=()=>action({type:'key',key:Number(b.dataset.key)}));document.getElementById('paste').onclick=()=>action({type:'paste',text:document.getElementById('pasteText').value});document.getElementById('openApp').onclick=()=>action({type:'app'});</script></html>`;
http.createServer(async (req,res) => {
  try {
    if (req.method==='GET' && req.url==='/') { res.setHeader('Content-Type','text/html;charset=utf-8'); res.end(html); return; }
    if (req.method==='GET' && req.url.startsWith('/screen')) { res.setHeader('Content-Type','image/png');res.setHeader('Cache-Control','no-store');res.end(await screen());return; }
    if (req.method==='POST' && req.url==='/input') {
      if (req.headers['x-preview-key']!==nonce) {res.writeHead(403);res.end('Unauthorized');return;}
      const chunks=[];let bytes=0; for await (const chunk of req) { bytes+=chunk.length;if(bytes>65536)throw Error('Input too large');chunks.push(chunk); }
      const data=JSON.parse(Buffer.concat(chunks).toString('utf8')),number=v=>{if(!Number.isFinite(v)||v<0||v>10000)throw Error('Invalid coordinate');return String(Math.round(v))};
      await serialInput(async()=>{
      if(data.type==='tap')await adbRun(['shell','input','tap',number(data.x),number(data.y)]);
      else if(data.type==='swipe')await adbRun(['shell','input','swipe',number(data.x),number(data.y),number(data.x2),number(data.y2),number(data.duration)]);
      else if(data.type==='key' && [3,4,19,20,21,22,61,66,67,92,93].includes(data.key))await adbRun(['shell','input','keyevent',String(data.key)]);
      else if(data.type==='text' && typeof data.text==='string' && /^[\x20-\x7e]{1,100}$/.test(data.text))await adbRun(['shell','input','text',"'"+data.text.replaceAll(' ','%s').replaceAll("'", "'\\''")+"'"]);
      else if(data.type==='paste' && typeof data.text==='string' && data.text.length<=6000){const result=await adbRun(['shell','am','broadcast','-a','app.monogatari.android.PREVIEW_PASTE','-n','app.monogatari.android/.debug.PreviewInputReceiver','--es','textBase64',Buffer.from(data.text,'utf8').toString('base64')]);if(!/result=1\b/.test(result.stdout))throw Error('Install the latest debug APK to enable multilingual input');await adbRun(['shell','input','keyevent','279']);}
      else if(data.type==='app')await adbRun(['shell','am','start','-n','app.monogatari.android/.MainActivity']);
      else throw Error('Unsupported input');
      });
      res.end('OK');return;
    }
    res.writeHead(404);res.end('Not found');
  }catch(error){res.writeHead(500);res.end(error.message);}
}).listen(port,'127.0.0.1',()=>process.stdout.write('Android live preview: http://127.0.0.1:'+port+'\n'));
if (process.argv.includes('--capture')) {
  await fs.mkdir(new URL('../verification/',import.meta.url),{recursive:true});
  await fs.writeFile(new URL('../verification/initial.png',import.meta.url),await screen());
}
