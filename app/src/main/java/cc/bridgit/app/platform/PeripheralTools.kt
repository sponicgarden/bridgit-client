package cc.bridgit.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.hardware.usb.*
import android.nfc.NfcAdapter
import android.util.Base64
import kotlinx.coroutines.*
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class PeripheralTools(private val app:BridgeApp) {
    fun list():JSONObject {
        val manager=app.getSystemService(BluetoothManager::class.java)
        val usb=app.getSystemService(UsbManager::class.java)
        return JSONObject().put("bluetooth_supported",manager?.adapter!=null).put("bluetooth_permission",app.granted(Manifest.permission.BLUETOOTH_CONNECT))
            .put("bonded",JSONArray(if(app.granted(Manifest.permission.BLUETOOTH_CONNECT)) manager?.adapter?.bondedDevices?.map { JSONObject().put("name",it.name ?: "").put("address",it.address) } ?: emptyList() else emptyList<JSONObject>()))
            .put("usb",JSONArray(usb.deviceList.values.map { d -> JSONObject().put("id",d.deviceName).put("vendor",d.vendorId).put("product",d.productId).put("permission",usb.hasPermission(d)).put("interfaces",d.interfaceCount) }))
            .put("nfc_supported",NfcAdapter.getDefaultAdapter(app)!=null).put("last_owner_nfc",JSONObject(app.platform.store.setting("last_nfc","{}")))
    }
    suspend fun action(agent:String,args:JSONObject):JSONObject {
        app.platform.authorize(agent,"peripherals")
        if(!BuildConfig.PRIVATE_CONTROL) throw ApiError(403,"private_build_required","Private build required")
        return when(args.text("type",32)) { "ble.scan" -> scan(args); "ble.read","ble.write" -> gatt(args); "usb.transfer" -> usb(args); else -> throw ApiError(422,"unsupported_action","Supported: ble.scan, ble.read, ble.write, usb.transfer") }
    }
    private suspend fun scan(args:JSONObject):JSONObject {
        if(!app.granted(Manifest.permission.BLUETOOTH_SCAN)) throw ApiError(403,"bluetooth_permission_required","Grant Nearby devices on the phone")
        val scanner=app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: throw ApiError(409,"bluetooth_unavailable","Turn Bluetooth on")
        val results=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
        val callback=object:ScanCallback() { override fun onScanResult(type:Int,result:ScanResult) { if(results.size<100) results[result.device.address]=JSONObject().put("address",result.device.address).put("name",result.scanRecord?.deviceName ?: "").put("rssi",result.rssi) } }
        try { scanner.startScan(callback); delay(args.whole("seconds",5,1,10)*1000L) } finally { scanner.stopScan(callback) }
        return JSONObject().put("devices",JSONArray(results.values.toList())).put("as_of",System.currentTimeMillis())
    }
    private suspend fun gatt(args:JSONObject):JSONObject {
        if(!app.granted(Manifest.permission.BLUETOOTH_CONNECT)) throw ApiError(403,"bluetooth_permission_required","Grant Nearby devices")
        val adapter=app.getSystemService(BluetoothManager::class.java)?.adapter ?: throw ApiError(409,"bluetooth_unavailable","Bluetooth unavailable")
        val address=args.text("address",17)
        if(!BluetoothAdapter.checkBluetoothAddress(address)) throw ApiError(422,"invalid_parameter","Invalid Bluetooth address")
        val service=runCatching { UUID.fromString(args.text("service_uuid",36)) }.getOrElse { throw ApiError(422,"invalid_parameter","Invalid service UUID") }
        val characteristic=runCatching { UUID.fromString(args.text("characteristic_uuid",36)) }.getOrElse { throw ApiError(422,"invalid_parameter","Invalid characteristic UUID") }
        var connection:BluetoothGatt?=null
        try { return withTimeout(12_000) { suspendCancellableCoroutine { cont ->
            fun fail(message:String) { if(cont.isActive) cont.resumeWith(Result.failure(ApiError(409,"peripheral_failed",message))) }
            val callback=object:BluetoothGattCallback() {
                override fun onConnectionStateChange(g:BluetoothGatt,status:Int,state:Int) { if(status!=BluetoothGatt.GATT_SUCCESS) fail("GATT connection failed") else if(state==BluetoothProfile.STATE_CONNECTED) g.discoverServices() else if(state==BluetoothProfile.STATE_DISCONNECTED) fail("Device disconnected") }
                override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
                    val ch=g.getService(service)?.getCharacteristic(characteristic)
                    if(status!=BluetoothGatt.GATT_SUCCESS || ch==null) { fail("Characteristic unavailable"); return }
                    if(args.optString("type")=="ble.write") {
                        val bytes=runCatching { Base64.decode(args.text("data_base64",4096),Base64.NO_WRAP) }.getOrElse { fail("Invalid bytes"); return }
                        if(bytes.size>512) { fail("BLE writes are limited to 512 bytes"); return }
                        val sent=if(android.os.Build.VERSION.SDK_INT>=33) g.writeCharacteristic(ch,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
                            else @Suppress("DEPRECATION") run { ch.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; ch.value=bytes; g.writeCharacteristic(ch) }
                        if(!sent) fail("Write was not dispatched")
                    } else if(!g.readCharacteristic(ch)) fail("Read was not dispatched")
                }
                override fun onCharacteristicRead(g:BluetoothGatt,ch:BluetoothGattCharacteristic,value:ByteArray,status:Int) { if(status!=BluetoothGatt.GATT_SUCCESS) fail("Read failed") else if(cont.isActive) cont.resume(JSONObject().put("data_base64",Base64.encodeToString(value,Base64.NO_WRAP)).put("outcome","read")) }
                override fun onCharacteristicWrite(g:BluetoothGatt,ch:BluetoothGattCharacteristic,status:Int) { if(status!=BluetoothGatt.GATT_SUCCESS) fail("Write failed") else if(cont.isActive) cont.resume(JSONObject().put("dispatched",true).put("outcome","gatt_acknowledged_effect_unverified")) }
            }
            connection=adapter.getRemoteDevice(address).connectGatt(app,false,callback)
            cont.invokeOnCancellation { connection?.disconnect() }
        } } } finally { connection?.disconnect(); connection?.close() }
    }
    private suspend fun usb(args:JSONObject):JSONObject = withContext(Dispatchers.IO) {
        val manager=app.getSystemService(UsbManager::class.java)
        val device=manager.deviceList[args.text("device_id",256)] ?: throw ApiError(404,"device_not_found","USB device disconnected")
        if(!manager.hasPermission(device)) throw ApiError(403,"usb_permission_required","Grant access to this USB device on the phone")
        if(device.interfaceCount==0) throw ApiError(422,"unsupported_device","USB device has no interfaces")
        val iface=device.getInterface(args.whole("interface",0,0,device.interfaceCount-1))
        if(iface.endpointCount==0) throw ApiError(422,"unsupported_device","USB interface has no endpoints")
        val endpoint=iface.getEndpoint(args.whole("endpoint",0,0,iface.endpointCount-1))
        if(endpoint.type!=UsbConstants.USB_ENDPOINT_XFER_BULK) throw ApiError(422,"unsupported_endpoint","Only bulk endpoints are supported")
        val conn=manager.openDevice(device) ?: throw ApiError(409,"device_unavailable","Cannot open USB device")
        try {
            if(!conn.claimInterface(iface,false)) throw ApiError(409,"device_busy","USB interface is busy")
            try {
                val input=endpoint.direction==UsbConstants.USB_DIR_IN
                val buffer=if(input) ByteArray(args.whole("max_bytes",1024,1,65536)) else try { Base64.decode(args.text("data_base64",90_000),Base64.NO_WRAP).also { if(it.size>65536) throw IllegalArgumentException() } } catch(e:Exception) { throw ApiError(422,"invalid_parameter","Invalid transfer bytes") }
                val count=conn.bulkTransfer(endpoint,buffer,buffer.size,3000)
                if(count<0) throw ApiError(504,"transfer_timeout","USB transfer did not complete")
                JSONObject().put("bytes",count).put("direction",if(input) "in" else "out").put("data_base64",if(input) Base64.encodeToString(buffer.copyOf(count),Base64.NO_WRAP) else JSONObject.NULL).put("outcome",if(input) "read" else "transferred_effect_unverified")
            } finally { conn.releaseInterface(iface) }
        } finally { conn.close() }
    }
}
