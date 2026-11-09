import AVKit
import CoreLocation
import ImageIO
import Speech
import SwiftUI
import UniformTypeIdentifiers
import BridgitAppletRuntime

struct AppletCatalogView: View {
    @EnvironmentObject private var model: AppModel
    @State private var owned = false
    @State private var search = ""
    @State private var query = ""
    @State private var cursors = [""]
    @State private var retry = 0
    @State private var page: CatalogPage?
    @State private var error: String?

    private struct Request: Hashable { let owned: Bool; let query: String; let after: String; let retry: Int }

    var body: some View {
        let request = Request(owned:owned,query: query, after: cursors.last ?? "", retry: retry)
        List {
            Picker("Library",selection:$owned) {
                Text("Public library").tag(false)
                Text("My editions").tag(true)
            }.pickerStyle(.segmented)
            if let error {
                Text(error)
                Button("Try again") { retry += 1 }
            } else if let page {
                if page.applets.isEmpty { Text("No matching applets") }
                ForEach(page.applets, id: \.key) { entry in
                    NavigationLink { AppletReleaseView(entry: entry) } label: {
                        VStack(alignment: .leading) {
                            Text(entry.name)
                            Text(entry.summary).frostText(.supporting).foregroundStyle(.secondary)
                        }
                    }
                }
                if let next = page.next { Button("Next page") { cursors.append(next) } }
            } else { ProgressView("Loading applets") }
            if cursors.count > 1 { Button("Previous page") { cursors.removeLast() } }
        }
        .navigationTitle("Applet library")
        .searchable(text: $search, prompt: "Search applets")
        .onChange(of:owned) { _,_ in cursors = [""] }
        .onSubmit(of: .search) { query = String(search.prefix(100)); cursors = [""] }
        .onChange(of: search) { _, value in
            if value.isEmpty { query = ""; cursors = [""] }
        }
        .task(id: request) {
            page = nil; error = nil
            do {
                let result = try await model.cloud.catalog(query: request.query, after: request.after, owned:request.owned)
                try Task.checkCancellation()
                page = result
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
}

private struct PackageDocument: FileDocument {
    static let readableContentTypes = [UTType.json]
    let data: Data
    init(_ data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

@MainActor
private func recordLocalTranscript(seconds: Int, language: String? = nil) async throws -> String {
    let locale = language.map(Locale.init(identifier:)) ?? .current
    guard let recognizer = SFSpeechRecognizer(locale:locale), recognizer.supportsOnDeviceRecognition else {
        throw AppletRuntimeError.unavailable("On-device speech recognition for this device language")
    }
    let speech = await withCheckedContinuation { continuation in
        SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
    }
    try Task.checkCancellation()
    guard speech == .authorized, await AVAudioApplication.requestRecordPermission() else {
        throw AppletRuntimeError.unavailable("Microphone and speech recognition permissions are required")
    }
    try Task.checkCancellation()
    guard UIApplication.shared.applicationState == .active else { throw CancellationError() }
    let session = AVAudioSession.sharedInstance()
    let previousCategory = session.category, previousMode = session.mode, previousOptions = session.categoryOptions
    try session.setCategory(.record,mode:.measurement)
    defer { try? session.setCategory(previousCategory,mode:previousMode,options:previousOptions) }
    try session.setActive(true)
    defer { try? session.setActive(false,options:.notifyOthersOnDeactivation) }

    let engine = AVAudioEngine()
    let input = engine.inputNode
    let format = input.outputFormat(forBus:0)
    guard format.sampleRate > 0, format.channelCount > 0 else { throw AppletRuntimeError.unavailable("Microphone input") }
    let request = SFSpeechAudioBufferRecognitionRequest()
    request.requiresOnDeviceRecognition = true
    request.shouldReportPartialResults = false
    input.installTap(onBus:0,bufferSize:1024,format:format) { buffer,_ in request.append(buffer) }
    defer { engine.stop(); input.removeTap(onBus:0) }

    let (results, output) = AsyncThrowingStream<String, Error>.makeStream()
    let recognition = recognizer.recognitionTask(with:request) { result,error in
        if let error { output.finish(throwing:error) }
        else if let result, result.isFinal {
            output.yield(result.bestTranscription.formattedString)
            output.finish()
        }
    }
    defer { recognition.cancel() }
    try engine.start()
    let deadline = Task { @MainActor in
        try await Task.sleep(for:.seconds(seconds))
        engine.stop()
        request.endAudio()
        try await Task.sleep(for:.seconds(10))
        output.finish(throwing:AppletRuntimeError.unavailable("On-device transcription timed out"))
    }
    defer { deadline.cancel() }
    for try await text in results {
        try Task.checkCancellation()
        return text
    }
    throw CancellationError()
}

private struct AppletReleaseView: View {
    @EnvironmentObject private var model: AppModel
    let entry: CatalogEntry
    @State private var release: RegistryPackage?
    @State private var monitor: (package: AppletPackage, data: Data)?
    @State private var exporting = false
    @State private var installed = false
    @State private var existing: LocalAppletLibrary.Edition?
    @State private var update: LocalAppletLibrary.Update?
    @State private var confirmUpdate = false
    @State private var installing = false
    @State private var error: String?
    @State private var retry = 0

    var body: some View {
        List {
            Text(entry.summary)
            LabeledContent("Registry revision", value: String(entry.revision))
            if let url = entry.installReviewURL {
                Section {
                    ShareLink("Share Android install link", item: url)
                } footer: {
                    Text("Open this revision on the monitoring phone to review its permissions and choose whether to install it.")
                }
            }
            if let release {
                if let bytes = release.package.iconPNG, let icon = UIImage(data: bytes) {
                    Image(uiImage: icon).resizable().scaledToFit().frame(width: 64, height: 64)
                }
                LabeledContent("Version", value: release.package.version)
                if !release.package.media.isEmpty {
                    Section("Images to download") {
                        ForEach(release.package.media.keys.sorted(),id:\.self) { name in
                            if let media = release.package.media[name] {
                                LabeledContent(name,value:ByteCountFormatter.string(fromByteCount:Int64(media.size),countStyle:.file))
                            }
                        }
                    }
                }
                if case .array(let scopes)? = release.package.definition["permissions"]?["required"], !scopes.isEmpty {
                    Section("Requested permissions") {
                        ForEach(scopes.compactMap(\.text), id: \.self) { Text($0) }
                    }
                }
                if !release.package.flows.isEmpty {
                    Section("Flows") { ForEach(release.package.flows.keys.sorted(), id: \.self) { Text($0) } }
                }
                if let existing { LabeledContent("Installed revision",value:String(existing.revision)) }
                if installed {
                    NavigationLink("Open local library") { LocalAppletsView() }
                } else {
                    Button(installing ? "Checking installation…" : (existing == nil ? "Install on this device" : "Review update")) {
                        installing = true
                        Task {
                            defer { installing = false }
                            do {
                                let library = try await model.localApplets()
                                if let current = try await library.installed(entry.key), current.revision != entry.revision {
                                    existing = current
                                    update = try await model.cloud.reviewUpdate(to:release,from:current,in:library)
                                    confirmUpdate = true
                                } else {
                                    let files = try await model.cloud.packageMedia(release)
                                    try await library.install(release,media:files)
                                    installed = true
                                }
                                error = nil
                            } catch { self.error = error.localizedDescription }
                        }
                    }
                    .disabled(installing)
                }
                Button("Save package…") { exporting = true }
            } else if let monitor {
                LabeledContent("Version", value: monitor.package.version)
                Section("Monitoring device") {
                    Text("This edition runs on an Android phone. Share the install link with that phone to review its permissions and classifier.")
                }
                if case .array(let scopes)? = monitor.package.definition["permissions"]?["required"] {
                    Section("Requested permissions") { Text(scopes.compactMap(\.text).joined(separator: ", ")) }
                }
                if case .array(let remote)? = monitor.package.definition["permissions"]?["remote"] {
                    Section("Remote access") { Text(remote.compactMap(\.text).joined(separator: ", ")) }
                }
                if let questions = monitor.package.definition["processing"]?["classifier"]?["questions"]?.fields {
                    DisclosureGroup("Camera classifier questions") {
                        ForEach(questions.keys.sorted(), id: \.self) { key in
                            if let prompt = questions[key]?["instructions"]?.text { Text(prompt) }
                        }
                    }
                }
                if model.applets.contains(where: { $0.applet == "dog" && $0.isOwner }) {
                    Section("Existing monitors on your account") {
                        ForEach(model.applets.filter { $0.applet == "dog" && $0.isOwner }) { applet in
                            NavigationLink {
                                AppletDetailView(applet: applet)
                            } label: {
                                Text("Open monitor on " + (applet.name ?? applet.phone))
                            }
                        }
                    }
                }
                Button("Save package…") { exporting = true }
            } else if error == nil { ProgressView("Reading release") }
            if let error {
                Text(error)
                if release == nil { Button("Try again") { retry += 1 } }
            }
        }
        .navigationTitle(entry.name)
        .task(id: retry) {
            error = nil
            do {
                let reviewed = try await model.cloud.catalogRelease(entry)
                try Task.checkCancellation()
                let selected: RegistryPackage
                switch reviewed {
                case .androidMonitor(let package, let data):
                    monitor = (package, data)
                    return
                case .local(let local): selected = local
                }
                release = selected
                let library = try await model.localApplets()
                existing = try await library.installed(entry.key)
                if existing?.revision == entry.revision {
                    try await library.install(selected) // verify the exact bytes before offering Open
                    installed = true
                }
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
        .alert("Apply reviewed applet update?", isPresented: $confirmUpdate, presenting: update) { plan in
            Button("Update") {
                installing = true
                Task {
                    defer { installing = false }
                    do {
                        let library = try await model.localApplets()
                        let files = try await model.cloud.packageMedia(plan.release)
                        try await library.apply(plan,media:files)
                        existing = try await library.installed(entry.key)
                        installed = existing?.revision == entry.revision
                        error = nil
                    } catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { plan in
            let permissions = plan.newPermissions.isEmpty ? "None" : plan.newPermissions.joined(separator: ", ")
            let remote = plan.remoteDisclosures.isEmpty ? "None" : plan.remoteDisclosures.joined(separator: ", ")
            let storage = plan.addedStorage.isEmpty ? "" : "\nAdded tables or columns: " + plan.addedStorage.joined(separator: ", ")
            let remaining = plan.targetRevision < entry.revision ? "\nReview the next update after applying this prerequisite." : ""
            Text("Revision \(plan.from.revision) → \(plan.targetRevision) (version \(plan.targetVersion))\(remaining)\nAdditional permissions: \(permissions)\nRemote services: \(remote)\(storage)")
        }
        .fileExporter(isPresented: $exporting, document: (release?.data ?? monitor?.data).map(PackageDocument.init(_:)),
                      contentType: .json, defaultFilename: entry.id + "-r" + String(entry.revision)) { result in
            if case .failure(let failure) = result { error = failure.localizedDescription }
        }
    }
}

struct LocalAppletsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var editions = [LocalAppletLibrary.Edition]()
    @State private var library: LocalAppletLibrary?
    @State private var error: String?
    @State private var retry = 0
    @State private var removal: LocalAppletLibrary.Edition?

    var body: some View {
        List {
            if let library {
                if editions.isEmpty { Text("No applets installed on this device") }
                ForEach(editions) { edition in
                    NavigationLink { LocalEditionView(edition: edition, library: library) } label: {
                        VStack(alignment: .leading) {
                            Text(edition.name)
                            Text("Revision " + String(edition.revision)).frostText(.supporting)
                        }
                    }
                    .swipeActions { Button("Remove", role: .destructive) { removal = edition } }
                }
            } else if error == nil { ProgressView("Reading installed applets") }
            if let error { Text(error); Button("Try again") { retry += 1 } }
        }
        .navigationTitle("On this device")
        .alert("Remove this local applet?", isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } }), presenting: removal) { edition in
            Button("Remove", role: .destructive) {
                Task {
                    do { try await library?.remove(edition); retry += 1 }
                    catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .task(id: retry) {
            error = nil
            do {
                let store = try await model.localApplets()
                let rows = try await store.editions()
                try Task.checkCancellation()
                library = store; editions = rows
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
}

/// One open card owns the capture IDs, in-memory JPEGs and temporary movie files.
@MainActor
final class CardCaptureMemory: ObservableObject {
    private var images = [String:Data]()
    private var movies = [String:CardMovieFile]()
    private let limit = 24

    func store(_ photo: LocalAppletLibrary.ReviewedPhoto) throws -> JSONValue {
        guard images.count < limit, !photo.jpeg.isEmpty, photo.jpeg.count <= 2_000_000 else {
            throw AppletRuntimeError.invalid("Card capture limit reached")
        }
        let id = UUID().uuidString.lowercased()
        images[id] = photo.jpeg
        return .object(["camera":.string(photo.camera),"capture":.object(["capture_id":.string(id)])])
    }

    func storeVideo(_ movie: CardMovieFile, camera: String, frames: [Data]) throws -> JSONValue {
        guard movies.count < 2, (1...8).contains(frames.count), images.count + frames.count <= limit,
              frames.allSatisfy({ !$0.isEmpty && $0.count <= 2_000_000 }) else {
            throw AppletRuntimeError.invalid("Card capture limit reached")
        }
        let videoID = UUID().uuidString.lowercased()
        movies[videoID] = movie
        let refs = frames.map { jpeg -> JSONValue in
            let id = UUID().uuidString.lowercased()
            images[id] = jpeg
            return .object(["capture_id":.string(id)])
        }
        return .object(["video_capture_id":.string(videoID),"video_saved":.bool(true),"frames":.array(refs),
                        "count":.number(Double(frames.count)),"camera":.string(camera)])
    }

    func jpeg(_ id: String) throws -> Data {
        guard let data = images[id] else { throw AppletRuntimeError.invalid("Capture unavailable") }
        return data
    }

    func movie(_ id: String) throws -> CardMovieFile {
        guard let movie = movies[id] else { throw AppletRuntimeError.invalid("Video unavailable") }
        return movie
    }

    func readOrDelete(_ request: JSONValue, id: String) throws -> JSONValue {
        if request["method"] == .string("DELETE") {
            let removed = movies.removeValue(forKey:id) != nil || images.removeValue(forKey:id) != nil
            return .object(["deleted":.bool(removed)])
        }
        guard let jpeg = images[id] else { throw AppletRuntimeError.invalid("Capture unavailable") }
        guard (request["method"] ?? .string("GET")) == .string("GET"),
              request["query"]?.fields?.keys.allSatisfy({ $0 == "max_edge_px" }) ?? true,
              case .number(let edge) = request["query"]?["max_edge_px"] ?? .number(960),
              (64...1280).contains(edge), edge.rounded() == edge,
              let image = UIImage(data:jpeg), let bytes = OwnerPhotos.shrink(image,maxEdge:CGFloat(edge)) else {
            throw AppletRuntimeError.invalid("Invalid capture preview")
        }
        return .object(["data_base64":.string(bytes.base64EncodedString()),"mime_type":.string("image/jpeg")])
    }

    func clear() { images.removeAll(); movies.removeAll() }
}

/// A single, owner-approved foreground reading. The manager and delegate expire with this request.
@MainActor
final class CardLocation: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    private var manager: CLLocationManager?
    private var reply: CheckedContinuation<JSONValue,Error>?
    private var timeout: Task<Void,Never>?
    private var fresh = true
    private var awaitingAuthorization = false
    private var requestedFix = false

    func read(fresh: Bool, balanced: Bool) async throws -> JSONValue {
        guard reply == nil else { throw AppletRuntimeError.invalid("A location request is already open") }
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in
            let manager = CLLocationManager()
            reply = continuation; self.manager = manager; self.fresh = fresh
            awaitingAuthorization = false; requestedFix = false
            manager.desiredAccuracy = balanced ? kCLLocationAccuracyHundredMeters : kCLLocationAccuracyBest
            manager.delegate = self
            timeout = Task {
                do { try await Task.sleep(for:.seconds(30)) } catch { return }
                finish(.failure(AppletRuntimeError.invalid("Location request timed out")))
            }
            authorize(manager)
        }
    }

    private func authorize(_ source: CLLocationManager) {
        guard source === manager else { return }
        switch source.authorizationStatus {
        case .notDetermined:
            if !awaitingAuthorization { awaitingAuthorization = true; source.requestWhenInUseAuthorization() }
        case .authorizedAlways,.authorizedWhenInUse:
            guard !requestedFix else { return }
            requestedFix = true
            if fresh { source.requestLocation() }
            else if let last = source.location { deliver(last,from:source) }
            else { finish(.failure(AppletRuntimeError.invalid("No cached location available"))) }
        default: finish(.failure(AppletRuntimeError.invalid("Location access is off for Bridgit")))
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { authorize(manager) }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard manager === self.manager, let fix = locations.last else { return }
        deliver(fix,from:manager)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if manager === self.manager { finish(.failure(error)) }
    }

    func cancel() { finish(.failure(CancellationError())) }

    private func finish(_ result: Result<JSONValue,Error>) {
        let pending = reply
        reply = nil
        manager?.delegate = nil; manager?.stopUpdatingLocation(); manager = nil
        timeout?.cancel(); timeout = nil
        pending?.resume(with:result)
    }

    private func deliver(_ fix: CLLocation, from manager: CLLocationManager) {
        do { finish(.success(try Self.response(fix,approximate:manager.accuracyAuthorization == .reducedAccuracy))) }
        catch { finish(.failure(error)) }
    }

    static func response(_ fix: CLLocation, approximate: Bool, now: Date = Date()) throws -> JSONValue {
        guard CLLocationCoordinate2DIsValid(fix.coordinate), fix.horizontalAccuracy >= 0,
              fix.horizontalAccuracy.isFinite else { throw AppletRuntimeError.invalid("Invalid location reading") }
        func optional(_ value: Double) -> JSONValue { value.isFinite && value >= 0 ? .number(value) : .null }
        return .object(["latitude":.number(fix.coordinate.latitude),"longitude":.number(fix.coordinate.longitude),
                        "altitude_m":fix.verticalAccuracy >= 0 && fix.altitude.isFinite ? .number(fix.altitude) : .null,
                        "accuracy_m":.number(fix.horizontalAccuracy),"vertical_accuracy_m":optional(fix.verticalAccuracy),
                        "speed_mps":optional(fix.speed),"bearing_deg":optional(fix.course),"provider":.string("corelocation"),
                        "precision":.string(approximate ? "approximate" : "precise"),
                        "fix_time":.string(ISO8601DateFormatter().string(from:fix.timestamp)),
                        "age_s":.number(max(0,floor(now.timeIntervalSince(fix.timestamp))))])
    }
}

/// A package can play a literal public Bridgit audio asset. Never attach the owner's session.
final class CardAudioAsset: NSObject, URLSessionTaskDelegate {
    static func url(_ value: String?, html: String) throws -> URL {
        guard let value, value.range(of:#"\Ahttps://bridgit\.cc/assets/audio/[a-z0-9_-]{1,100}\.mp3\z"#,options:.regularExpression) != nil,
              ["'","\""].contains(where:{ html.contains($0 + value + $0) }), let url = URL(string:value) else {
            throw AppletRuntimeError.invalid("Choose an audio asset referenced by this applet")
        }
        return url
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }

    func load(_ url: URL) async throws -> Data {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil
        config.timeoutIntervalForResource = 30
        let session = URLSession(configuration:config,delegate:self,delegateQueue:nil)
        defer { session.invalidateAndCancel() }
        let (bytes,response) = try await session.bytes(from:url)
        guard (response as? HTTPURLResponse)?.statusCode == 200, response.mimeType == "audio/mpeg",
              response.expectedContentLength <= 4_000_000 else { throw AppletRuntimeError.invalid("Audio asset unavailable") }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 4_000_000 else { throw AppletRuntimeError.invalid("Audio asset too large") }
            data.append(byte)
        }
        return data
    }
}

@MainActor
final class CardSpeaker: NSObject, ObservableObject, @preconcurrency AVAudioPlayerDelegate {
    private var playing: AVAudioPlayer?
    private var queued = [AVAudioPlayer]()
    var status: JSONValue {
        .object(["playing":.bool(playing != nil),"queued":.array(queued.map { _ in .bool(true) })])
    }
    func play(_ data: Data, replace: Bool) throws {
        if replace { stop() }
        guard queued.count < 3 else { throw AppletRuntimeError.invalid("Speaker queue is full") }
        let player = try AVAudioPlayer(data:data)
        player.delegate = self
        if playing == nil {
            guard player.play() else { throw AppletRuntimeError.invalid("Audio playback unavailable") }
            playing = player
        } else { queued.append(player) }
    }
    func stop() {
        playing?.stop(); playing = nil; queued.removeAll()
    }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard playing === player else { return }
        playing = nil
        while !queued.isEmpty {
            let next = queued.removeFirst()
            if next.play() { playing = next; break }
        }
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        audioPlayerDidFinishPlaying(player,successfully:false)
    }
}

struct CardIllustration: Identifiable {
    let id = UUID()
    let image: UIImage
    let title: String
    let receipt: JSONValue

    init(response: JSONValue,title: String) throws {
        guard let task = response["task_key"], let model = response["model"], let quota = response["quota"],
              case .array(let images)? = response["data"], images.count == 1,
              let encoded = images[0]["b64_json"]?.text, encoded.utf8.count <= 16_000_000,
              let bytes = Data(base64Encoded:encoded), let source = CGImageSourceCreateWithData(bytes as CFData,nil),
              CGImageSourceGetType(source) == UTType.jpeg.identifier as CFString,
              let properties = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int, (1...4096).contains(width),
              let height = properties[kCGImagePropertyPixelHeight] as? Int, (1...4096).contains(height),
              let thumb = CGImageSourceCreateThumbnailAtIndex(source,0,[
                kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:1536
              ] as CFDictionary) else { throw AppletRuntimeError.invalid("Invalid illustration response") }
        self.title = title
        image = UIImage(cgImage:thumb)
        receipt = .object(["interaction_id":.string(id.uuidString),"task_key":task,"model":model,"quota":quota,
                           "display":.object(["shown_as":.string("full_screen"),"full_screen":.bool(true)])])
    }
}

private struct InstalledHTMLCard: View {
    @EnvironmentObject private var model: AppModel
    let html: String
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    let session: UUID
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var location = CardLocation()
    private struct Approval: Identifiable {
        let id = UUID()
        let message: String
        let result: AsyncStream<Bool>.Continuation
    }
    @State private var approvals = [Approval]()
    private var locationBusy: Bool { !approvals.isEmpty }
    @State private var artwork: CardIllustration?
    @StateObject private var speaker = CardSpeaker()
    @State private var narrationVoice: String?
    @State private var spokenCharacters = 0
    @State private var speaking = false
    @State private var storyTask: Task<JSONValue,Error>?
    @State private var presentation: String?
    @State private var foregroundGeneration = UUID()
    @State private var readingLocation = false
    @StateObject private var captures = CardCaptureMemory()
    private struct Prompt: Identifiable {
        let id = UUID()
        let lens: String
        let countdown: Int
        let edge: CGFloat
        let quality: CGFloat
        let result: AsyncStream<LocalAppletLibrary.ReviewedPhoto>.Continuation
    }
    private struct MoviePrompt: Identifiable {
        enum Mode {
            case record(String,Int,Int)
            case review(CardMovieFile)
        }
        enum Answer {
            case captured(CardMovieFile,String)
            case reviewed(delete:Bool)
        }
        let id = UUID()
        let mode: Mode
        let result: AsyncStream<Answer>.Continuation
    }
    @State private var moviePrompt: MoviePrompt?
    @State private var prompt: Prompt?
    @State private var visible = false

    var body: some View {
        CardWebView(html:html,installedAction:act)
            .onAppear { visible = true }
            .onDisappear { visible = false; cancelLocation(); prompt?.result.finish(); moviePrompt?.result.finish(); captures.clear() }
            .onChange(of:scenePhase) { _, phase in if phase == .background { cancelLocation() } }
            .overlay {
                if let artwork {
                    VStack {
                        Text(artwork.title).frostText(.label, weight: .semibold)
                        Image(uiImage:artwork.image).resizable().scaledToFit().frame(maxHeight:.infinity)
                        Button("Close illustration") { self.artwork = nil }
                    }
                    .padding().frame(maxWidth:.infinity,maxHeight:.infinity).background(.background)
                    .accessibilityAddTraits(.isModal)
                }
            }
            .toolbar(artwork == nil ? .automatic : .hidden,for:.navigationBar,.tabBar)
            .sheet(isPresented:Binding(get:{ !approvals.isEmpty },set:{ _ in })) {
                if let first = approvals.first {
                    VStack(spacing:18) {
                        Text(edition.name).frostText(.label, weight: .semibold)
                        Text(first.message)
                        Button("Allow once") { answer(first,true) }.buttonStyle(.borderedProminent)
                        Button("Cancel",role:.cancel) { answer(first,false) }
                    }
                    .padding().id(first.id).presentationDetents([.medium]).interactiveDismissDisabled()
                }
            }
            .sheet(item:$prompt) { item in
                AppletCameraSheet(requested:item.lens,size:nil,countdown:item.countdown,outputEdge:item.edge,
                                  outputQuality:item.quality,accept:{ item.result.yield($0); item.result.finish() },
                                  cancel:{ item.result.finish() })
                .interactiveDismissDisabled()
                .onDisappear { item.result.finish() }
            }
            .sheet(item:$moviePrompt) { item in
                Group {
                    switch item.mode {
                    case .record(let lens,let seconds,let countdown):
                        AppletVideoSheet(lens:lens,seconds:seconds,countdown:countdown,accept:{ movie,camera in
                            item.result.yield(.captured(movie,camera)); item.result.finish()
                        },cancel:{ item.result.finish() })
                    case .review(let movie):
                        CardMovieReview(file:movie) { delete in
                            item.result.yield(.reviewed(delete:delete)); item.result.finish()
                        }
                    }
                }
                .interactiveDismissDisabled()
                .onDisappear { item.result.finish() }
            }
    }

    @MainActor private func checkSession() throws {
        guard visible, model.librarySessionGeneration == session else { throw AppletRuntimeError.revoked }
    }

    @MainActor private func cancelLocation() {
        storyTask?.cancel(); speaker.stop(); narrationVoice = nil; spokenCharacters = 0
        presentation = nil; artwork = nil; foregroundGeneration = UUID(); location.cancel()
        for pending in approvals { pending.result.finish() }
        approvals.removeAll()
    }

    @MainActor private func readLocation(_ request: JSONValue) async throws -> JSONValue {
        let package = try await library.open(edition).package
        try checkSession()
        guard scenePhase == .active, !readingLocation, !locationBusy, prompt == nil, moviePrompt == nil, package.cardHTML != nil,
              case .array(let grants)? = package.definition["permissions"]?["required"], grants.contains(.string("location")),
              (request["method"] ?? .string("GET")) == .string("GET"), request["body"] == nil else {
            throw AppletRuntimeError.unavailable("location")
        }
        let query = request["query"]?.fields ?? [:]
        guard request["query"] == nil || request["query"]?.fields != nil,
              query.keys.allSatisfy({ ["fresh","accuracy","compass"].contains($0) }),
              [.string("high"),.string("balanced")].contains(query["accuracy"] ?? .string("high")),
              [.bool(false),.string("false"),.string("0")].contains(query["compass"] ?? .bool(false)) else {
            throw AppletRuntimeError.invalid("Unsupported location options")
        }
        let fresh: Bool
        switch query["fresh"] {
        case nil,.bool(true),.string("1"),.string("true"): fresh = true
        case .bool(false),.string("0"),.string("false"): fresh = false
        default: throw AppletRuntimeError.invalid("Invalid fresh option")
        }
        readingLocation = true
        defer { readingLocation = false }
        try await approve("Give this applet one location reading from this device.")
        let fix = try await location.read(fresh:fresh,balanced:query["accuracy"] == .string("balanced"))
        _ = try await library.open(edition)
        try checkSession()
        guard scenePhase != .background else { throw CancellationError() }
        return fix
    }

    @MainActor private func answer(_ pending: Approval, _ allowed: Bool) {
        guard approvals.first?.id == pending.id else { return }
        approvals.removeFirst()
        pending.result.yield(allowed); pending.result.finish()
    }

    @MainActor private func approve(_ message: String) async throws {
        try checkSession()
        guard approvals.count < 4, prompt == nil, moviePrompt == nil, scenePhase == .active else { throw CancellationError() }
        let (stream,result) = AsyncStream<Bool>.makeStream(bufferingPolicy:.bufferingOldest(1))
        let pending = Approval(message:message,result:result)
        approvals.append(pending)
        defer { result.finish(); approvals.removeAll { $0.id == pending.id } }
        for await allowed in stream where allowed {
            try checkSession()
            guard scenePhase == .active else { throw CancellationError() }
            return
        }
        throw CancellationError()
    }

    @MainActor private func lookup(_ decision: LocalAppletLibrary.Decision, reserve: @Sendable () async throws -> Void) async throws -> JSONValue {
        guard !readingLocation else { throw CancellationError() }
        let foreground = foregroundGeneration
        if decision.service == .story {
            try await approve("Send the reviewed video frames, portrait and supplied context to GLM 5.3 Flash via OpenRouter? The two story-generation steps use this applet’s budget.")
        } else {
            let provider = decision.flow == "places" ? "Google Places" : "Google Air Quality"
            try await approve("Send the requested coordinates to " + provider + " for one paid lookup using this applet’s budget?")
        }
        let result = try await model.cloud.decide(decision) {
            try await reserve()
            try await MainActor.run {
                try checkSession()
                guard scenePhase == .active, foregroundGeneration == foreground else { throw CancellationError() }
            }
        }
        try checkSession()
        guard foregroundGeneration == foreground else { throw CancellationError() }
        return result
    }

    @MainActor private func speech(_ request: JSONValue) async throws -> JSONValue {
        let foreground = foregroundGeneration
        let token = presentation
        guard token != nil, !speaking, scenePhase == .active else { throw AppletRuntimeError.unavailable("active narration") }
        speaking = true
        defer { speaking = false }
        let synthesis = request["path"] == .string("/v1/speech/speak")
        if synthesis {
            guard token == request["body"]?["presentation_token"]?.text,
                  let text = request["body"]?["text"]?.text, spokenCharacters + text.utf16.count <= 3200 else {
                throw AppletRuntimeError.invalid("Narration exceeded this presentation")
            }
        }
        let response = try await library.cardSpeech(request,in:edition) { decision,reserve in
            try await submitSpeech(decision,token:token,foreground:foreground,reserve:reserve)
        }
        try checkSession()
        guard token == presentation, foreground == foregroundGeneration else { throw CancellationError() }
        if !synthesis { return response }
        guard let encoded = response["audio_base64"]?.text, encoded.utf8.count <= 2_666_668,
              let bytes = Data(base64Encoded:encoded), !bytes.isEmpty,
              response["model"] == request["body"]?["expected_model"],
              [JSONValue.string("audio/mpeg"),.string("audio/wav")].contains(response["content_type"] ?? .null)
        else { throw AppletRuntimeError.invalid("Invalid narrated audio") }
        try speaker.play(bytes,replace:false)
        return .object(["status":.string("queued")])
    }

    @MainActor private func submitSpeech(_ decision: LocalAppletLibrary.Decision, token: String?, foreground: UUID,
                                         reserve: @Sendable () async throws -> Void) async throws -> JSONValue {
        if decision.service == .speak, let voice = decision.options["expected_model"]?.text, narrationVoice != voice {
            try await approve("Send up to 3,200 characters from this reading to Cloudflare for narration with " + voice + "? This uses the applet’s budget.")
            guard token == presentation else { throw CancellationError() }
            narrationVoice = voice
        }
        return try await model.cloud.decide(decision) {
            try await reserve()
            try await MainActor.run {
                try checkSession()
                guard scenePhase == .active, foregroundGeneration == foreground, token == presentation else { throw CancellationError() }
                if decision.service == .speak { spokenCharacters += decision.options["text"]?.text?.utf16.count ?? 0 }
            }
        }
    }

    @MainActor private func illustrate(_ decision: LocalAppletLibrary.Decision,token: String,
                                        reserve: @Sendable () async throws -> Void) async throws -> JSONValue {
        try await approve("Send this story to Azure for one illustration? It uses this applet’s budget and your monthly image allowance.")
        return try await model.cloud.decide(decision) {
            try await reserve()
            try await MainActor.run {
                try checkSession()
                guard scenePhase == .active, token == presentation else { throw CancellationError() }
            }
        }
    }

    @MainActor private func act(_ request: JSONValue) async throws -> JSONValue {
        try checkSession()
        let path = request["path"]?.text ?? ""
        if path == "/v1/story/stop", request["method"] == .string("POST") {
            cancelLocation()
            prompt?.result.finish(); moviePrompt?.result.finish()
            return .object(["stopped":.bool(true)])
        }
        if path == "/v1/story/image" {
            guard let token = presentation, token == request["body"]?["presentation_token"]?.text else { throw CancellationError() }
            let response = try await library.cardIllustration(request,in:edition) { decision,reserve in
                try await illustrate(decision,token:token,reserve:reserve)
            }
            try checkSession()
            guard token == presentation, scenePhase == .active else { throw CancellationError() }
            let illustration = try CardIllustration(response:response,title:request["body"]?["title"]?.text ?? edition.name)
            artwork = illustration
            return illustration.receipt
        }
        if path == "/v1/story/presentation", request["method"] == .string("POST") {
            let package = try await library.open(edition).package
            guard case .array(let grants)? = package.definition["permissions"]?["required"], grants.contains(.string("speaker")) else {
                throw AppletRuntimeError.unavailable("speaker")
            }
            let token = UUID().uuidString
            speaker.stop(); narrationVoice = nil; spokenCharacters = 0; artwork = nil; presentation = token
            return .object(["presentation_token":.string(token)])
        }
        if path == "/v1/speaker", (request["method"] ?? .string("GET")) == .string("GET") { return speaker.status }
        if path == "/v1/speech/models" || path == "/v1/speech/speak" {
            return try await speech(request)
        }
        if path == "/v1/speaker/play" {
            let package = try await library.open(edition).package
            guard request["method"] == .string("POST"), let body = request["body"]?.fields, body.count == 1,
                  case .array(let grants)? = package.definition["permissions"]?["required"], grants.contains(.string("speaker")),
                  let card = package.cardHTML else { throw AppletRuntimeError.unavailable("speaker") }
            let url = try CardAudioAsset.url(body["url"]?.text,html:card)
            let foreground = foregroundGeneration
            let data = try await CardAudioAsset().load(url)
            _ = try await library.open(edition)
            try checkSession()
            guard scenePhase == .active, foregroundGeneration == foreground else { throw CancellationError() }
            try speaker.play(data,replace:true)
            return .object(["status":.string("playing")])
        }
        if path == "/v1/story/generate" {
            guard storyTask == nil else { throw AppletRuntimeError.invalid("Story generation is already running") }
            let token = UUID().uuidString
            narrationVoice = nil; spokenCharacters = 0; artwork = nil; presentation = token
            let task = Task {
                try await library.cardStory(request,in:edition,capture:{ id in
                    try await MainActor.run { try captures.jpeg(id) }
                }) { decision,reserve in try await lookup(decision,reserve:reserve) }
            }
            storyTask = task
            defer { storyTask = nil }
            let result = try await task.value
            try checkSession()
            guard presentation == token, var fields = result.fields else { throw CancellationError() }
            fields["presentation_token"] = .string(token)
            return .object(fields)
        }
        if path == "/v1/services/context" {
            return try await library.cardContext(request,in:edition) { try await lookup($0,reserve:$1) }
        }
        if path == "/v1/location" { return try await readLocation(request) }
        if ["/v1/camera/snapshot","/v1/camera/video","/v1/camera/video/review"].contains(path) || path.hasPrefix("/v1/captures/") {
            let package = try await library.open(edition).package
            try checkSession()
            guard package.cardHTML != nil, case .array(let grants)? = package.definition["permissions"]?["required"],
                  grants.contains(.string("captures")) else { throw AppletRuntimeError.unavailable("captures") }
            if path.hasPrefix("/v1/captures/") {
                return try captures.readOrDelete(request,id:String(path.dropFirst("/v1/captures/".count)))
            }
            guard !locationBusy, !readingLocation, prompt == nil, moviePrompt == nil, request["method"] == .string("POST") else {
                throw AppletRuntimeError.invalid("Camera unavailable or another review is open")
            }
            if path != "/v1/camera/snapshot" {
                guard grants.contains(.string("video")), grants.contains(.string("camera")) else {
                    throw AppletRuntimeError.unavailable("video")
                }
                return try await video(request,review:path.hasSuffix("/review"))
            }
            guard grants.contains(.string("camera")),
                  let body = request["body"]?.fields, body.keys.allSatisfy({
                      ["camera","viewfinder","countdown_seconds","max_edge_px","quality","store","return_image"].contains($0)
                  }), let lens = body["camera"]?.text, ["front","rear"].contains(lens),
                  body["viewfinder"] == .bool(true), body["store"] == .array([.string("memory")]),
                  body["return_image"] == .bool(false),
                  case .number(let delay) = body["countdown_seconds"] ?? .number(0), let seconds = Int(exactly:delay),(0...10).contains(seconds),
                  case .number(let edge) = body["max_edge_px"] ?? .number(960),(64...1280).contains(edge),edge.rounded() == edge,
                  case .number(let quality) = body["quality"] ?? .number(80),(1...100).contains(quality) else {
                throw AppletRuntimeError.invalid("Unsupported camera request")
            }
            let (stream,result) = AsyncStream<LocalAppletLibrary.ReviewedPhoto>.makeStream(bufferingPolicy:.bufferingOldest(1))
            let item = Prompt(lens:lens,countdown:seconds,edge:CGFloat(edge),quality:CGFloat(quality/100),result:result)
            prompt = item
            defer { result.finish(); if prompt?.id == item.id { prompt = nil } }
            for await reviewed in stream {
                _ = try await library.open(edition)
                try checkSession()
                return try captures.store(reviewed)
            }
            throw CancellationError()
        }
        let response = try await library.cardTableAction(request,in:edition)
        try checkSession()
        return response
    }

    @MainActor private func video(_ request: JSONValue, review: Bool) async throws -> JSONValue {
        let body = request["body"]?.fields ?? [:]
        let mode: MoviePrompt.Mode
        var count = 0
        if review {
            guard body.count == 1, let id = body["capture_id"]?.text else { throw AppletRuntimeError.invalid("Choose a recorded video") }
            mode = .review(try captures.movie(id))
        } else {
            guard body.keys.allSatisfy({ ["camera","duration_seconds","countdown_seconds","audio","resolution","extract_frames","viewfinder"].contains($0) }),
                  case .string(let lens) = body["camera"] ?? .string("rear"), ["front","rear"].contains(lens),
                  (body["resolution"] ?? .string("720p")) == .string("720p"),
                  (body["audio"] ?? .bool(false)) == .bool(false), body["viewfinder"] == .bool(true),
                  case .number(let duration) = body["duration_seconds"] ?? .number(10), let seconds = Int(exactly:duration), (1...30).contains(seconds),
                  case .number(let delay) = body["countdown_seconds"] ?? .number(0), let countdown = Int(exactly:delay), (0...10).contains(countdown),
                  case .number(let samples)? = body["extract_frames"], let frames = Int(exactly:samples), (1...8).contains(frames) else {
                throw AppletRuntimeError.invalid("Unsupported video request")
            }
            mode = .record(lens,seconds,countdown)
            count = frames
        }
        let (stream,result) = AsyncStream<MoviePrompt.Answer>.makeStream(bufferingPolicy:.bufferingOldest(1))
        let item = MoviePrompt(mode:mode,result:result)
        moviePrompt = item
        defer { result.finish(); if moviePrompt?.id == item.id { moviePrompt = nil } }
        for await answer in stream {
            switch answer {
            case .captured(let file,let camera):
                let frames = try await file.frames(count:count)
                _ = try await library.open(edition)
                try checkSession()
                return try captures.storeVideo(file,camera:camera,frames:frames)
            case .reviewed(let delete):
                _ = try await library.open(edition)
                try checkSession()
                if delete, let id = body["capture_id"]?.text {
                    _ = try captures.readOrDelete(.object(["method":.string("DELETE")]),id:id)
                }
                return .object(["deleted":.bool(delete)])
            }
        }
        throw CancellationError()
    }
}

private struct CardMovieReview: View {
    let file: CardMovieFile
    let finish: (Bool) -> Void
    @State private var player: AVPlayer?

    var body: some View {
        VStack {
            VideoPlayer(player:player)
            Button("Keep video") { finish(false) }
            Button("Delete video",role:.destructive) { finish(true) }
        }
        .padding()
        .onAppear { player = AVPlayer(url:file.url) }
        .onDisappear { player?.pause(); player = nil }
    }

}

private struct LocalPackageImage: View {
    @EnvironmentObject private var model: AppModel
    let edition: LocalAppletLibrary.Edition
    let name: String
    @State private var image: UIImage?
    @State private var error: String?

    var body: some View {
        Group {
            if let image { Image(uiImage:image).resizable().scaledToFit() }
            else if let error { Text(error) }
            else { ProgressView() }
        }
        .navigationTitle(name)
        .task {
            do {
                let library = try await model.localApplets()
                let bytes = try await library.media(named:name,in:edition)
                guard let decoded = UIImage(data:bytes) else { throw AppletRuntimeError.invalid("Cannot display package image") }
                try Task.checkCancellation()
                image = decoded
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
}

private struct LocalEditionView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var cookingGoals = ""
    @State private var cookingCamera = "rear"
    @State private var speakTips = false
    @State private var cooking = false
    @State private var cookingStatus: String?
    @State private var speaker = AVSpeechSynthesizer()
    let edition: LocalAppletLibrary.Edition
    let library: LocalAppletLibrary
    @State private var instance: LocalAppletLibrary.Instance?
    @State private var editingSettings = false
    @State private var refresh = 0
    @State private var execution: Task<Void, Never>?
    @State private var question: LocalAppletLibrary.OwnerQuestion?
    private struct PhotoPrompt: Identifiable {
        let id = UUID()
        let camera: String
        let size: AppletPackage.CameraSize?
        let response: AsyncStream<LocalAppletLibrary.ReviewedPhoto>.Continuation
    }
    @State private var drafts: [String:String] = [:]
    @State private var cardReplies: [String:JSONValue] = [:]
    @State private var scanning = false
    @State private var scanSession: UUID?
    @State private var scanningItem: JSONValue?
    @State private var choosingImage = false
    @State private var confirmingImage = false
    @State private var pendingDraft: JSONValue?
    @State private var recording = false
    @State private var photoPrompt: PhotoPrompt?
    @State private var answer: AsyncStream<String>.Continuation?
    @State private var notices = [LocalAppletLibrary.Notice]()
    @State private var error: String?

    var body: some View {
        List {
            if let instance {
                if !instance.package.media.isEmpty {
                    Section("Package images") {
                        ForEach(instance.package.media.keys.sorted(),id:\.self) { name in
                            NavigationLink(name) { LocalPackageImage(edition:edition,name:name) }
                        }
                    }
                }
                if let bytes = instance.package.iconPNG, let icon = UIImage(data: bytes) {
                    Image(uiImage: icon).resizable().scaledToFit().frame(width: 64, height: 64)
                }
                LabeledContent("Version", value: instance.package.version)
                LabeledContent("Installed revision", value: String(edition.revision))
                if let summary = instance.package.definition["summary"]?.text { Text(summary) }
                if instance.package.id == "dog-monitor", instance.package.definition["runtime"]?["adapter"] == .string("bridgit.package.v1") {
                    NavigationLink("Imported Dog Monitor history") {
                        ImportedDogHistory(library:library,edition:edition)
                    }
                    let galleries = model.applets.filter { $0.applet == "dog" }
                    if !galleries.isEmpty && !model.isSample {
                        Section("Existing Pet monitor galleries") {
                            ForEach(galleries) { gallery in
                                NavigationLink {
                                    AppletDetailView(applet:gallery)
                                } label: {
                                    VStack(alignment:.leading) {
                                        Text(gallery.title)
                                        Text([gallery.name ?? gallery.phone, gallery.role].compactMap { $0 }.joined(separator:" · "))
                                            .frostText(.supporting).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                if case .array(let watches)? = instance.package.definition["processing"]?["watches"], !watches.isEmpty {
                    Section("Monitoring phone") {
                        PairedAppletPhone(label: "Monitoring phone") { live in
                            MonitorRows(live: live, edition: edition, card: instance.package.definition["ui"]?["card"])
                        }
                    }
                }
                if instance.package.definition["config_schema"]?["properties"]?.fields?.isEmpty == false {
                    Button("Applet settings") {
                        Task {
                            do {
                                self.instance = try await library.open(edition)
                                editingSettings = true
                            } catch { self.error = error.localizedDescription }
                        }
                    }.disabled(execution != nil)
                }
                if let card = instance.package.definition["ui"]?["card"],
                   case .array(let pieces)? = card["pieces"] {
                    Section(card["title"]?.text ?? edition.name) {
                        ForEach(pieces.indices, id: \.self) { index in
                            let piece = pieces[index]
                            switch piece["only_if"] == nil ? piece["type"]?.text : nil {
                            case "header","note","text":
                                if let text = piece["text"]?.text {
                                    Text(text).font(piece["type"]?.text == "header" ? .headline : .body)
                                }
                            case "buttons":
                                if case .array(let items)? = piece["items"] {
                                    ForEach(items.indices, id: \.self) { index in
                                        let item = items[index]
                                        if let label = item["label"]?.text, let action = item["do"]?.fields {
                                            if instance.package.ocrButton(item) != nil {
                                                Button(label) { scanningItem = item; scanSession = model.librarySessionGeneration; confirmingImage = true }.disabled(execution != nil)
                                            } else if instance.package.microphoneButton(item) != nil {
                                                Button(label) { recordTranscript(item,package:instance.package) }.disabled(execution != nil)
                                            } else if instance.package.isDraftSave(item) {
                                                Button(label) { pendingDraft = item }.disabled(execution != nil)
                                            } else if let batch = instance.package.batchButton(item) {
                                                Button(label) { start(batch.flow,batch:item) }.disabled(execution != nil)
                                            } else if instance.package.cameraButton(item) != nil {
                                                Button(label) { takePicture(item) }.disabled(execution != nil)
                                            } else if item.fields?.keys.allSatisfy({ ["label","style","do"].contains($0) }) != true {
                                                Text(label + " requires additional runtime support.")
                                            } else if action.count == 1, action["refresh"] == .bool(true) {
                                                Button(label) { refresh += 1 }
                                            } else if let button = instance.package.cardButton(item) {
                                                Button(label) { start(button.flow) }.disabled(execution != nil)
                                            } else {
                                                Text(label + " is unavailable on this device.")
                                            }
                                        }
                                    }
                                }
                            case "draft":
                                if let field = instance.package.cardDrafts.first(where:{ $0.id == piece["id"]?.text }) {
                                    TextField(field.label,text:Binding(get:{ drafts[field.id] ?? "" },set:{ drafts[field.id] = $0 }),axis:.vertical)
                                    if (drafts[field.id] ?? "").utf16.count > field.limit { Text("Maximum " + String(field.limit) + " characters") }
                                }
                            case "devices":
                                if case .array(let grants)? = instance.package.definition["permissions"]?["required"],
                                   grants.contains(.string("lan_control")),
                                   let path = piece["items"]?["$source"]?.text?.components(separatedBy: "."),
                                   path.count == 2, path[1] == "devices",
                                   card["sources"]?[path[0]]?["call"] == .object(["method":.string("GET"), "path":.string("/v1/lan/devices")]) {
                                    PairedAppletPhone(label: "Inventory phone") { live in
                                        EnrollmentRows(live: live, edition: edition, piece: piece, refresh: refresh)
                                    }
                                } else {
                                    Text("This device source requires additional runtime support.")
                                }
                            case "metrics":
                                if case .array(let metrics)? = piece["items"] {
                                    ForEach(metrics.indices, id: \.self) { index in
                                        let item = metrics[index]
                                        if MonitorCard.binding(item["value"], sources: card["sources"]) == nil {
                                            LabeledContent(item["label"]?.text ?? "Metric", value: "Source unavailable")
                                        }
                                    }
                                }
                            case "timeline":
                                if MonitorCard.binding(piece["items"], sources: card["sources"]) == nil {
                                    LocalTimelinePiece(library:library,edition:edition,piece:piece,refresh:refresh)
                                }
                            case "gallery":
                                LocalPhotoGallery(library:library,edition:edition,piece:piece,refresh:refresh)
                            case "table":
                                if MonitorCard.binding(piece["rows"], sources: card["sources"]) == nil {
                                    LocalTablePiece(library:library, edition:edition, piece:piece, sources:card["sources"], refresh:refresh)
                                }
                            default:
                                Text("This card element requires additional runtime support.")
                            }
                        }
                    }
                }
                if let html = instance.package.cardHTML {
                    let session = model.librarySessionGeneration
                    NavigationLink("Open applet card") {
                        InstalledHTMLCard(html:html,library:library,edition:edition,session:session)
                        .navigationTitle(edition.name)
                        .navigationBarTitleDisplayMode(.inline)
                    }
                }
                let buttons = instance.package.runButtons.filter { !$0.onCard }
                ForEach(buttons.indices, id: \.self) { index in
                    Button(buttons[index].label) { start(buttons[index].flow) }.disabled(execution != nil)
                }
                ForEach(instance.package.flows.keys.sorted().filter { instance.package.cookingInterval($0) != nil }, id: \.self) { flow in
                    Section(instance.package.flows[flow]?["name"]?.text ?? flow) {
                        TextField("Cooking goals (optional)",text:$cookingGoals).disabled(execution != nil)
                        Picker("Camera",selection:$cookingCamera) {
                            Text("Rear").tag("rear"); Text("Front").tag("front")
                        }.disabled(execution != nil)
                        Toggle("Speak suggestions",isOn:$speakTips)
                            .onChange(of:speakTips) { _,enabled in if !enabled { speaker.stopSpeaking(at:.immediate) } }
                        Button("Start foreground session") { startCooking(flow) }.disabled(execution != nil || cookingGoals.utf16.count > 1000)
                        if let timing = instance.package.cookingTiming(flow) {
                            Text("Review each frame before sending. The session ends after \(Duration.seconds(timing.session).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))) or when you leave.")
                        }
                        if let cookingStatus { Text(cookingStatus) }
                    }
                }
                if execution != nil { ProgressView("Running"); Button("Stop") { execution?.cancel() } }
                if let question {
                    Section(question.title) {
                        Text(question.text)
                        ForEach(question.choices, id: \.self) { choice in
                            Button(choice) { answer?.yield(choice); answer?.finish() }
                        }
                    }
                }
                ForEach(notices) { notice in
                    VStack(alignment: .leading) { Text(notice.title).frostText(.label, weight: .semibold); Text(notice.text) }
                }
                if let error { Text(error) }
            } else if let error { Text(error) }
            else { ProgressView("Opening local applet") }
        }
        .confirmationDialog("Choose the image to read",isPresented:$confirmingImage) {
            Button("Choose image…") { choosingImage = true }
            Button("Cancel",role:.cancel) {}
        } message: {
            Text("This photo becomes the selected gallery input for this applet. Text recognition runs on this device.")
        }
        .fileImporter(isPresented:$choosingImage,allowedContentTypes:[.image]) { result in
            guard scanSession == model.librarySessionGeneration, let item = scanningItem, let instance else { return }
            do { scanImage(try result.get(),item:item,snapshot:instance) }
            catch let failure as CocoaError where failure.code == .userCancelled {}
            catch { self.error = error.localizedDescription }
        }
        .alert(pendingDraft?["confirm"]?.text ?? "Save entry?",isPresented:Binding(
            get:{ pendingDraft != nil },set:{ if !$0 { pendingDraft = nil } }),presenting:pendingDraft) { item in
            Button("Save") {
                let values = drafts, replies = cardReplies, reviewed = instance
                execution = Task {
                    defer { execution = nil }
                    do {
                        try Task.checkCancellation()
                        try await library.recordDraft(item,in:edition,drafts:values,replies:replies,operation:UUID(),reviewed:reviewed)
                        refresh += 1
                        error = nil
                    } catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel",role:.cancel) {}
        }
        .sheet(isPresented:$editingSettings) {
            if let instance {
                LocalSettingsView(instance:instance) { values in
                    try await library.saveSettings(values,replacing:instance)
                    self.instance = try await library.open(edition)
                    drafts.removeAll(); cardReplies.removeAll()
                    refresh += 1
                    editingSettings = false
                }
            }
        }
        .sheet(item: $photoPrompt) { prompt in
            AppletCameraSheet(requested: prompt.camera, size: prompt.size, accept: { photo in
                prompt.response.yield(photo)
                prompt.response.finish()
            }, cancel: { prompt.response.finish() })
            .interactiveDismissDisabled()
            .onDisappear { prompt.response.finish() }
        }
        .navigationTitle(edition.name)
        .onDisappear { execution?.cancel(); speaker.stopSpeaking(at:.immediate) }
        .onChange(of:scenePhase) { _,phase in
            if (cooking || recording || scanning) && phase == .background { execution?.cancel(); speaker.stopSpeaking(at:.immediate) }
        }
        .onChange(of:model.librarySessionGeneration) { _,_ in
            execution?.cancel()
            speaker.stopSpeaking(at:.immediate)
            scanSession = nil; scanningItem = nil; choosingImage = false; confirmingImage = false
            pendingDraft = nil; drafts.removeAll(); cardReplies.removeAll()
        }
        .task {
            do { instance = try await library.open(edition) }
            catch { self.error = error.localizedDescription }
        }
    }
    private nonisolated static func readSelectedImage(_ url: URL) async throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom:url)
        defer { try? handle.close() }
        guard let data = try handle.read(upToCount:16 * 1024 * 1024 + 1), data.count <= 16 * 1024 * 1024 else {
            throw AppletRuntimeError.invalid("Choose an image up to 16 MiB")
        }
        return data
    }

    @MainActor private func scanImage(_ url: URL, item: JSONValue, snapshot: LocalAppletLibrary.Instance) {
        guard execution == nil, scenePhase == .active else { return }
        error = nil
        scanning = true
        execution = Task {
            defer { execution = nil; scanning = false }
            do {
                let data = try await Self.readSelectedImage(url)
                let (current,replies) = try await library.scanSelectedImage(data,with:item,replacing:snapshot)
                try Task.checkCancellation()
                instance = current
                cardReplies.merge(replies) { _, new in new }
                for field in current.package.cardDrafts {
                    if let value = replies[field.source]?.text { drafts[field.id] = value }
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }

    @MainActor private func recordTranscript(_ item: JSONValue, package: AppletPackage) {
        guard execution == nil, scenePhase == .active else { return }
        recording = true
        error = nil
        execution = Task {
            defer { recording = false; execution = nil }
            do {
                let replies = try await library.captureTranscript(item,in:edition) { seconds in
                    try await recordLocalTranscript(seconds:seconds)
                }
                try Task.checkCancellation()
                guard scenePhase == .active else { throw CancellationError() }
                cardReplies.merge(replies) { _, new in new }
                for field in package.cardDrafts {
                    if let value = replies[field.source]?.text { drafts[field.id] = value }
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }

    @MainActor private func startCooking(_ flow: String) {
        guard execution == nil, scenePhase == .active, let timing = instance?.package.cookingTiming(flow) else { return }
        let session = model.librarySessionGeneration
        cooking = true
        cookingStatus = nil
        error = nil
        execution = Task {
            let expiry = Task { @MainActor in
                try await Task.sleep(for:.seconds(timing.session))
                execution?.cancel()
            }
            defer { expiry.cancel(); cooking = false; execution = nil; speaker.stopSpeaking(at:.immediate); refresh += 1 }
            do {
                try await library.observeCooking(flow,in:edition,camera:cookingCamera,goals:cookingGoals,
                    capture:{ try await capturePhoto($0) },
                    decide:{ request,reserve in try await decide(request,reserve:reserve) },
                    observed:{ action,tip in
                        try await MainActor.run {
                            try Task.checkCancellation()
                            guard scenePhase == .active, session == model.librarySessionGeneration else { throw CancellationError() }
                            cookingStatus = [action,tip].filter { !$0.isEmpty }.joined(separator:" · ")
                            refresh += 1
                            if speakTips && !tip.isEmpty { speaker.speak(AVSpeechUtterance(string:tip)) }
                        }
                    })
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }

    @MainActor private func takePicture(_ item: JSONValue) {
        guard execution == nil else { return }
        error = nil
        execution = Task {
            defer { execution = nil; refresh += 1 }
            do {
                _ = try await library.captureCardPhoto(item,in:edition) { lens,size in
                    try await capturePhoto(lens,size:size)
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    @MainActor private func hearOnDevice(seconds: Int, language: String?) async throws -> String {
        guard scenePhase == .active else { throw CancellationError() }
        recording = true
        defer { recording = false }
        return try await recordLocalTranscript(seconds:seconds,language:language)
    }

    @MainActor private func start(_ flow: String, batch: JSONValue? = nil) {
        guard execution == nil else { return }
        error = nil
        execution = Task {
            defer { execution = nil; refresh += 1 }
            do {
                let ask: LocalAppletLibrary.AnswerOwner = { try await askOwner($0) }
                let capture: LocalAppletLibrary.CapturePhoto = { try await capturePhoto($0) }
                let decision: LocalAppletLibrary.Decide = { request, reserve in try await decide(request,reserve:reserve) }
                let present: @Sendable (LocalAppletLibrary.Notice) async throws -> Void = { notice in
                    try Task.checkCancellation()
                    await MainActor.run {
                        if !notices.contains(where: { $0.id == notice.id }) { notices.append(notice) }
                    }
                }
                if let batch {
                    _ = try await library.runCardBatch(batch,in:edition,ask:ask,capture:capture,decide:decision,present:present)
                } else {
                    let hear: LocalAppletLibrary.Hear = { seconds, language in
                        try await hearOnDevice(seconds:seconds,language:language)
                    }
                    _ = try await library.runButton(flow,in:edition,ask:ask,capture:capture,hear:hear,decide:decision,present:present)
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }

    @MainActor private func decide(_ decision: LocalAppletLibrary.Decision, reserve: @Sendable () async throws -> Void) async throws -> JSONValue {
        let generation = model.librarySessionGeneration
        let items = decision.questions.keys.sorted().compactMap { decision.questions[$0]?["instructions"]?.text }
        let media = decision.jpeg == nil ? "these questions" : "your reviewed photo and these questions"
        let description: String
        switch decision.service {
        case .clef: description = "Send " + media + " to Cloudflare Clef for one paid check?"
        case .appraise: description = "Send this saved inventory photo to Gemini via OpenRouter to identify belongings and estimate prices?"
        case .illustration,.voice,.speak,.story,.context: throw AppletRuntimeError.unavailable("Use the card context route")
        case .chef:
            description = decision.options["mode"] == .string("observe")
                ? "Send this reviewed frame, your cooking goals, pantry labels and notes, and previous suggestion to GLM via OpenRouter for one cooking observation?"
                : "Send this saved kitchen photo and its area to GLM via OpenRouter to identify ingredients?"
        }
        let message = ([description, "Bridgit links this device to your account on first use and applies your applet budget."] + items).joined(separator:"\n\n")
        let answer = try await askOwner(.init(id:UUID().uuidString,title:"Allow paid analysis?",text:message,choices:["Allow once","Cancel"]))
        guard answer == "Allow once", model.librarySessionGeneration == generation else { throw CancellationError() }
        return try await model.cloud.decide(decision,reserve:reserve)
    }

    @MainActor private func capturePhoto(_ camera: String, size: AppletPackage.CameraSize? = nil) async throws -> LocalAppletLibrary.ReviewedPhoto {
        try Task.checkCancellation()
        let (responses, continuation) = AsyncStream<LocalAppletLibrary.ReviewedPhoto>.makeStream(bufferingPolicy: .bufferingOldest(1))
        let prompt = PhotoPrompt(camera: camera, size:size, response: continuation)
        photoPrompt = prompt
        defer {
            continuation.finish()
            if photoPrompt?.id == prompt.id { photoPrompt = nil }
        }
        for await response in responses { return response }
        throw CancellationError()
    }

    @MainActor private func askOwner(_ prompt: LocalAppletLibrary.OwnerQuestion) async throws -> String {
        try Task.checkCancellation()
        let (responses, continuation) = AsyncStream<String>.makeStream(bufferingPolicy: .bufferingOldest(1))
        question = prompt
        answer = continuation
        defer {
            continuation.finish()
            if question?.id == prompt.id { question = nil; answer = nil }
        }
        for await response in responses { return response }
        throw CancellationError()
    }

}

private struct ImportedDogHistory: View {
    @EnvironmentObject private var model: AppModel
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    @State private var episodes: [LocalAppletLibrary.HistoricalEpisode]?
    @State private var selecting = false
    @State private var selectionSession: UUID?
    @State private var openedSession: UUID?
    @State private var problem: String?

    var body: some View {
        List {
            if let openedSession, openedSession != model.librarySessionGeneration {
                Text("Reopen this applet from the current account.")
            } else if let episodes {
                Section("Imported episodes · " + String(episodes.count)) {
                    ForEach(episodes.indices.reversed(), id: \.self) { index in
                        let item = episodes[index]
                        VStack(alignment:.leading) {
                            Text(item.kind.capitalized)
                            let start = Date(timeIntervalSince1970:Double(item.start)/1000)
                            let end = Date(timeIntervalSince1970:Double(item.lastSeen)/1000)
                            Text(start.formatted() + " – " + end.formatted() + " · " + String(item.frames) + " frames")
                                .frostText(.supporting)
                        }
                    }
                }
            } else {
                Button("Choose exported history…") { selectionSession = model.librarySessionGeneration; selecting = true }
            }
            if let problem { Text(problem) }
        }
        .navigationTitle("Imported history")
        .task(id:model.librarySessionGeneration) {
            episodes = nil
            let current = model.librarySessionGeneration
            if openedSession == nil { openedSession = current }
            guard openedSession == current else { return }
            do {
                let loaded = try await library.importedDogEpisodes(in:edition)
                try Task.checkCancellation()
                episodes = loaded
            } catch is CancellationError {} catch { problem = error.localizedDescription }
        }
        .fileImporter(isPresented:$selecting,allowedContentTypes:[.json,.plainText]) { result in
            guard let session = selectionSession, session == openedSession, session == model.librarySessionGeneration else { return }
            Task {
                do {
                    let url = try result.get()
                    let bytes = try await Self.read(url)
                    guard session == model.librarySessionGeneration else { return }
                    let loaded = try await library.importDogHistory(bytes,into:edition)
                    guard session == model.librarySessionGeneration else { return }
                    episodes = loaded
                    problem = nil
                } catch let error as CocoaError where error.code == .userCancelled {}
                catch { problem = error.localizedDescription }
            }
        }
    }

    private nonisolated static func read(_ url: URL) async throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom:url)
        defer { try? handle.close() }
        let limit = 8 * 1024 * 1024
        var bytes = Data()
        while let chunk = try handle.read(upToCount:min(65536,limit + 1 - bytes.count)), !chunk.isEmpty {
            bytes.append(chunk)
            guard bytes.count <= limit else { throw AppletRuntimeError.invalid("Choose a history archive up to 8 MiB") }
        }
        return bytes
    }
}

private struct LocalSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    let instance: LocalAppletLibrary.Instance
    let save: ([String:JSONValue]) async throws -> Void
    @State private var input: [String:String]
    @State private var saving = false
    @State private var error: String?

    init(instance: LocalAppletLibrary.Instance, save: @escaping ([String:JSONValue]) async throws -> Void) {
        self.instance = instance
        self.save = save
        let properties = instance.package.definition["config_schema"]?["properties"]?.fields ?? [:]
        _input = State(initialValue:Dictionary(uniqueKeysWithValues:instance.config.map { key,value in
            (key,Self.fieldText(value,schema:properties[key]))
        }))
    }

    private static func fieldText(_ value: JSONValue, schema: JSONValue?) -> String {
        if schema?["type"] == .string("string"), let text = value.text { return text }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys,.withoutEscapingSlashes]
        return (try? String(decoding:encoder.encode(value),as:UTF8.self)) ?? ""
    }

    private var properties: [String:JSONValue] {
        instance.package.definition["config_schema"]?["properties"]?.fields ?? [:]
    }

    private func isRequired(_ key: String) -> Bool {
        guard case .array(let names)? = instance.package.definition["config_schema"]?["required"] else { return false }
        return names.contains(.string(key))
    }

    var body: some View {
        NavigationStack {
            Form {
                ForEach(properties.keys.sorted(),id:\.self) { key in
                    let schema = properties[key]
                    let hint = instance.package.definition["ui"]?["inputs"]?[key]
                    let label = hint?["label"]?.text ?? key
                    let binding = Binding(get:{ input[key] ?? "" },set:{ input[key] = $0 })
                    Section {
                        if case .array(let options)? = schema?["enum"] {
                            Picker(label,selection:binding) {
                                if input[key] == nil { Text("Choose…").tag("") }
                                ForEach(options.indices,id:\.self) { index in
                                    let text = Self.fieldText(options[index],schema:schema)
                                    Text(options[index].text ?? text).tag(text)
                                }
                            }
                        } else if schema?["type"] == .string("boolean") {
                            Picker(label,selection:binding) {
                                if input[key] == nil { Text("Choose…").tag("") }
                                Text("On").tag("true")
                                Text("Off").tag("false")
                            }
                        } else {
                            TextField(label,text:binding,axis:.vertical)
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                        if let defaultValue = instance.package.defaults[key] {
                            let defaultText = Self.fieldText(defaultValue,schema:schema)
                            if input[key] != defaultText { Button("Use default") { input[key] = defaultText } }
                        } else if input[key] != nil, !isRequired(key) {
                            Button("Clear value") { input.removeValue(forKey:key) }
                        }
                    } header: { Text(label) } footer: {
                        if let help = hint?["help"]?.text { Text(help) }
                        if schema?["type"] == .string("array") || schema?["type"] == .string("object") || schema?["type"]?.text == nil {
                            Text("Enter a JSON value.")
                        }
                    }
                }
                if let error { Text(error) }
            }
            .navigationTitle("Applet settings")
            .disabled(saving)
            .interactiveDismissDisabled(saving)
            .toolbar {
                ToolbarItem(placement:.cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement:.confirmationAction) {
                    Button("Save") {
                        saving = true
                        Task {
                            defer { saving = false }
                            do {
                                var values = [String:JSONValue]()
                                for (key,text) in input {
                                    if properties[key]?["type"] == .string("string") { values[key] = .string(text) }
                                    else {
                                        guard let value = try? JSONDecoder().decode(JSONValue.self,from:Data(text.utf8)) else {
                                            throw AppletRuntimeError.invalid("Invalid value for " + key)
                                        }
                                        values[key] = value
                                    }
                                }
                                try await save(values)
                            } catch { self.error = error.localizedDescription }
                        }
                    }.disabled(saving)
                }
            }
        }
    }
}

private struct PairedAppletPhone<Content: View>: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var relays: RelayStore
    let label: String
    @ViewBuilder let content: (PhoneLive) -> Content
    @State private var selected = ""

    private var eligible: [PortalDevice] {
        guard model.mode == .signedIn else { return [] }
        return relays.pairings.compactMap { pairing in
            guard let install = pairing.install,
                  let phone = model.phones.first(where: { $0.phone == install }) else { return nil }
            return PortalDevice(id: "relay:" + pairing.deviceId, name: phone.displayName, cloud: phone, pairing: pairing)
        }
    }

    var body: some View {
        if eligible.isEmpty {
            Text("Pair a phone on this account with this applet edition installed.")
        } else {
            Picker(label, selection: $selected) {
                Text("Choose a paired phone").tag("")
                ForEach(eligible) { Text($0.name).tag($0.id) }
            }
            if let phone = eligible.first(where: { $0.id == selected }) {
                content(model.live(for: phone))
                    .id(phone.pairing)
            }
        }
    }
}

/// Resolve only watch bindings declared by this package. Local table and workflow sources use other hosts.
enum MonitorCard {
    struct Binding {
        let key: String
        let read: String
        let path: [String]
        func resolve(in watch: PhoneLive.MonitoredWatch) -> JSONValue? {
            guard watch.key == key else { return nil }
            return path.reduce(watch.cardSource(read: read)) { $0?[$1] }
        }
    }

    static func binding(_ value: JSONValue?, sources: JSONValue?) -> Binding? {
        guard let ref = value?["$source"]?.text else { return nil }
        let parts = ref.components(separatedBy: ".")
        guard let alias = parts.first, let source = sources?[alias],
              let key = source["watch"]?["$watch"]?.text, let read = source["read"]?.text,
              ["status","tally"].contains(read) else { return nil }
        return Binding(key: key, read: read, path: Array(parts.dropFirst()))
    }

    static func display(_ value: JSONValue?, words: JSONValue? = nil) -> String? {
        let raw: String
        switch value {
        case .string(let text): raw = text
        case .number(let number): raw = number.formatted(.number.grouping(.never))
        case .bool(let flag): raw = flag ? "true" : "false"
        default: return nil
        }
        return words?[raw]?.text ?? raw
    }
}

private struct MonitorCardPiece: View {
    let piece: JSONValue
    let sources: JSONValue?
    let watch: PhoneLive.MonitoredWatch

    private func binding(_ ref: JSONValue?) -> MonitorCard.Binding? {
        let binding = MonitorCard.binding(ref, sources: sources)
        return binding?.key == watch.key ? binding : nil
    }

    @ViewBuilder var body: some View {
        switch piece["type"]?.text {
        case "metrics":
            if case .array(let metrics)? = piece["items"] {
                ForEach(metrics.indices, id: \.self) { index in
                    let metric = metrics[index]
                    if let source = binding(metric["value"]) {
                        let value = MonitorCard.display(source.resolve(in: watch), words: metric["words"])
                        LabeledContent(metric["label"]?.text ?? "Metric",
                                       value: value.map { metric["format"]?.text == "minutes" ? $0 + " min" : $0 } ?? "Source unavailable")
                    }
                }
            }
        case "table":
            if let source = binding(piece["rows"]) {
                if let rows = source.resolve(in: watch)?.fields, case .array(let columns)? = piece["columns"] {
                    if rows.isEmpty { Text(piece["empty"]?.text ?? "No entries.") }
                    ForEach(rows.keys.sorted(), id: \.self) { key in
                        VStack(alignment: .leading) {
                            ForEach(columns.indices, id: \.self) { index in
                                let column = columns[index]
                                if let field = column["key"]?.text {
                                    LabeledContent(column["label"]?.text ?? field,
                                                   value: field == "$key" ? key : MonitorCard.display(rows[key]?[field]) ?? "Unavailable")
                                }
                            }
                        }
                    }
                } else { Text("Watch table unavailable.") }
            }
        case "timeline":
            if let source = binding(piece["items"]) {
                if case .array(let rows)? = source.resolve(in: watch) {
                    if rows.isEmpty { Text(piece["empty"]?.text ?? "No entries.") }
                    let limit: Int = if case .number(let n)? = piece["limit"] { Int(min(20, max(1, n))) } else { 10 }
                    ForEach(Array(rows.indices.reversed().prefix(limit)), id: \.self) { index in
                        let row = rows[index]
                        VStack(alignment: .leading) {
                            Text(MonitorCard.display(row[piece["label"]?.text ?? "label"], words: piece["label_words"])
                                 ?? row["outcome"]?.text ?? "Unavailable")
                            if let time = row[piece["time"]?.text ?? "at"]?.text { Text(time).frostText(.supporting) }
                            if let field = piece["detail"]?.text, let value = MonitorCard.display(row[field]) {
                                Text(field == "minutes" ? value + " min" : value).frostText(.supporting)
                            }
                        }
                    }
                } else { Text("Watch history unavailable.") }
            }
        default: EmptyView()
        }
    }
}

private struct MonitorRows: View {
    let live: PhoneLive
    let edition: LocalAppletLibrary.Edition
    let card: JSONValue?
    @State private var watches: [PhoneLive.MonitoredWatch]?
    @State private var failure: String?
    @State private var refresh = 0

    var body: some View {
        Group {
            if let failure { Text(failure) }
            else if let watches {
                if watches.isEmpty { Text("This edition has no watch on the selected phone.") }
                ForEach(watches) { watch in
                    VStack(alignment: .leading) {
                        LabeledContent(watch.key, value: watch.state.label)
                        if let checks = watch.checks, let matches = watch.matches {
                            Text("Checks: " + String(checks) + " · Matches: " + String(matches))
                        }
                        if let date = watch.last_check_at, let outcome = watch.last_outcome {
                            Text("Last check: " + date + " · " + outcome.replacingOccurrences(of: "_", with: " "))
                        }
                        if case .array(let pieces)? = card?["pieces"] {
                            ForEach(pieces.indices, id: \.self) { index in
                                if pieces[index]["only_if"] == nil {
                                    MonitorCardPiece(piece: pieces[index], sources: card?["sources"], watch: watch)
                                }
                            }
                        }
                    }
                }
            } else { ProgressView("Reading watch activity") }
            Button("Refresh watch activity") { refresh += 1 }
        }
        .task(id: refresh) {
            watches = nil
            failure = nil
            do {
                let loaded = try await live.monitorWatches(template: edition.id, revision: edition.revision)
                try Task.checkCancellation()
                watches = loaded
            } catch {
                if !Task.isCancelled { failure = error.localizedDescription }
            }
        }
    }
}

private struct EnrollmentRows: View {
    let live: PhoneLive
    let edition: LocalAppletLibrary.Edition
    let piece: JSONValue
    let refresh: Int
    @State private var rows: [PhoneLive.Enrollment]?
    @State private var failure: String?

    var body: some View {
        Group {
            if let failure { Text(failure) }
            else if let rows, rows.isEmpty { Text(piece["empty"]?.text ?? "No devices enrolled.") }
            else if let rows {
                ForEach(rows) { row in
                    VStack(alignment: .leading) {
                        Text(row.name)
                        Text(row.adapter + " · Availability unchecked").frostText(.supporting)
                        if row.verified_at > 0 {
                            Text("Enrollment verified " + Date(timeIntervalSince1970: Double(row.verified_at) / 1000).formatted()).frostText(.supporting)
                        }
                    }
                }
            } else { ProgressView("Reading enrolled devices") }
        }
        .task(id: refresh) {
            rows = nil
            failure = nil
            do {
                let loaded = try await live.enrollments(template: edition.id, revision: edition.revision)
                try Task.checkCancellation()
                rows = loaded
            } catch {
                if !Task.isCancelled { failure = error.localizedDescription }
            }
        }
    }
}

private struct LocalTimelinePiece: View {
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    let piece: JSONValue
    let refresh: Int
    @State private var entries: [LocalAppletLibrary.TimelineEntry]?
    @State private var error: String?

    var body: some View {
        Group {
            if let error { Text(error) }
            else if let entries {
                if entries.isEmpty { Text(piece["empty"]?.text ?? "Nothing yet.") }
                ForEach(entries) { entry in
                    VStack(alignment:.leading) {
                        Text(entry.label)
                        if let detail = entry.detail { Text(detail) }
                        if piece["time_format"] == .string("time") {
                            Text(entry.date.formatted()).frostText(.supporting)
                        } else {
                            Text(entry.date,style:.relative).frostText(.supporting)
                        }
                    }
                }
            } else { ProgressView() }
        }
        .task(id:refresh) {
            do {
                let result = try await library.timeline(piece,in:edition)
                try Task.checkCancellation()
                entries = result
                error = nil
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
    }
}

private struct LocalTablePiece: View {
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    let piece: JSONValue
    let sources: JSONValue?
    let refresh: Int
    @State private var removing: String?
    @State private var rows = [[String:JSONValue]]()
    @State private var error: String?

    var body: some View {
        Group {
            if let error { Text(error) }
            else if rows.isEmpty { Text(piece["empty"]?.text ?? "No saved rows.") }
            else if case .array(let columns)? = piece["columns"] {
                let groupKey = piece["group_by"]?.text
                let grouped = Dictionary(grouping:rows) { row in groupKey.flatMap { row[$0]?.text } ?? "" }
                ForEach(grouped.keys.sorted(),id:\.self) { name in
                    let members = grouped[name] ?? []
                    if groupKey != nil { Text(name.isEmpty ? "Other" : name).frostText(.label, weight: .semibold) }
                    ForEach(members.indices,id:\.self) { index in
                        let row = members[index]
                        VStack(alignment:.leading) {
                            if let photoColumn = piece["photo"]?["column"]?.text, let reference = row[photoColumn]?.text {
                                LocalTablePhoto(library:library,edition:edition,reference:reference,thumbnail:true)
                            }
                            ForEach(columns.indices,id:\.self) { column in
                                let spec = columns[column]
                                if let key = spec["key"]?.text, let value = row[key] {
                                    LabeledContent(spec["label"]?.text ?? key,value:display(value,spec:spec))
                                }
                            }
                            if piece["owner_delete"] == .bool(true), let id = row["id"]?.text {
                                Button("Delete row",role:.destructive) { removing = id }
                            }
                            if let marks = piece["marks"]?.fields {
                                ForEach(marks.keys.sorted(),id:\.self) { key in
                                    if case .bool(let flag)? = row[key], let label = marks[key]?[String(flag)]?.text {
                                        Text(label).frostText(.supporting)
                                    }
                                }
                            }
                        }
                    }
                    if groupKey != nil { totals(members,columns:columns,label:"Subtotal") }
                }
                totals(rows,columns:columns,label:"Total")
            }
        }
        .alert("Delete this row?",isPresented:Binding(get:{ removing != nil },set:{ if !$0 { removing = nil } }),presenting:removing) { id in
            Button("Delete",role:.destructive) {
                Task {
                    do {
                        try await library.deleteDisplayedRow(id,from:piece,in:edition)
                        rows.removeAll { $0["id"] == .string(id) }
                    } catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel",role:.cancel) {}
        }
        .task(id:refresh) {
            do {
                let path = piece["rows"]?["$source"]?.text?.components(separatedBy:".") ?? []
                guard let key = path.first, path.count == 1 || (path.count == 2 && path[1] == "items"), let source = sources?[key],
                      let table = source["table"]?.text, source.fields?.keys.allSatisfy({ ["table","limit"].contains($0) }) == true,
                      case .number(let limit) = source["limit"] ?? .number(20),
                      (1...500).contains(limit), limit.rounded() == limit,
                      case .number(let shown) = piece["limit"] ?? .number(piece["group_by"] == nil ? 20 : 500),
                      (1...500).contains(shown), shown.rounded() == shown else {
                    throw AppletRuntimeError.invalid("This table source requires additional runtime support.")
                }
                rows = try await library.rows(in:edition,table:table,limit:Int(min(limit,shown)))
                error = nil
            } catch { self.error = error.localizedDescription }
        }
    }

    @ViewBuilder private func totals(_ entries: [[String:JSONValue]], columns: [JSONValue], label: String) -> some View {
        if case .array(let totals)? = piece["totals"] {
            ForEach(totals.compactMap(\.text),id:\.self) { key in
                if let column = columns.first(where:{ $0["key"]?.text == key }) {
                    let numbers = entries.compactMap { row -> Double? in
                        if case .number(let amount)? = row[key] { return amount }
                        return nil
                    }
                    if !numbers.isEmpty {
                        LabeledContent(label + " · " + (column["label"]?.text ?? key),
                                       value:display(.number(numbers.reduce(0,+)),spec:column)).frostText(.label, weight: .semibold)
                    }
                }
            }
        }
    }

    private func display(_ value: JSONValue, spec: JSONValue) -> String {
        let format = spec["format"]?.text
        switch value {
        case .string(let text): return text
        case .number(let n) where format == "money": return n.formatted(.currency(code:spec["currency"]?.text ?? "USD"))
        case .number(let n) where format == "time": return Date(timeIntervalSince1970:n/1000).formatted()
        case .number(let n): return n.formatted()
        case .bool(let b): return b ? "True" : "False"
        default: return ""
        }
    }
}

private struct LocalPhotoGallery: View {
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    let piece: JSONValue
    let refresh: Int
    @State private var photos = [LocalAppletLibrary.GalleryPhoto]()
    @State private var selected: LocalAppletLibrary.GalleryPhoto?
    @State private var error: String?

    var body: some View {
        Group {
            if let error { Text(error) }
            else if photos.isEmpty { Text(piece["empty"]?.text ?? "No photos yet.") }
            else {
                LazyVGrid(columns:[GridItem(.adaptive(minimum:90,maximum:160))]) {
                    ForEach(photos) { photo in
                        Button {
                            selected = photo
                        } label: {
                            VStack {
                                LocalTablePhoto(library:library,edition:edition,reference:photo.reference,thumbnail:true)
                                if !photo.caption.isEmpty { Text(photo.caption).frostText(.supporting) }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .task(id:refresh) {
            do {
                let result = try await library.gallery(piece,in:edition)
                try Task.checkCancellation()
                photos = result
                error = nil
            } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
        }
        .sheet(item:$selected) { photo in
            VStack {
                LocalTablePhoto(library:library,edition:edition,reference:photo.reference,expanded:true)
                if !photo.caption.isEmpty { Text(photo.caption) }
                Button("Done") { selected = nil }
            }.padding()
        }
    }
}

private struct LocalTablePhoto: View {
    let library: LocalAppletLibrary
    let edition: LocalAppletLibrary.Edition
    let reference: String
    var thumbnail = false
    var expanded = false
    @State private var image: UIImage?
    @State private var unavailable = false

    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: expanded ? .infinity : (thumbnail ? 140 : 200)).accessibilityLabel("Saved applet photo") }
            else if unavailable { Text("Photo unavailable") }
            else { ProgressView() }
        }
        .task(id: reference) {
            image = nil; unavailable = false
            do {
                let data = try await library.photo(reference, in: edition)
                try Task.checkCancellation()
                if thumbnail, let source = CGImageSourceCreateWithData(data as CFData,nil),
                   let small = CGImageSourceCreateThumbnailAtIndex(source,0,[
                       kCGImageSourceCreateThumbnailFromImageAlways:true,
                       kCGImageSourceCreateThumbnailWithTransform:true,
                       kCGImageSourceThumbnailMaxPixelSize:320
                   ] as CFDictionary) {
                    image = UIImage(cgImage:small)
                } else { image = UIImage(data:data) }
                unavailable = image == nil
            } catch { if !Task.isCancelled { unavailable = true } }
        }
    }
}
