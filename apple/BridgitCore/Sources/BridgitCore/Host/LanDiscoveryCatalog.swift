import Foundation

/// Fixed Android 3347 discovery catalog; callers cannot supply arbitrary Bonjour queries.
enum LanDiscoveryCatalog {
    struct Service { let type: String; let label: String; let group: String }
    static let batchSize = 6
    static let batchDuration: TimeInterval = 2
    static let services: [Service] = [
        Service(type: "_linkplay._tcp.", label: "WiiM / LinkPlay", group: "Audio & video"),
        Service(type: "_googlecast._tcp.", label: "Google Cast", group: "Audio & video"),
        Service(type: "_airplay._tcp.", label: "AirPlay", group: "Audio & video"),
        Service(type: "_raop._tcp.", label: "AirPlay", group: "Audio & video"),
        Service(type: "_spotify-connect._tcp.", label: "Spotify Connect", group: "Audio & video"),
        Service(type: "_sonos._tcp.", label: "Sonos", group: "Audio & video"),
        Service(type: "_rtsp._tcp.", label: "Camera stream", group: "Cameras"),
        Service(type: "_androidtvremote2._tcp.", label: "Android TV remote", group: "Audio & video"),
        Service(type: "_mediaremotetv._tcp.", label: "Apple TV remote", group: "Audio & video"),
        Service(type: "_ipp._tcp.", label: "Network printing", group: "Printers"),
        Service(type: "_ipps._tcp.", label: "Secure network printing", group: "Printers"),
        Service(type: "_printer._tcp.", label: "Network printing", group: "Printers"),
        Service(type: "_pdl-datastream._tcp.", label: "Direct printing", group: "Printers"),
        Service(type: "_shelly._tcp.", label: "Shelly", group: "Smart home"),
        Service(type: "_hue._tcp.", label: "Philips Hue", group: "Smart home"),
        Service(type: "_hap._tcp.", label: "HomeKit", group: "Smart home"),
        Service(type: "_matter._tcp.", label: "Matter", group: "Smart home"),
        Service(type: "_matterc._udp.", label: "Matter setup", group: "Smart home"),
        Service(type: "_home-assistant._tcp.", label: "Home Assistant", group: "Smart home"),
        Service(type: "_esphomelib._tcp.", label: "ESPHome", group: "Smart home"),
        Service(type: "_wled._tcp.", label: "WLED lighting", group: "Smart home"),
        Service(type: "_mqtt._tcp.", label: "MQTT", group: "Smart home"),
        Service(type: "_smb._tcp.", label: "File sharing", group: "Computers & storage"),
        Service(type: "_adisk._tcp.", label: "Network storage", group: "Computers & storage"),
        Service(type: "_ssh._tcp.", label: "Secure remote access", group: "Computers & storage"),
        Service(type: "_sftp-ssh._tcp.", label: "Secure file transfer", group: "Computers & storage"),
        Service(type: "_rfb._tcp.", label: "Screen sharing", group: "Computers & storage"),
        Service(type: "_rdp._tcp.", label: "Remote desktop", group: "Computers & storage"),
        Service(type: "_http._tcp.", label: "Web service", group: "Web services"),
        Service(type: "_https._tcp.", label: "Secure web service", group: "Web services"),
    ]
}
