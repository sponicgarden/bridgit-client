package cc.bridgit.app.judgment

/** Kept small enough for the existing MCP HTML card contract. No story text enters executable HTML. */
object FairytaleImageCard {
    fun html(title: String, jpegBase64: String): String {
        require(Regex("[A-Za-z0-9+/]*={0,2}").matches(jpegBase64))
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        return """<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
        :root{color-scheme:light dark}body{margin:0;background:#211d2a;color:#fff5e2;font:18px system-ui;min-height:100vh;display:flex;flex-direction:column}h1{font:normal 28px Georgia;margin:20px}img{width:100%;flex:1;min-height:0;max-height:78vh;object-fit:contain}button{font:inherit;border:1px solid #ae8baf;border-radius:14px;background:#65466d;color:white;padding:14px 24px;margin:20px;align-self:center}</style>
        <h1>${escape(title.take(160))}</h1><img alt="Your fairytale illustration" src="data:image/jpeg;base64,$jpegBase64"><button id="stop">Stop narration</button><script>
        document.getElementById('stop').onclick=async function(){this.disabled=true;try{var r=await (window.Bridgit||window.Bridget).act({path:'/v1/story/stop',method:'POST',body:{}});if(!r.ok)throw Error();this.textContent='Stopped';}catch(e){this.disabled=false;this.textContent='Try stop again';}};
        </script></html>"""
    }
}
