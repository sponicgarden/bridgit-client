package cc.bridgit.app.platform

import android.content.Context
import android.net.Uri
import android.util.Base64
import cc.bridgit.app.ApiError
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DeviceFiles(private val context:Context,private val store:PlatformStore) {
    private val folder=File(context.filesDir,"agent-files").apply { mkdirs() }
    fun list(agent:String)=JSONObject().put("files",JSONArray(store.list(agent,"file").map { public(it) }))
    fun put(agent:String,args:JSONObject):JSONObject {
        args.only("name","mime","data_base64","note")
        val data=try { Base64.decode(args.text("data_base64",720_000),Base64.NO_WRAP) } catch(e:Exception) { throw ApiError(422,"invalid_parameter","Invalid base64 file") }
        return save(agent,args.text("name",120),args.text("mime",120),data,args.text("note",1024,""))
    }
    private fun save(agent:String,name:String,mime:String,data:ByteArray,note:String):JSONObject {
        if(data.size>512*1024) throw ApiError(413,"payload_too_large","Files are limited to 512 KiB per transfer")
        if(!mime.matches(Regex("(?:image/(?:jpeg|png|webp)|audio/(?:mp4|mpeg|ogg)|application/pdf|text/plain)"))) throw ApiError(422,"unsupported_file","Allowed: images, PDF, plain text and supported audio")
        if(name.isBlank() || name.any { it=='/' || it=='\\' || it.code<32 }) throw ApiError(422,"invalid_parameter","Use a plain file name")
        val existing=store.list(agent,"file")
        if(existing.size>=50 || existing.sumOf { it.optLong("size_bytes") }+data.size>20*1024*1024) throw ApiError(429,"file_quota","Remove old files before sending more")
        val id=UUID.randomUUID().toString(); val file=File(folder,id); file.writeBytes(data)
        try {
            val record=store.save(agent,"file",JSONObject().put("id",id).put("name",name).put("mime",mime).put("size_bytes",data.size).put("note",note).put("status","received"))
            store.emit(agent,"file.received",id); return public(record)
        } catch(e:Exception) { file.delete(); throw e }
    }
    fun import(agent:String,uri:Uri):JSONObject {
        val mime=context.contentResolver.getType(uri) ?: "application/octet-stream"
        var name="Shared file"
        context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) }
        val bytes=context.contentResolver.openInputStream(uri)?.use { cc.bridgit.app.core.Compat.readAtMost(it,512*1024+1) } ?: throw ApiError(404,"file_not_found","Cannot open selected file")
        return save(agent,name.take(120),mime,bytes,"")
    }
    fun get(agent:String,id:String):JSONObject { val record=store.require(id,agent,"file"); return public(record).put("data_base64",Base64.encodeToString(file(agent,id).readBytes(),Base64.NO_WRAP)) }
    fun file(agent:String,id:String):File { store.require(id,agent,"file"); if(!id.matches(Regex("[a-f0-9-]{36}"))) throw ApiError(404,"file_not_found","Invalid file ID"); return File(folder,id).takeIf { it.isFile } ?: throw ApiError(404,"file_not_found","File was removed") }
    fun remove(agent:String,id:String) { file(agent,id).delete(); store.delete(id,agent) }
    fun public(record:JSONObject)=PlatformStore.copy(record).apply { remove("note") }
}
