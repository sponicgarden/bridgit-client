package cc.bridgit.app.lan

/** Available app integration, not evidence of enrollment, credentials or a successful command. */
object DeviceInterfaces {
    data class Interface(val adapter: String, val label: String, val setupRequired: Boolean = true)
    fun available(device: NetworkDevices.Device): Interface? = when {
        device.cameraCandidate && "tapo_camera" in device.services -> Interface("tapo_onvif", "Tapo camera")
        device.services.any { it.startsWith("_ssh.") || it.startsWith("_sftp-ssh.") } -> Interface("ssh", "SSH connection")
        else -> null
    }
}
