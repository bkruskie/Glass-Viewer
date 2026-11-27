package app.universalmedia.media.cast

import org.json.JSONObject

/**
 * Shared state for the TV-browser receiver: the phone sets what to play and
 * sends commands; the page at /tv polls once a second and reports its position.
 */
class WebReceiverState {
    @Volatile var url: String? = null
        private set
    @Volatile var title: String = ""
        private set
    @Volatile private var command: String = ""
    @Volatile private var argument: Long = 0
    @Volatile private var sequence: Int = 0

    @Volatile var positionMs: Long = 0
        private set
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var paused: Boolean = true
        private set
    @Volatile var lastReportAt: Long = 0
        private set

    @Synchronized
    fun load(url: String?, title: String) {
        this.url = url
        this.title = title
        positionMs = 0
        durationMs = 0
        send("play", 0)
    }

    @Synchronized
    fun send(cmd: String, arg: Long = 0) {
        command = cmd
        argument = arg
        sequence++
    }

    fun stateJson(): String = JSONObject()
        .put("url", url ?: JSONObject.NULL)
        .put("title", title)
        .put("cmd", command)
        .put("arg", argument)
        .put("seq", sequence)
        .toString()

    fun report(body: String) {
        runCatching {
            val json = JSONObject(body)
            positionMs = json.optLong("p")
            durationMs = json.optLong("d")
            paused = json.optBoolean("paused", true)
            lastReportAt = System.currentTimeMillis()
        }
    }

    companion object {
        /** Written in ES5 with XMLHttpRequest so it runs on older smart-TV browsers. */
        val PAGE = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Universal Media</title>
<style>
 html,body{margin:0;height:100%;background:#000;color:#aab2c2;font-family:sans-serif;overflow:hidden}
 video{position:absolute;top:0;left:0;width:100%;height:100%;background:#000}
 #msg{position:absolute;top:38%;width:100%;text-align:center;font-size:3vw}
 #msg b{color:#8ab4ff}
</style></head>
<body>
<video id="v" playsinline controls></video>
<div id="msg"><b>Universal Media</b><br>Ready. Choose something to play on your phone.</div>
<script>
var v=document.getElementById('v'),msg=document.getElementById('msg'),current=null,seq=null;
function req(method,path,body,cb){var x=new XMLHttpRequest();x.open(method,path+(method==='GET'?'?t='+Date.now():''),true);
 x.onreadystatechange=function(){if(x.readyState===4&&cb){cb(x.status===200?x.responseText:null);}};x.send(body||null);}
function tick(){req('GET','/tv/state',null,function(t){
 if(t){try{var s=JSON.parse(t);
  if(s.url&&s.url!==current){current=s.url;v.src=s.url;msg.style.display='none';try{v.play();}catch(e){}}
  if(!s.url&&current){current=null;v.pause();v.removeAttribute('src');v.load();msg.style.display='block';}
  if(seq===null){seq=s.seq;}else if(s.seq!==seq){seq=s.seq;
   if(s.cmd==='play'){try{v.play();}catch(e){}}else if(s.cmd==='pause'){v.pause();}
   else if(s.cmd==='seek'){v.currentTime=s.arg/1000;}else if(s.cmd==='vol+'){v.volume=Math.min(1,v.volume+0.1);}
   else if(s.cmd==='vol-'){v.volume=Math.max(0,v.volume-0.1);}}
 }catch(e){}}
 req('POST','/tv/status',JSON.stringify({p:Math.floor((v.currentTime||0)*1000),d:isFinite(v.duration)?Math.floor(v.duration*1000):0,paused:v.paused}),null);
 setTimeout(tick,1000);});}
tick();
</script></body></html>
        """.trimIndent()
    }
}
