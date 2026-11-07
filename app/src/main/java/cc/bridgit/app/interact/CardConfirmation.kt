package cc.bridgit.app.interact

import org.json.JSONObject

/**
 * One event after durable acceptance. Cards can draw their own in-document confirmation.
 * A card the owner closed gets the same panel without the event ([answered] false): nothing was answered.
 */
object CardConfirmation {
    const val EVENT = "bridgit:answered"
    const val CLOSE_DELAY_MS = 5000L
    fun script(answer: JSONObject?, title: String, message: String, closeAfterMs: Long? = null, answered: Boolean = true): String {
        val payload = JSONObject().put("answer", answer ?: JSONObject.NULL).put("close_after_ms", closeAfterMs ?: JSONObject.NULL)
        val event = if (answered) "var event=new CustomEvent('${EVENT}',{cancelable:true,detail:${payload}});\nif(!document.dispatchEvent(event))return;\n" else ""
        return """(function(){
$event
var body=document.body,style=getComputedStyle(body),panel=document.createElement('section');
panel.setAttribute('role','status');panel.setAttribute('aria-live','polite');
panel.style.cssText='position:fixed;inset:0;z-index:2147483647;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:28px;';
var see=function(c){return c&&c!=='rgba(0, 0, 0, 0)'&&c!=='transparent'?c:null;};
var bg=see(style.backgroundColor)||see(getComputedStyle(document.documentElement).backgroundColor);
var rgb=(style.color.match(/\d+/g)||[0,0,0]).map(Number),lightText=(rgb[0]*299+rgb[1]*587+rgb[2]*114)/1000>150;
panel.style.background=bg||(lightText?'#15132a':'#f5f2eb');
panel.style.color=bg?style.color:(lightText?'#f3f0ff':'#1f1b16');panel.style.fontFamily=style.fontFamily;
var icon=document.createElement('div');icon.textContent=${if (answered) "'✓'" else "'×'"};icon.style.cssText='font-size:44px;margin-bottom:16px;';
var heading=document.createElement('h2');heading.textContent=${JSONObject.quote(title)};
var note=document.createElement('p');note.textContent=${JSONObject.quote(message)};
panel.append(icon,heading,note);body.appendChild(panel);
document.querySelectorAll('button,input,select,textarea').forEach(function(e){e.disabled=true;});
})();"""
    }
}
