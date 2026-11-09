import AppKit
final class Receiver: NSObject, NSApplicationDelegate {
 func application(_ application: NSApplication, open urls: [URL]) {
  guard let data = (urls.map(\.absoluteString).joined(separator:"\n") + "\n").data(using:.utf8) else { return }
  let path = URL(fileURLWithPath:"/tmp/br-mac-browser-receiver/received.txt")
  if !FileManager.default.fileExists(atPath:path.path) { FileManager.default.createFile(atPath:path.path,contents:nil) }
  if let file = try? FileHandle(forWritingTo:path) { try? file.seekToEnd(); try? file.write(contentsOf:data); try? file.close() }
  NSApp.terminate(nil)
 }
}
let delegate=Receiver(); let app=NSApplication.shared; app.delegate=delegate; app.run()
