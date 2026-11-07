package cc.bridgit.app.lancamera

import cc.bridgit.app.dog.PetCameraSearch

/** Tapo omits the status space; only use the selected profile's explicit default in that case. */
object OnvifPosition {
    const val GENERIC="http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace"
    fun parse(x: String, y: String, space: String?, default: String?): PetCameraSearch.Pose? {
        if((space?.takeIf { it.isNotBlank() } ?: default) != GENERIC) return null
        val pan=x.toDoubleOrNull() ?: return null
        val tilt=y.toDoubleOrNull() ?: return null
        return runCatching { PetCameraSearch.Pose(pan,tilt) }.getOrNull()
    }
}
