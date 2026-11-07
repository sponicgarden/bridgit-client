package cc.bridgit.app

import android.content.Context
import cc.bridgit.app.core.AuthResult
import cc.bridgit.app.core.Connection
import org.json.JSONObject

/** Compatibility view over the production registry: no second key or agent database. */
class Policy(context:Context) {
    private val app=context.applicationContext as BridgeApp
    private val branding=context.getSharedPreferences("platform_branding",Context.MODE_PRIVATE)
    enum class Capability(val key:String) { CAMERA("camera"),MIC("mic"),GALLERY("gallery") }
    data class Agent(val id:String,val name:String,val salt:String,val hash:String,val createdAt:Long,val lastUsedAt:Long,val iconBase64:String?=null,val wordmarkBase64:String?=null)
    private fun adapt(a:cc.bridgit.app.core.Agent)=Agent(a.id,a.name,a.keySalt,a.keyHash,a.createdAt,a.lastUsedAt,branding.getString("icon:${a.id}",null),branding.getString("wordmark:${a.id}",null))
    fun agents()=app.agents.all().map(::adapt)
    fun isEnabled(cap:Capability)=app.settings.isOn(cap.key)
    fun setEnabled(cap:Capability,on:Boolean)=app.settings.setOn(cap.key,on)
    var listenerEnabled:Boolean get()=app.settings.listenerEnabled; set(value) { app.settings.listenerEnabled=value }
    var port:Int get()=app.settings.port; set(value) { app.settings.port=value }
    val hasAgents get()=app.agents.count>0
    fun checkName(name:String)=app.agents.checkName(name)
    fun addAgent(name:String):Pair<Agent,String> { val (a,key)=app.agents.add(name,Connection.DIRECT); return adapt(a) to key }
    fun rotate(id:String):Pair<Agent,String> { val (a,key)=app.agents.rotate(id); app.platform.invalidate(id); return adapt(a) to key }
    fun remove(id:String) { app.agents.remove(id); runCatching { app.captures.forgetAgent(id) }; branding.edit().remove("icon:$id").remove("wordmark:$id").apply(); app.platform.invalidate(id) }
    fun authenticate(key:String?):Agent?=(app.agents.authenticate(key) as? AuthResult.Ok)?.agent?.takeIf { it.connection != Connection.RELAY }?.let(::adapt)
    fun touch(id:String)=app.agents.recordUse(id,"private-platform")
    @Synchronized fun updateProfile(id:String,body:JSONObject):Agent {
        val old=app.agents.get(id) ?: throw cc.bridgit.app.api.ApiError(401,"unauthorized","Agent was removed")
        if(body.keys().asSequence().any { it !in setOf("name","icon_base64","wordmark_base64") }) throw cc.bridgit.app.api.ApiError(422,"invalid_parameter","Unknown profile field")
        val name=if(body.has("name")) (body.opt("name") as? String)?.trim() ?: throw cc.bridgit.app.api.ApiError(422,"invalid_parameter","name must be text") else old.name
        app.agents.checkName(name,id)?.let { throw cc.bridgit.app.api.ApiError(422,"invalid_parameter",it) }
        val icon=AgentBranding.asset(body,"icon_base64",branding.getString("icon:$id",null))
        val wordmark=AgentBranding.asset(body,"wordmark_base64",branding.getString("wordmark:$id",null))
        val updated=app.agents.update(id) { it.copy(name=name) } ?: throw cc.bridgit.app.api.ApiError(401,"unauthorized","Agent was removed")
        branding.edit().putString("icon:$id",icon).putString("wordmark:$id",wordmark).commit()
        return adapt(updated)
    }
    companion object { val NAME_RE=Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$") }
}
fun ByteArray.toHex():String=joinToString("") { "%02x".format(it) }
