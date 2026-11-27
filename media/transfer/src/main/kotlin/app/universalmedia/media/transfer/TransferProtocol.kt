package app.universalmedia.media.transfer

/** A file the phone offers to the other device. */
data class SharedFile(val id: String, val name: String, val uri: String, val mimeType: String?, val sizeBytes: Long?)

/** Pure helpers for the transfer web page and its requests. Unit tested. */
object TransferProtocol {

    data class Request(val method: String, val path: String, val query: Map<String, String>)

    fun parseRequestLine(line: String): Request? {
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        return Request(parts[0].uppercase(), decode(target.substringBefore('?')), parseQuery(target.substringAfter('?', "")))
    }

    fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate { kv ->
            decode(kv.substringBefore('=')) to decode(kv.substringAfter('=', ""))
        }

    /** Percent-decoding that treats '+' as a space (form style) and never throws. */
    fun decode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex); i += 3; continue
                    }
                    out.write('%'.code)
                }
                c == '+' -> out.write(' '.code)
                else -> out.write(c.toString().toByteArray())
            }
            i++
        }
        return out.toString("UTF-8")
    }

    /** A file name that's safe to save: no folders, no control or reserved characters. */
    fun safeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trim('.')
        return cleaned.take(120).ifBlank { "file" }
    }

    fun json(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    fun listJson(files: List<SharedFile>, deviceName: String): String = buildString {
        append("{\"device\":").append(json(deviceName)).append(",\"files\":[")
        files.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"id\":").append(json(f.id))
            append(",\"name\":").append(json(f.name))
            append(",\"type\":").append(json(f.mimeType ?: ""))
            append(",\"size\":").append(f.sizeBytes ?: -1)
            append('}')
        }
        append("]}")
    }

    /** The page the other device opens. Everything is relative to the secret session path. */
    val PAGE: String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Universal Media · Wi-Fi transfer</title>
<style>
:root{color-scheme:dark;--bg:#0f1115;--card:#1a1d24;--fg:#e8eaf0;--mute:#9aa0ad;--acc:#7aa8ff}
*{box-sizing:border-box}body{margin:0;font:16px/1.45 system-ui,sans-serif;background:var(--bg);color:var(--fg)}
main{max-width:760px;margin:0 auto;padding:20px}h1{font-size:22px;margin:4px 0 2px}p{color:var(--mute);margin:0 0 16px}
.drop{border:2px dashed #3a4050;border-radius:16px;padding:28px;text-align:center;background:var(--card);cursor:pointer}
.drop.over{border-color:var(--acc)}button,.btn{background:var(--acc);color:#0b1020;border:0;border-radius:999px;padding:10px 18px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-block}
h2{font-size:15px;color:var(--mute);text-transform:uppercase;letter-spacing:.06em;margin:26px 0 8px}
.row{display:flex;align-items:center;gap:12px;background:var(--card);border-radius:12px;padding:12px 14px;margin:6px 0}
.row .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.s{color:var(--mute);font-size:13px}
progress{width:120px}input[type=search]{width:100%;padding:10px 14px;border-radius:999px;border:1px solid #333a48;background:var(--card);color:var(--fg);margin-bottom:6px}
</style></head><body><main>
<h1>Universal Media</h1><p id="dev">Connected over your Wi-Fi. Files go straight between devices.</p>
<div class="drop" id="drop"><b>Send files to the phone</b><br><span class="s">Drop files here, or</span><br><br>
<button id="pick">Choose files</button><input id="file" type="file" multiple hidden></div>
<div id="up"></div>
<h2>On the phone</h2><input type="search" id="q" placeholder="Search">
<div id="list"><p>Loading…</p></div>
</main><script>
const base=location.pathname.endsWith('/')?location.pathname:location.pathname+'/';
const fmt=b=>b<0?'':b<1e6?(b/1e3).toFixed(0)+' KB':b<1e9?(b/1e6).toFixed(1)+' MB':(b/1e9).toFixed(2)+' GB';
let files=[];
function render(){const q=document.getElementById('q').value.toLowerCase();const el=document.getElementById('list');el.innerHTML='';
 const shown=files.filter(f=>f.name.toLowerCase().includes(q));
 if(!shown.length){el.innerHTML='<p>No files.</p>';return}
 shown.forEach(f=>{const r=document.createElement('div');r.className='row';
  const n=document.createElement('div');n.className='n';n.textContent=f.name;
  const s=document.createElement('span');s.className='s';s.textContent=fmt(f.size);
  const a=document.createElement('a');a.className='btn';a.textContent='Download';a.href=base+'f/'+encodeURIComponent(f.id)+'/'+encodeURIComponent(f.name);a.download=f.name;
  r.append(n,s,a);el.append(r)})}
async function load(){try{const r=await fetch(base+'list',{cache:'no-store'});const j=await r.json();files=j.files;
 document.getElementById('dev').textContent='Connected to '+j.device+' over your Wi-Fi. Files go straight between devices.';render()}
 catch(e){document.getElementById('list').innerHTML='<p>The phone stopped sharing. Start Wi-Fi transfer again in the app.</p>'}}
function send(file){const r=document.createElement('div');r.className='row';const n=document.createElement('div');n.className='n';n.textContent=file.name;
 const p=document.createElement('progress');p.max=1;p.value=0;const s=document.createElement('span');s.className='s';s.textContent=fmt(file.size);
 r.append(n,p,s);document.getElementById('up').append(r);
 return new Promise(res=>{const x=new XMLHttpRequest();x.open('PUT',base+'up?name='+encodeURIComponent(file.name)+'&size='+file.size);
  x.upload.onprogress=e=>{if(e.lengthComputable)p.value=e.loaded/e.total};
  x.onload=()=>{s.textContent=x.status==201?'Sent ✓':'Failed: '+x.responseText;p.value=1;res();load()};
  x.onerror=()=>{s.textContent='Failed: connection lost';res()};x.send(file)})}
async function sendAll(list){for(const f of list)await send(f)}
const drop=document.getElementById('drop'),inp=document.getElementById('file');
document.getElementById('pick').onclick=e=>{e.stopPropagation();inp.click()};drop.onclick=()=>inp.click();
inp.onchange=()=>{sendAll([...inp.files]);inp.value=''};
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');sendAll([...e.dataTransfer.files])};
document.getElementById('q').oninput=render;load();
</script></body></html>"""
}
