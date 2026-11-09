import BridgitAppletRuntime
@preconcurrency import AVFoundation
import AVKit
import ImageIO
import BridgitCore
import SwiftUI
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

/// The normal Library route on iPhone, iPad and native Mac shares one maintained store.
struct PortableLibraryEntry: View {
    let cloud: WatchClient
    let session: String?
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            HStack { Label("Applet library", systemImage: "books.vertical"); Spacer(); Image(systemName: "chevron.right") }
                .frame(minHeight: 48).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("portable-library-open")
        .coveringBuildStampSurface(presented)
        .bridgitDialog(isPresented: $presented) {
            NavigationStack { PortableLibraryView(cloud: cloud) }.buildStampSurface()
                #if os(macOS)
                .frame(minWidth: 620, minHeight: 500)
                #endif
        }
        .onChange(of: session) { _, _ in presented = false }
    }
}

private struct PortableLibraryView: View {
    @DialogDismiss private var dismiss
    @StateObject private var controller: PortableLibraryController
    @State private var search = ""
    @State private var owned = false
    @State private var cursor = ""
    init(cloud: WatchClient) { _controller = StateObject(wrappedValue: PortableLibraryController(cloud: cloud)) }
    var body: some View {
        List {
            if !controller.editions.isEmpty {
                Section("Installed on this device") {
                    ForEach(controller.editions) { edition in
                        NavigationLink { PortableInstalledView(controller: controller, edition: edition) } label: { Text(edition.name) }
                            .accessibilityIdentifier("portable-installed-" + edition.id)
                    }
                }
            }
            Section("Library") {
                Picker("Library", selection: $owned) { Text("Public library").tag(false); Text("My editions").tag(true) }.pickerStyle(.segmented)
                if let page = controller.page {
                    ForEach(page.applets, id: \.key) { entry in
                        NavigationLink { PortableReleaseReview(controller: controller, entry: entry) } label: { Text(entry.name) }
                            .accessibilityIdentifier("portable-catalog-" + entry.key)
                    }
                    #if !os(macOS)
                    catalogNext
                    #endif
                }
            }
            if let problem = controller.problem { Text(problem).foregroundStyle(B.error) }
        }
        .portableMacActions(shown: controller.page?.next != nil) { catalogNext }
        .navigationTitle("Applet library")
        .toolbar { ToolbarItem(placement: .primaryAction) { BuildStamp() } }
        .searchable(text: $search, prompt: "Search applets")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        .task(id: "\(owned)|\(search)|\(cursor)") {
            do { try await controller.loadInstalled() } catch { controller.problem = error.localizedDescription }
            do { try await controller.catalog(query: String(search.prefix(100)), after: cursor, owned: owned) }
            catch { controller.problem = error.localizedDescription }
        }
        .onChange(of: search) { _, _ in cursor = "" }
        .onChange(of: owned) { _, _ in cursor = "" }
    }
    @ViewBuilder private var catalogNext: some View {
        if let next = controller.page?.next { Button("Next page") { cursor = next }.accessibilityIdentifier("portable-catalog-next") }
    }
}

struct PortableReleaseReview: View {
    @ObservedObject var controller: PortableLibraryController
    let entry: CatalogEntry
    init(controller: PortableLibraryController, entry: CatalogEntry) { self.controller = controller; self.entry = entry }
    @State private var tables: [AppletReviewDetails.Table] = []
    @State private var spending: String?
    @State private var release: CatalogRelease?
    @State private var installed: LocalAppletLibrary.Edition?
    @State private var problem: String?
    @State private var busy = false
    @State private var update: LocalAppletLibrary.Update?
    @State private var confirmingUpdate = false
    var body: some View {
        List {
            LabeledContent("Revision", value: String(entry.revision))
            Text(entry.summary)
            if let release {
                switch release {
                case .local(let selected):
                    LabeledContent("Version", value: selected.package.version)
                    if case .array(let permissions)? = selected.package.definition["permissions"]?["required"] {
                        Section("Requested permissions") {
                            ForEach(permissions.compactMap(\.text), id: \.self) { permission in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(AppletReviewPermissionDetails.all[permission]?.0 ?? permission)
                                    if let description = AppletReviewPermissionDetails.all[permission]?.1 {
                                        Text(description).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if !tables.isEmpty {
                        Section("Saved data") {
                            ForEach(tables) { table in
                                VStack(alignment: .leading) {
                                    Text(table.label ?? table.id)
                                    Text(table.retention).font(.subheadline).foregroundStyle(B.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if let spending {
                        Section("Spending limits") { Text(spending).accessibilityIdentifier("portable-review-spending") }
                    }
                    if case .array(let services)? = selected.package.definition["permissions"]?["remote"], !services.isEmpty {
                        Section("Remote services") { ForEach(services.compactMap(\.text), id: \.self) { Text($0) } }
                    }
                    if let object = try? JSONSerialization.jsonObject(with: selected.data) as? [String: Any] {
                        let pending = HomeBundledDefinition.outstandingControls(in: object)
                        if !pending.isEmpty {
                            Section("Applet action availability") {
                                ForEach(pending, id: \.self) { Text($0) }
                            }
                        }
                    }
                    if let installed {
                        LabeledContent("Installed revision", value: String(installed.revision))
                        #if !os(macOS)
                        reviewActions
                        #endif
                        NavigationLink("Open installed applet") { PortableInstalledView(controller: controller, edition: installed) }
                            .accessibilityIdentifier("portable-review-open-installed")
                    } else {
                        #if !os(macOS)
                        reviewActions
                        #endif
                    }
                case .androidMonitor:
                    Text("This edition runs on an Android monitoring phone.")
                    if let url = entry.installReviewURL { ShareLink("Share install link", item: url) }
                }
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }.accessibilityIdentifier("portable-release-review-list")
        .portableMacActions(shown: hasReviewAction) { reviewActions }
        .navigationTitle(entry.name)
        .toolbar { ToolbarItem(placement: .primaryAction) { BuildStamp() } }
        .confirmationDialog("Apply reviewed update?", isPresented: $confirmingUpdate, titleVisibility: .visible) {
            if let update {
                Button("Apply revision " + String(update.targetRevision)) {
                    busy = true
                    Task {
                        defer { busy = false }
                        do { installed = try await controller.apply(update); self.update = nil }
                        catch { problem = error.localizedDescription }
                    }
                }
            }
            Button("Cancel", role: .cancel) { update = nil }
        } message: {
            if let update {
                Text("Revision \(update.from.revision) → \(update.targetRevision) · \(update.targetVersion)\nAdditional permissions: \(update.newPermissions.joined(separator: ", "))\nRemote services: \(update.remoteDisclosures.joined(separator: ", "))\nAdditional storage: \(update.addedStorage.joined(separator: ", "))")
            }
        }
        .task {
            do {
                let reviewed = try await controller.review(entry)
                if case .local(let selected) = reviewed {
                    let details = try? JSONDecoder().decode(AppletReviewDetails.self, from: selected.data)
                    let package = try JSONSerialization.jsonObject(with: selected.data) as? [String: Any] ?? [:]
                    tables = details?.processing?.tables ?? []
                    spending = try AppletReviewDetails.spendingSummary(for: package, paidFlow: details?.hasPaidFlow == true)
                }
                release = reviewed
                let library = try await controller.library()
                installed = try await library.installed(entry.key)
            } catch { problem = error.localizedDescription }
        }
    }
    private var hasReviewAction: Bool {
        guard case .local? = release else { return false }
        guard let installed else { return true }
        return installed.revision < entry.revision
    }
    @ViewBuilder private var reviewActions: some View {
        if case .local(let selected)? = release {
            if let installed {
                if installed.revision < entry.revision {
                    Button("Review update") {
                        busy = true
                        Task {
                            defer { busy = false }
                            do { update = try await controller.reviewUpdate(selected, replacing: installed); confirmingUpdate = true }
                            catch { problem = error.localizedDescription }
                        }
                    }.disabled(busy).accessibilityIdentifier("portable-review-update")
                }
            } else {
                Button(busy ? "Installing…" : "Install on this device") {
                    busy = true
                    Task {
                        defer { busy = false }
                        do { installed = try await controller.install(selected) }
                        catch { problem = error.localizedDescription }
                    }
                }.disabled(busy).accessibilityIdentifier("portable-review-install")
            }
        }
    }
}

private struct PortableCSVShare: Transferable {
    let name: String
    let read: @Sendable () async throws -> Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .commaSeparatedText) { item in try await item.read() }
            .suggestedFileName { $0.name }
    }
}

private struct PortableCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct PortableInstalledView: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    init(controller: PortableLibraryController, edition: LocalAppletLibrary.Edition) { self.controller = controller; self.edition = edition }
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingRemoval = false
    @State private var removing = false
    @State private var instance: LocalAppletLibrary.Instance?
    @State private var notice: String?
    @State private var problem: String?
    @StateObject private var execution = PortableExecutionOwner()
    private struct PhotoRequest: Identifiable {
        let id = UUID()
        let lens: String
        let size: AppletPackage.CameraSize
        let response: AsyncStream<LocalAppletLibrary.ReviewedPhoto>.Continuation
    }
    @State private var photoRequest: PhotoRequest?
    @State private var selectedPhoto: LocalAppletLibrary.GalleryPhoto?
    @Environment(\.scenePhase) private var phase
    @State private var contentRevision = 0
    private var galleries: [JSONValue] {
        guard case .array(let pieces)? = instance?.package.definition["ui"]?["card"]?["pieces"] else { return [] }
        return pieces.filter { $0["type"] == .string("gallery") && $0["only_if"] == nil }
    }
    private var tables: [JSONValue] {
        guard case .array(let tables)? = instance?.package.definition["processing"]?["tables"] else { return [] }
        return tables.filter { $0["id"]?.text != nil }
    }
    var body: some View {
        List {
            LabeledContent("Installed revision", value: String(edition.revision))
            PortablePauseControl(controller: controller, edition: edition)
            if let instance {
                if let html = instance.package.cardHTML {
                    NavigationLink("Open applet card") {
                        PortableInstalledCard(controller: controller, edition: edition, html: html)
                            .navigationTitle(edition.name)
                    }.accessibilityIdentifier("portable-installed-html-card")
                }
                #if !os(macOS)
                runActions
                #endif
                if let notice { Text(notice).accessibilityIdentifier("portable-flow-notice") }
                ForEach(Array(galleries.enumerated()), id: \.offset) { _, piece in
                    PortablePhotoGallery(controller: controller, edition: edition, piece: piece, revision: contentRevision) { selectedPhoto = $0 }
                }
                ForEach(Array(tables.enumerated()), id: \.offset) { _, schema in
                    tableLink(schema)
                }
            }
            Button("Remove", role: .destructive) { confirmingRemoval = true }
                .disabled(execution.running || removing)
                .accessibilityIdentifier("portable-installed-remove")
            if let problem { Text(problem).foregroundStyle(B.error) }
        }.accessibilityIdentifier("portable-installed-list")
        .portableMacActions(shown: !(instance?.package.runButtons.isEmpty ?? true) || !(instance?.package.csvButtons.isEmpty ?? true) || !(instance?.package.cameraItems.isEmpty ?? true) || execution.running) { runActions }
        .alert("Remove " + edition.name + "?", isPresented: $confirmingRemoval) {
            Button("Remove", role: .destructive) {
                removing = true
                Task {
                    defer { removing = false }
                    do {
                        try await controller.remove(edition)
                        dismiss()
                    } catch { problem = error.localizedDescription }
                }
            }.accessibilityIdentifier("portable-confirm-remove")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes this applet and its saved records and photos from this device.")
        }
        .navigationTitle(edition.name)
        .toolbar { ToolbarItem(placement: .primaryAction) { BuildStamp() } }
        .coveringBuildStampSurface(photoRequest != nil || selectedPhoto != nil)
        .task { await reload() }
        #if os(iOS)
        .fullScreenCover(item: $photoRequest) { cameraView($0).buildStampSurface() }
        .fullScreenCover(item: $selectedPhoto) { expandedPhoto($0).buildStampSurface() }
        #else
        .bridgitDialog(item: $photoRequest) { cameraView($0).buildStampSurface() }
        .bridgitDialog(item: $selectedPhoto) { expandedPhoto($0).buildStampSurface() }
        #endif
        .onChange(of: phase) { _, phase in
            if phase == .background && photoRequest != nil { execution.cancel() }
        }
        .onDisappear { execution.cancel() }
    }
    @ViewBuilder private func tableLink(_ schema: JSONValue) -> some View {
        if let table = schema["id"]?.text {
            let label = schema["label"]?.text ?? table
            let identity = edition.id + ":" + String(edition.revision) + ":" + table
            NavigationLink("Records · " + label) {
                PortableRecordsView(controller: controller, edition: edition, table: table, schema: schema,
                                    columnOrder: instance?.columnOrder[table]).id(identity)
            }.accessibilityIdentifier("portable-records-open-" + table)
        }
    }
    @ViewBuilder private var runActions: some View {
        if let instance {
            ForEach(Array(instance.package.cameraItems.enumerated()), id: \.offset) { _, item in
                Button(item["label"]?.text ?? "Take photo", systemImage: "camera") {
                    execution.start {
                        do {
                            try await controller.capturePhoto(item, edition: edition) { lens, size in
                                try await requestPhoto(lens: lens, size: size)
                            }
                            contentRevision += 1
                            notice = "Photo saved."
                        } catch is CancellationError { } catch { problem = error.localizedDescription }
                    }
                }.disabled(execution.running || controller.isPaused(edition))
                .accessibilityIdentifier("portable-camera-" + (item["id"]?.text ?? "photo"))
            }
            ForEach(Array(instance.package.csvButtons.enumerated()), id: \.offset) { _, button in
                let share = PortableCSVShare(name: button.table + ".csv") { [controller, edition] in
                    try await controller.exportCSV(button, edition: edition)
                }
                ShareLink(item: share, preview: SharePreview(button.title)) {
                    Label(button.label, systemImage: "square.and.arrow.up")
                }
                .accessibilityIdentifier("portable-share-csv-" + button.table)
            }
            ForEach(instance.package.runButtons, id: \.flow) { button in
                Button(button.label) {
                    execution.start {
                        do {
                            _ = try await controller.run(button.flow, edition: edition) { message in
                                await MainActor.run { notice = message.text }
                            }
                            await reload()
                        } catch is CancellationError {
                            // Owner/API pause is an intentional stop, not a failed run.
                            await reload()
                        } catch { problem = error.localizedDescription }
                    }
                }.disabled(execution.running || controller.isPaused(edition)).accessibilityIdentifier("portable-run-" + button.flow)
            }
            if execution.running {
                Button(execution.cancelling ? "Stopping…" : "Cancel") { execution.cancel() }
                    .disabled(execution.cancelling).accessibilityIdentifier("portable-run-cancel")
            }
        }
    }
    private func expandedPhoto(_ photo: LocalAppletLibrary.GalleryPhoto) -> some View {
        VStack {
            HStack {
                if !photo.caption.isEmpty { Text(photo.caption).font(.headline) }
                Spacer()
                BuildStamp()
                Button("Done") { selectedPhoto = nil }
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("portable-photo-close")
            }
            PortablePhoto(controller: controller, edition: edition, photo: photo, thumbnail: false)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }.padding()
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 360)
        #endif
    }

    private func cameraView(_ request: PhotoRequest) -> some View {
        DeviceCameraView(requestedLens: request.lens) { data, actualLens in
            guard photoRequest?.id == request.id else { return }
            do {
                let large = request.size == .large
                let jpeg = try LocalAppletModel.jpeg(data, maxEdge: large ? 2048 : 1280, quality: large ? 0.90 : 0.85)
                request.response.yield(.init(jpeg: jpeg, camera: actualLens))
            } catch { problem = error.localizedDescription }
            request.response.finish()
        }.onDisappear { request.response.finish() }
    }

    @MainActor private func requestPhoto(lens: String, size: AppletPackage.CameraSize) async throws -> LocalAppletLibrary.ReviewedPhoto {
        try Task.checkCancellation()
        guard phase == .active else { throw CancellationError() }
        let (stream, response) = AsyncStream<LocalAppletLibrary.ReviewedPhoto>.makeStream(bufferingPolicy: .bufferingOldest(1))
        let request = PhotoRequest(lens: lens, size: size, response: response)
        photoRequest = request
        defer {
            response.finish()
            if photoRequest?.id == request.id { photoRequest = nil }
        }
        for await photo in stream { return photo }
        throw CancellationError()
    }
    @MainActor private func reload() async {
        do {
            try await controller.loadInstalled()
            instance = try await controller.open(edition)
            contentRevision += 1
        } catch { problem = error.localizedDescription }
    }
}

private struct PortablePauseControl: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    @State private var changing = false
    @State private var problem: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(controller.isPaused(edition) ? "Paused" : "Running")
                    .foregroundStyle(B.onSurfaceVariant)
                    .accessibilityIdentifier("portable-status-" + edition.id)
                Spacer()
                Button(controller.isPaused(edition) ? "Resume" : "Pause") {
                    changing = true
                    Task {
                        defer { changing = false }
                        do {
                            try await controller.setPaused(!controller.isPaused(edition), edition: edition)
                            problem = nil
                        } catch { problem = error.localizedDescription }
                    }
                }
                .disabled(changing)
                .accessibilityLabel((controller.isPaused(edition) ? "Resume " : "Pause ") + edition.name)
                .accessibilityIdentifier("portable-pause-" + edition.id)
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }
    }
}

/// One card presentation owns its reviewed captures; closing it revokes every reference.
private struct PortableInstalledCard: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    let html: String
    @StateObject private var captures = PortableCardCaptureSession()
    @Environment(\.scenePhase) private var phase
    private struct PhotoRequest: Identifiable {
        let id = UUID()
        let lens: String
        let countdown: Int
        let instructions: String?
        let response: AsyncStream<LocalAppletLibrary.ReviewedPhoto>.Continuation
    }
    @State private var photoRequest: PhotoRequest?
    private enum VideoAnswer { case captured(PortableCardCaptureSession.ReviewedMovie), reviewed(Bool) }
    private struct VideoRequest: Identifiable {
        enum Mode { case record(String, Int, Int, Int, String?), review(PortableCardMovie) }
        let id = UUID()
        let mode: Mode
        let response: AsyncStream<VideoAnswer>.Continuation
    }
    @State private var videoRequest: VideoRequest?
    var body: some View {
        InstalledAppletCardWebView(html: html) { request in
            try await captures.action(request, authorize: {
                try controller.requireCurrent()
                let instance = try await controller.open(edition)
                try controller.requireCurrent()
                return instance.package
            }, table: { request in
                let library = try await controller.library()
                try Task.checkCancellation()
                let result = try await library.cardTableAction(request, in: edition)
                try controller.requireCurrent()
                return result
            }, reviewPhoto: { lens, countdown, instructions in
                try await reviewPhoto(lens: lens, countdown: countdown, instructions: instructions)
            }, reviewVideo: { lens, seconds, countdown, count, instructions in
                guard case .captured(let result) = try await reviewVideo(.record(lens, seconds, countdown, count, instructions)) else { throw CancellationError() }
                return result
            }, reviewExistingVideo: { movie in
                guard case .reviewed(let deleted) = try await reviewVideo(.review(movie)) else { throw CancellationError() }
                return deleted
            })
        }
        .onAppear { if phase == .active { captures.activate() } }
        .onDisappear {
            // A full-screen iPhone camera temporarily covers this card. Its own
            // dismissal finishes the request; navigating away closes the session.
            if photoRequest == nil && videoRequest == nil { captures.close() }
        }
        .onChange(of: phase) { _, value in
            #if os(macOS)
            if value != .active { photoRequest?.response.finish(); videoRequest?.response.finish(); captures.close() }
            else { captures.activate() }
            #else
            if value == .background { photoRequest?.response.finish(); videoRequest?.response.finish(); captures.close() }
            else if value == .active { captures.activate() }
            #endif
        }
        #if os(iOS)
        .fullScreenCover(item: $photoRequest, content: photoView)
        .fullScreenCover(item: $videoRequest, content: videoView)
        #else
        .bridgitDialog(item: $photoRequest, content: photoView)
        .bridgitDialog(item: $videoRequest, content: videoView)
        #endif
    }
    private func videoView(_ request: VideoRequest) -> some View {
        Group {
            switch request.mode {
            case .record(let lens, let seconds, let countdown, let count, let instructions):
                PortableVideoRecordView(name: edition.name, lens: lens, seconds: seconds, countdown: countdown, frameCount: count, instructions: instructions) { result in
                    guard videoRequest?.id == request.id else { return }
                    request.response.yield(.captured(result)); request.response.finish()
                }
            case .review(let movie):
                PortableVideoReviewView(movie: movie) { deleted in
                    guard videoRequest?.id == request.id else { return }
                    request.response.yield(.reviewed(deleted)); request.response.finish()
                }
            }
        }.onDisappear { request.response.finish() }
    }
    @MainActor private func reviewVideo(_ mode: VideoRequest.Mode) async throws -> VideoAnswer {
        guard phase == .active, photoRequest == nil, videoRequest == nil else { throw AppletRuntimeError.invalid("The camera is unavailable or another review is open.") }
        let (stream, continuation) = AsyncStream<VideoAnswer>.makeStream(bufferingPolicy: .bufferingOldest(1))
        let request = VideoRequest(mode: mode, response: continuation); videoRequest = request
        defer { continuation.finish(); if videoRequest?.id == request.id { videoRequest = nil } }
        for await answer in stream { return answer }
        throw CancellationError()
    }
    private func photoView(_ request: PhotoRequest) -> some View {
        PortableReviewedPhotoView(name: edition.name, lens: request.lens, countdown: request.countdown, instructions: request.instructions) { data, lens in
            guard photoRequest?.id == request.id else { return }
            request.response.yield(.init(jpeg: data, camera: lens))
            request.response.finish()
        }.onDisappear { request.response.finish() }
    }
    @MainActor private func reviewPhoto(lens: String, countdown: Int, instructions: String?) async throws -> LocalAppletLibrary.ReviewedPhoto {
        guard phase == .active, photoRequest == nil, videoRequest == nil else { throw AppletRuntimeError.invalid("The camera is unavailable or another review is open.") }
        let (stream, continuation) = AsyncStream<LocalAppletLibrary.ReviewedPhoto>.makeStream(bufferingPolicy: .bufferingOldest(1))
        let request = PhotoRequest(lens: lens, countdown: countdown, instructions: instructions, response: continuation)
        photoRequest = request
        defer { continuation.finish(); if photoRequest?.id == request.id { photoRequest = nil } }
        for await photo in stream { return photo }
        throw CancellationError()
    }
}

private struct PortablePhotoGallery: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    let piece: JSONValue
    let revision: Int
    @State private var photos: [LocalAppletLibrary.GalleryPhoto]?
    let open: (LocalAppletLibrary.GalleryPhoto) -> Void
    @State private var problem: String?

    var body: some View {
        Group {
            if let problem { Text(problem).foregroundStyle(B.error) }
            else if let photos {
                if photos.isEmpty { Text(piece["empty"]?.text ?? "No photos yet.").foregroundStyle(B.onSurfaceVariant) }
                else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), alignment: .leading) {
                        ForEach(photos) { photo in
                            Button { open(photo) } label: {
                                VStack(alignment: .leading) {
                                    Color.clear.aspectRatio(1, contentMode: .fit)
                                        .overlay { PortablePhoto(controller: controller, edition: edition, photo: photo, thumbnail: true) }
                                        .clipped()
                                    if !photo.caption.isEmpty { Text(photo.caption).font(.caption).lineLimit(1) }
                                }
                            }.buttonStyle(.plain)
                            .accessibilityLabel(photo.caption.isEmpty ? "Photo" : "Photo, " + photo.caption)
                            .accessibilityIdentifier("portable-gallery-photo-" + photo.id)
                        }
                    }
                }
            } else { ProgressView() }
        }
        .task(id: revision) {
            do { photos = try await controller.gallery(piece, edition: edition); problem = nil }
            catch { if !Task.isCancelled { photos = nil; problem = error.localizedDescription } }
        }

    }
}

private struct PortablePhoto: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    let photo: LocalAppletLibrary.GalleryPhoto
    let thumbnail: Bool
    @State private var image: CGImage?
    @State private var problem: String?

    var body: some View {
        Group {
            if let image {
                Image(image, scale: 1, label: Text("Photo")).resizable().aspectRatio(contentMode: thumbnail ? .fill : .fit)
                    .accessibilityIdentifier(thumbnail ? "portable-photo-thumbnail" : "portable-photo-expanded")
            } else if let problem { Text(problem).font(.caption) }
            else { ProgressView() }
        }
        .task(id: photo.reference) {
            image = nil; problem = nil
            do {
                let data = try await controller.photo(photo.reference, edition: edition)
                guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                      let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: thumbnail ? 384 : 4096
                      ] as CFDictionary) else { throw AppletRuntimeError.invalid("Photo unavailable") }
                try controller.requireCurrent()
                image = decoded
            } catch { if !Task.isCancelled { problem = error.localizedDescription } }
        }
    }
}

private struct PortableRecordColumn: Identifiable {
    let id: String
    let label: String
    let type: String
}

private struct PortableRecordsView: View {
    @ObservedObject var controller: PortableLibraryController
    let edition: LocalAppletLibrary.Edition
    let table: String
    let schema: JSONValue
    let columnOrder: [String]?
    @State private var rows: [[String: JSONValue]] = []
    @State private var next: String?
    @State private var problem: String?
    @State private var loading = false
    @State private var deleting = false
    @State private var rowToDelete: String?
    @State private var confirmRow = false
    @State private var confirmAll = false
    @State private var exporting = false
    @State private var showingExporter = false
    @State private var exportDocument: PortableCSVDocument?
    @StateObject private var load = PortableExecutionOwner()
    private var columns: [PortableRecordColumn] {
        let fields = schema["columns"]?.fields ?? [:]
        var seen = Set<String>()
        let ordered = (columnOrder ?? []).filter { fields[$0] != nil && seen.insert($0).inserted }
        let remainder = fields.keys.filter { !seen.contains($0) }.sorted()
        return (ordered + remainder).map { id in
            let field = fields[id]
            return PortableRecordColumn(id: id, label: field?["label"]?.text ?? id.replacingOccurrences(of: "_", with: " ").capitalized,
                                        type: field?["type"]?.text ?? "")
        }
    }
    private var title: String { schema["label"]?.text ?? table }
    var body: some View {
        recordsContainer.portableMacActions(shown: next != nil) { moreButton }
        .fileExporter(isPresented: $showingExporter, document: exportDocument, contentType: .commaSeparatedText,
                      defaultFilename: table + ".csv") { result in
            if case .failure(let error) = result { problem = error.localizedDescription }
            exportDocument = nil
        }
        .alert("Delete this row?", isPresented: $confirmRow) {
            Button("Delete", role: .destructive) {
                guard let id = rowToDelete, !loading, !load.running, !deleting else { return }
                rowToDelete = nil; deleting = true
                Task {
                    defer { deleting = false }
                    do { try await deleteRow(id) } catch { problem = error.localizedDescription }
                }
            }.accessibilityIdentifier("portable-record-confirm-delete")
            Button("Cancel", role: .cancel) { rowToDelete = nil }
        } message: { Text("It is deleted from " + title + " on this device, along with any photo only it used. This cannot be undone.") }
        .alert("Delete all rows of " + title + "?", isPresented: $confirmAll) {
            Button("Delete all", role: .destructive) {
                guard !loading && !load.running && !deleting else { return }
                deleting = true
                Task {
                    defer { deleting = false }
                    do {
                        try await controller.deleteOwnerRows(edition: edition, table: table)
                        await reload()
                    } catch { problem = error.localizedDescription }
                }
            }.accessibilityIdentifier("portable-record-confirm-delete-all")
            Button("Cancel", role: .cancel) {}
        } message: { Text("They are deleted from this device, along with photos only they used. This cannot be undone.") }
        .navigationTitle(title)
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack { Spacer(); BuildStamp() }.padding(.horizontal, 16).padding(.vertical, 4)
        }
        .task { if rows.isEmpty { await page() } }
        .onDisappear { load.cancel() }
    }
    @ViewBuilder private var recordsContainer: some View {
        #if os(macOS)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                recordsContent
                if let problem { Text(problem).foregroundStyle(B.error) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
        }
        #else
        List {
            recordsContent
            if next != nil { moreButton }
            if let problem { Text(problem).foregroundStyle(B.error) }
        }
        #endif
    }
    @ViewBuilder private var recordsContent: some View {
            if !rows.isEmpty {
                Section {
                    Button("Delete all rows", role: .destructive) { confirmAll = true }
                        .disabled(deleting || loading || load.running).accessibilityIdentifier("portable-record-delete-all-" + table)
                    Button("Export CSV", systemImage: "square.and.arrow.up") {
                        exporting = true
                        Task {
                            defer { exporting = false }
                            do {
                                let data = try await controller.exportOwnerTableCSV(edition: edition, table: table)
                                exportDocument = PortableCSVDocument(data: data); showingExporter = true
                            } catch { problem = error.localizedDescription }
                        }
                    }.disabled(exporting || deleting).accessibilityIdentifier("portable-record-export-" + table)
                    Text("CSV excludes photos and structured data. Limit: 10,000 rows or 20 MB.")
                        .font(.footnote).foregroundStyle(B.onSurfaceVariant)
                }
            }
            Section(title + " · newest first") {
                if rows.isEmpty && !loading && problem == nil { Text("No rows yet.") }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(preview(row)).font(.headline).accessibilityIdentifier("portable-record-" + table)
                        if let saved = portableRecordDate(row["at"]) { Text(saved).font(.subheadline).foregroundStyle(B.onSurfaceVariant) }
                        HStack {
                            if let id = row["id"]?.text {
                                Button(role: .destructive) { rowToDelete = id; confirmRow = true } label: {
                                    Text("Delete").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                                }
                                .buttonStyle(.borderless).disabled(deleting || loading || load.running)
                                .accessibilityIdentifier("portable-record-delete-" + table)
                                #if os(macOS)
                                .accessibilityLabel("Delete " + preview(row))
                                #endif
                            }
                            Spacer()
                            NavigationLink {
                                PortableRecordDetail(title: title, columns: columns, row: row) {
                                    guard let id = row["id"]?.text else { throw AppletRuntimeError.invalid("This row has no identifier.") }
                                    guard !loading && !load.running && !deleting else { throw AppletRuntimeError.invalid("Wait for the current records operation to finish.") }
                                    deleting = true
                                    defer { deleting = false }
                                    try await deleteRow(id)
                                }
                            } label: {
                                Text("Open").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                            }.buttonStyle(.borderless).disabled(deleting || loading || load.running).accessibilityIdentifier("portable-record-open-" + table)
                            #if os(macOS)
                            .accessibilityLabel("Open " + preview(row))
                            #endif
                        }
                    }
                    #if os(macOS)
                    Divider()
                    #endif
                }
            }
    }
    private func preview(_ row: [String: JSONValue]) -> String {
        for column in columns {
            guard let value = row[column.id], value != .null else { continue }
            let text = portableRecordDisplay(value, type: column.type).trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return String(text.prefix(120)) }
        }
        return "(empty row)"
    }
    @MainActor private func reload() async {
        rows = []; next = nil; problem = nil
        await page()
    }
    @MainActor private func deleteRow(_ id: String) async throws {
        try await controller.deleteOwnerRow(id, edition: edition, table: table)
        await reload()
    }
    private var moreButton: some View {
        Button("Show more") { load.start { await page() } }
            .disabled(loading || load.running || deleting).accessibilityIdentifier("portable-records-more")
    }
    @MainActor private func page() async {
        guard !loading else { return }; loading = true
        defer { loading = false }
        do {
            let page = try await controller.rowsPage(edition, table: table, before: next)
            try Task.checkCancellation()
            rows += page.rows; next = page.next
        } catch is CancellationError { }
        catch { problem = error.localizedDescription }
    }
}

private struct PortableRecordDetail: View {
    let title: String
    let columns: [PortableRecordColumn]
    let row: [String: JSONValue]
    let delete: @MainActor () async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingDelete = false
    @State private var deleting = false
    @State private var problem: String?
    var body: some View {
        detailContainer
        .alert("Delete this row?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                deleting = true
                Task {
                    defer { deleting = false }
                    do { try await delete(); dismiss() } catch { problem = error.localizedDescription }
                }
            }.accessibilityIdentifier("portable-record-detail-confirm-delete")
            Button("Cancel", role: .cancel) {}
        } message: { Text("It is deleted from " + title + " on this device, along with any photo only it used. This cannot be undone.") }
        .accessibilityIdentifier("portable-record-detail-list")
        .navigationTitle(title)
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack { Spacer(); BuildStamp() }.padding(.horizontal, 16).padding(.vertical, 4)
        }
    }
    @ViewBuilder private var detailContainer: some View {
        #if os(macOS)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) { detailContent }
                .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        }
        #else
        List { detailContent }
        #endif
    }
    @ViewBuilder private var detailContent: some View {
            ForEach(columns) { column in
                VStack(alignment: .leading, spacing: 4) {
                    Text(column.label).font(.headline)
                    Text(row[column.id].map { portableRecordDisplay($0, type: column.type) } ?? "—").textSelection(.enabled)
                }
            }
            if let saved = portableRecordDate(row["at"]) {
                Text("Saved " + saved + portableRecordActor(row["by"]))
                    .foregroundStyle(B.onSurfaceVariant).accessibilityIdentifier("portable-record-saved")
            }
            DisclosureGroup("Technical details") {
                ForEach(row.keys.sorted(), id: \.self) { key in
                    LabeledContent(key, value: portableRecordDisplay(row[key]!, type: ""))
                }
            }
            if row["id"]?.text != nil {
                Button("Delete", role: .destructive) { confirmingDelete = true }
                    .disabled(deleting).accessibilityIdentifier("portable-record-detail-delete")
            }
            if let problem { Text(problem).foregroundStyle(B.error) }
    }
}

private func portableRecordDisplay(_ value: JSONValue, type: String) -> String {
    if value == .null { return "—" }
    if type == "time", let formatted = portableRecordDate(value) { return formatted }
    if case .bool(let yes) = value { return yes ? "Yes" : "No" }
    if let text = value.text { return text }
    let data = try? JSONEncoder().encode(value)
    return data.map { String(decoding: $0, as: UTF8.self) } ?? ""
}

private func portableRecordDate(_ value: JSONValue?) -> String? {
    guard let value else { return nil }
    let date: Date?
    if case .number(let milliseconds) = value, milliseconds.isFinite {
        date = Date(timeIntervalSince1970: milliseconds / 1000)
    } else if let text = value.text {
        date = ISO8601DateFormatter().date(from: text)
    } else { date = nil }
    return date?.formatted(date: .abbreviated, time: .shortened)
}

private func portableRecordActor(_ value: JSONValue?) -> String {
    switch value?["type"]?.text {
    case "flow": return " by the applet's workflow"
    case "card": return " from its card"
    case "agent": return " by its control agent"
    default: return ""
    }
}


private extension View {
    /// Mac List exposes action rows as selectable rows, so keep native actions outside its row tree.
    @ViewBuilder func portableMacActions<Actions: View>(shown: Bool, @ViewBuilder actions: () -> Actions) -> some View {
        #if os(macOS)
        safeAreaInset(edge: .bottom, spacing: 0) {
            if shown {
                VStack(alignment: .leading, spacing: 8) { actions() }
                    .buttonStyle(.bordered).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16).background(B.surface)
            }
        }
        #else
        self
        #endif
    }
}

extension EnvironmentValues {
    @Entry var manageAppletDevices: (() -> Void)? = nil
}

/// Metadata/review comes from the signed app's canonical resources, not a fabricated registry edition.
struct HomeBundledDefinition: Identifiable {
    let id: String
    let name: String
    let summary: String
    let icon: String
    var isPet: Bool { id == "dog-monitor" }
    static let all: [Self] = [.init(id: "dog-monitor", name: "Pet monitor", summary: "Photo checks on your pet.", icon: "pawprint.fill")] + BundledApplet.catalog.map {
        .init(id: $0.id, name: $0.name, summary: $0.subtitle, icon: $0.icon)
    }
    func definition(bundle: Bundle = .main) throws -> [String: Any] {
        guard let url = bundle.url(forResource: id, withExtension: "json", subdirectory: "Applets") else {
            throw AppletRuntimeError.unavailable("The bundled definition is missing.")
        }
        return try Self.read(Data(contentsOf: url), expectedID: id)
    }
    static func read(_ data: Data, expectedID: String) throws -> [String: Any] {
        guard data.count <= 131072,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              value["format"] as? String == "bridgit-template/1", value["kind"] as? String == "applet",
              value["id"] as? String == expectedID else { throw AppletRuntimeError.invalid("The bundled definition has a different identity.") }
        return value
    }
    /// Use the maintained host's parsers; recognizing an action name alone does not make it supported.
    static func outstandingControls(in definition: [String: Any]) -> [String] {
        let card = (definition["ui"] as? [String: Any])?["card"] as? [String: Any] ?? [:]
        var labels: [String] = []
        if card["asset"] as? String != nil {
            return ["Cards can use declared tables and request reviewed captures where authorized. Other actions may not be available on this device."]
        }
        for piece in card["pieces"] as? [[String: Any]] ?? [] where piece["type"] as? String == "buttons" {
            for button in piece["items"] as? [[String: Any]] ?? [] {
                let action = button["do"] as? [String: Any] ?? [:]
                // PDF is deliberately absent from Apple exports, rather than a pending control.
                if let step = action["step"] as? [String: Any], step["type"] as? String == "share", step["as"] as? String == "pdf" { continue }
                // Preserve the piece's conditions and the full package/table/permission context.
                // An otherwise similar CSV/camera declaration may not pass the host parser.
                var candidate = definition
                var ui = definition["ui"] as? [String: Any] ?? [:]
                var candidateCard = card
                var candidatePiece = piece
                candidatePiece["items"] = [button]
                candidateCard["pieces"] = [candidatePiece]
                ui["card"] = candidateCard; candidate["ui"] = ui
                if let bytes = try? JSONSerialization.data(withJSONObject: candidate),
                   let package = try? AppletPackage(data: bytes),
                   !package.cameraItems.isEmpty || !package.csvButtons.isEmpty || package.runButtons.contains(where: { $0.onCard }) { continue }
                if let label = button["label"] as? String, !label.isEmpty, !labels.contains(label) { labels.append(label) }
            }
        }
        return labels
    }

    static func available(installedKeys: Set<String>) -> [Self] {
        all.filter { !installedKeys.contains($0.id) }
    }

    /// Built-in keys are fixed by the registry migrations; a publisher's same-name fork is another edition.
    func registryEntry(in page: CatalogPage?) -> CatalogEntry? {
        guard page?.scope == "public" else { return nil }
        return page?.applets.first { $0.key == id && ($0.id == id || (isPet && $0.id == "dog")) }
    }
}

/// The local Home surface owns the same account-bound library as the detailed registry route.
struct PortableHomeApplets: View {
    let cloud: WatchClient
    let session: String?
    let signIn: () -> Void
    var showLinkedDevices: (() -> Void)? = nil
    var body: some View {
        PortableHomeAppletsContent(cloud: cloud, signIn: signIn, showLinkedDevices: showLinkedDevices)
            .id(session)
    }
}

private struct HomeRegistrySelection: Identifiable {
    let entry: CatalogEntry
    var id: String { entry.key + "@" + String(entry.revision) }
}

private struct PortableHomeAppletsContent: View {
    @EnvironmentObject private var local: LocalAppletModel
    @Environment(\.manageAppletDevices) private var manageDevices
    @StateObject private var controller: PortableLibraryController
    let signIn: () -> Void
    var showLinkedDevices: (() -> Void)?
    @State private var loadedInstalled = false
    @State private var registryProblem: String?
    @State private var bundledReview: HomeBundledDefinition?
    @State private var registryReview: HomeRegistrySelection?
    @State private var showLibrary = false
    @State private var showCreation = false
    @State private var installedReview: LocalAppletLibrary.Edition?
    init(cloud: WatchClient, signIn: @escaping () -> Void, showLinkedDevices: (() -> Void)?) {
        _controller = StateObject(wrappedValue: PortableLibraryController(cloud: cloud))
        self.signIn = signIn; self.showLinkedDevices = showLinkedDevices
    }
    private var owner: String { "This " + DeviceTypeDescription.label(DeviceTypeDescription.local) }
    private var signedIn: Bool { controller.cloud.session != nil }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                SectionLabel(text: "Installed").accessibilityAddTraits(.isHeader)
                Spacer()
                if let host = local.host {
                    BuildInstructionDelivery(host: host, createApplet: true, maintainedLibrary: true)
                        .accessibilityIdentifier("home-create-applet")
                } else {
                    Button("Create an applet", systemImage: "plus") { showCreation = true }
                        .accessibilityIdentifier("home-create-applet")
                }
            }
            Text(owner).font(.caption).foregroundStyle(B.onSurfaceVariant)
            if loadedInstalled {
                if controller.editions.isEmpty { Text("No installed applets").foregroundStyle(B.onSurfaceVariant) }
                ForEach(controller.editions) { edition in
                    #if os(macOS)
                    Button { installedReview = edition } label: {
                        HStack {
                            Label(edition.name, systemImage: "square.grid.2x2").font(.headline)
                            Spacer()
                            Text("Revision " + String(edition.revision)).font(.caption)
                            Image(systemName: "chevron.right").accessibilityHidden(true)
                        }.padding(14).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)

                    }.buttonStyle(.bordered).accessibilityIdentifier("home-maintained-installed-" + edition.id)
                    #else
                    NavigationLink { PortableInstalledView(controller: controller, edition: edition) } label: {
                        HStack {
                            Label(edition.name, systemImage: "square.grid.2x2").font(.headline)
                            Spacer()
                            Text("Revision " + String(edition.revision)).font(.caption)
                            Image(systemName: "chevron.right").accessibilityHidden(true)
                        }.padding(14).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)

                    }.buttonStyle(.bordered).accessibilityIdentifier("home-maintained-installed-" + edition.id)
                    #endif
                    PortablePauseControl(controller: controller, edition: edition)
                }
            } else if !signedIn {
                Button("Sign in to view installed editions", action: signIn).accessibilityIdentifier("home-library-sign-in")
            } else { Text("Installed editions not loaded").foregroundStyle(B.onSurfaceVariant) }
            if !local.packages.isEmpty { LocalAppletsSection(showInstall: false, title: "Legacy installations") }
            SectionLabel(text: "Available").accessibilityAddTraits(.isHeader)
            ForEach(HomeBundledDefinition.available(installedKeys: Set(controller.editions.map(\.id)))) { item in
                Button {
                    if !item.isPet, let entry = item.registryEntry(in: controller.page) { registryReview = .init(entry: entry) }
                    else { bundledReview = item }
                } label: {
                    AppletCatalogRow(name: item.name, summary: item.summary,
                                     icon: item.isPet ? Image(systemName: item.icon) : Image(item.icon))
                }.buttonStyle(.plain)
                .accessibilityLabel("Review " + item.name + ". " + item.summary)
                .accessibilityIdentifier("home-available-" + item.id)
                Divider().padding(.leading, 52)
            }
            if let page = controller.page {
                let other = page.applets.filter { entry in
                    !HomeBundledDefinition.all.contains { $0.registryEntry(in: page)?.key == entry.key } &&
                    !controller.editions.contains { $0.id == entry.key && $0.revision >= entry.revision }
                }
                ForEach(other, id: \.key) { entry in
                    Button { registryReview = .init(entry: entry) } label: {
                        AppletCatalogRow(name: entry.name, summary: entry.summary, icon: Image(systemName: "square.grid.2x2"))
                    }.buttonStyle(.plain).accessibilityIdentifier("home-registry-" + entry.key)
                    Divider().padding(.leading, 52)
                }
            }
            Button(signedIn ? "Browse applet library" : "Sign in to browse applet library", systemImage: "books.vertical") {
                if signedIn { showLibrary = true } else { signIn() }
            }.accessibilityIdentifier("home-browse-library")
            if let registryProblem { Text(registryProblem).font(.caption).foregroundStyle(B.onSurfaceVariant) }
        }
        .task { await refresh() }
        .coveringBuildStampSurface(showLibrary || registryReview != nil || installedReview != nil)
        .bridgitDialog(isPresented: $showLibrary, onDismiss: { Task { await refresh() } }) {
            NavigationStack { PortableLibraryView(cloud: controller.cloud) }.buildStampSurface()
                #if os(macOS)
                .frame(minWidth: 620, minHeight: 500)
                #endif
        }
        .bridgitDialog(item: $registryReview, onDismiss: { Task { await refresh() } }) { selection in
            NavigationStack {
                PortableReleaseReview(controller: controller, entry: selection.entry)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { registryReview = nil } } }
            }.buildStampSurface()
            #if os(macOS)
            .frame(minWidth: 620, minHeight: 500)
            #endif
        }
        .bridgitDialog(item: $installedReview, onDismiss: { Task { await refresh() } }) { edition in
            NavigationStack {
                PortableInstalledView(controller: controller, edition: edition)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { installedReview = nil } } }
            }.buildStampSurface()
            #if os(macOS)
            .frame(minWidth: 620, minHeight: 500)
            #endif
        }
        .bridgitDialog(item: $bundledReview) { item in
            HomeBundledReview(item: item, showLinkedDevices: showLinkedDevices ?? manageDevices)
        }
        .bridgitDialog(isPresented: $showCreation) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 16) {
                    if let host = local.host { BuildInstructionDelivery(host: host, createApplet: true, maintainedLibrary: true) }
                    else { Text("Local agent setup is unavailable.") }
                }.padding(20).navigationTitle("Create an applet")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { showCreation = false } } }
            }
            #if os(macOS)
            .frame(minWidth: 480, minHeight: 300)
            #endif
        }
    }
    @MainActor private func refresh() async {
        guard signedIn else { loadedInstalled = false; return }
        do { try await controller.loadInstalled(); loadedInstalled = true }
        catch is CancellationError { return }
        catch { loadedInstalled = false }
        do { try await controller.catalog(); registryProblem = nil }
        catch is CancellationError { }
        catch { registryProblem = "Online library unavailable. Bundled reviews remain available." }
    }
}

struct HomeBundledReview: View {
    let item: HomeBundledDefinition
    var showLinkedDevices: (() -> Void)? = nil
    @DialogDismiss private var dismiss
    @State private var definition: [String: Any]?
    @State private var problem: String?
    private var required: [String] { (definition?["permissions"] as? [String: Any])?["required"] as? [String] ?? [] }
    private var remote: [String] { (definition?["permissions"] as? [String: Any])?["remote"] as? [String] ?? [] }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(item.summary).font(.headline)
                    if let definition {
                        LabeledContent("Bundled version", value: definition["version"] as? String ?? "Unknown")
                        SectionLabel(text: "Permissions").accessibilityAddTraits(.isHeader)
                        ForEach(required, id: \.self) { permission in
                            Text(AppletReviewPermissionDetails.all[permission]?.0 ?? permission)
                        }
                        if let details = try? JSONDecoder().decode(AppletReviewDetails.self, from: JSONSerialization.data(withJSONObject: definition)) {
                            ForEach(details.processing?.tables ?? []) { table in
                                let limits = [table.max_rows.map { String($0) + " rows" }, table.keep_days.map { String($0) + " days" }].compactMap { $0 }
                                Text(([table.label ?? table.id] + limits).joined(separator: " · "))
                            }
                        }
                        if !remote.isEmpty {
                            SectionLabel(text: "Remote services").accessibilityAddTraits(.isHeader)
                            ForEach(remote, id: \.self) { Text($0) }
                        }
                        SectionLabel(text: "Setup").accessibilityAddTraits(.isHeader)
                        if item.isPet {
                            Text("This Apple host does not provide the continuous watches and sound monitoring declared by this package.")
                            Text("Use a linked monitoring device with those capabilities. An existing monitor remains on the device that owns it.")
                            if let showLinkedDevices {
                                Button("Linked devices") { dismiss(); showLinkedDevices() }
                                    .accessibilityIdentifier("home-pet-linked-devices")
                            }
                        } else {
                            let pending = HomeBundledDefinition.outstandingControls(in: definition)
                            if !pending.isEmpty {
                                Text("Applet action availability").font(.headline)
                                ForEach(pending, id: \.self) { Text($0) }
                            }
                            Text("This is the bundled definition. Sign in to review a published registry edition for installation. Opening this review grants nothing.")
                        }
                    }
                    if let problem { Text(problem).foregroundStyle(B.error) }
                }.padding(20).frame(maxWidth: 760).frame(maxWidth: .infinity, alignment: .leading)
            }.navigationTitle(item.name)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 440)
        #endif
        .task { do { definition = try item.definition() } catch { problem = error.localizedDescription } }
    }
}

/// Owner-triggered, silent video recording. A card receives frames only after review.
@MainActor private final class PortableVideoRecorder: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    @Published var ready = false
    @Published var problem: String?
    @Published var canFlip = false
    private var input: AVCaptureDeviceInput?
    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "cc.bridgit.installed-card.video")
    private var generation = UUID()
    private var pending: CheckedContinuation<Void, Error>?
    @Published private(set) var camera = "default"
    func start(lens: String) async {
        let run = UUID(); generation = run
        #if os(iOS)
        let position: AVCaptureDevice.Position = lens == "front" ? .front : .back
        let preferred = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
        #else
        let preferred: AVCaptureDevice? = nil
        #endif
        guard let device = preferred ?? AVCaptureDevice.default(for: .video) else { problem = "No camera is connected."; return }
        let allowed = await AVCaptureDevice.requestAccess(for: .video)
        guard generation == run, !Task.isCancelled else { return }
        guard allowed else { problem = "Camera access is off. Allow Bridgit to use the camera in Settings."; return }
        if session.inputs.isEmpty {
            guard let input = try? AVCaptureDeviceInput(device: device) else { problem = "The camera could not be opened."; return }
            session.beginConfiguration()
            guard session.canSetSessionPreset(.hd1280x720), session.canAddInput(input), session.canAddOutput(output) else {
                session.commitConfiguration(); problem = "This camera cannot record 720p video."; return
            }
            session.sessionPreset = .hd1280x720; session.addInput(input); session.addOutput(output); session.commitConfiguration()
            self.input = input
            #if os(iOS)
            canFlip = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front) != nil && AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil
            #endif
            camera = device.position == .front ? "front" : (device.position == .back ? "rear" : "default")
        }
        let session = self.session
        await withCheckedContinuation { continuation in queue.async { session.startRunning(); continuation.resume() } }
        guard generation == run, !Task.isCancelled else { return }
        ready = session.isRunning
    }
    func record(seconds: Int) async throws -> PortableCardMovie {
        guard ready, pending == nil, output.connection(with: .video) != nil else { throw AppletRuntimeError.unavailable("video recording") }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bridgit-card-" + UUID().uuidString).appendingPathExtension("mov")
        let movie = PortableCardMovie(url: url)
        output.maxRecordedDuration = CMTime(value: Int64(seconds), timescale: 1)
        output.maxRecordedFileSize = 32 * 1024 * 1024
        try await withCheckedThrowingContinuation { continuation in
            pending = continuation; output.startRecording(to: url, recordingDelegate: self)
        }
        try Task.checkCancellation()
        return movie
    }
    #if os(iOS)
    func flip() {
        guard ready, pending == nil, let current = input,
              let nextDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: camera == "front" ? .back : .front),
              let next = try? AVCaptureDeviceInput(device: nextDevice) else { return }
        session.beginConfiguration(); session.removeInput(current)
        if session.canAddInput(next) { session.addInput(next); input = next; camera = nextDevice.position == .front ? "front" : "rear" }
        else { session.addInput(current) }
        session.commitConfiguration()
    }
    #endif
    func finishRecording() { if output.isRecording { output.stopRecording() } }
    func stop() {
        generation = UUID(); ready = false; finishRecording()
        let session = self.session
        queue.async { if session.isRunning { session.stopRunning() } }
    }
    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL, from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            guard let pending else { return }
            self.pending = nil
            if let error, (error as NSError).userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool != true { pending.resume(throwing: error) }
            else { pending.resume() }
        }
    }
}

/// A card can ask to use the camera, but only the owner can open it.
private struct PortableReviewedPhotoView: View {
    let name: String
    let lens: String
    let countdown: Int
    let instructions: String?
    let accept: (Data, String) -> Void
    @DialogDismiss private var dismiss
    @State private var opened = false
    var body: some View {
        Group {
            if opened {
                VStack {
                    if let instructions { Text(instructions).padding() }
                    #if os(iOS)
                    if countdown > 0 { PortableTimedPhotoView(lens: lens, countdown: countdown, accept: accept) }
                    else { DeviceCameraView(requestedLens: lens, reviewed: accept) }
                    #else
                    DeviceCameraView(requestedLens: lens, countdown: countdown, reviewed: accept)
                    #endif
                }
            } else {
                VStack(spacing: 16) {
                    Text(name).font(.headline)
                    Text("This applet would like to take a photo. You can review it before using it.")
                    if let instructions { Text(instructions).font(.callout) }
                    Button("Open camera") { opened = true }.buttonStyle(.borderedProminent)
                    Button("Cancel", role: .cancel) { dismiss() }
                }.padding()
            }
        }
    }
}

#if os(iOS)
/// UIImagePickerController cannot time its shutter, so timed requests use the
/// existing native viewfinder and retain the same explicit photo review.
private struct PortableTimedPhotoView: View {
    let lens: String
    let countdown: Int
    let accept: (Data, String) -> Void
    @DialogDismiss private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var camera = PhotoCamera()
    @State private var photo: UIImage?
    @State private var actualLens = "default"
    @State private var remaining = 0
    @State private var shutter: Task<Void, Never>?
    var body: some View {
        VStack(spacing: 14) {
            if let photo {
                Image(uiImage: photo).resizable().scaledToFit()
                HStack {
                    Button("Retake") { self.photo = nil; camera.start(preferFront: lens == "front") }
                    Button("Use photo") {
                        if let data = photo.jpegData(compressionQuality: 0.9) { accept(data, actualLens); dismiss() }
                    }.buttonStyle(.borderedProminent)
                }
            } else {
                CameraPreview(camera: camera).aspectRatio(4/3, contentMode: .fit)
                Text(camera.front ? "Front camera" : "Rear camera").font(.caption)
                if let problem = camera.problem { Text(problem).foregroundStyle(B.error) }
                Button("Flip camera") { camera.flip(persistPreference: false) }.disabled(!camera.ready || !camera.canFlip || shutter != nil)
                Button(remaining > 0 ? "Taking photo in \(remaining)" : "Take photo") {
                    shutter = Task {
                        defer { remaining = 0; shutter = nil }
                        do {
                            try await PortablePhotoCountdown.run(countdown, update: { remaining = $0 })
                            guard let image = await camera.capture(), phase == .active else { return }
                            try Task.checkCancellation()
                            actualLens = camera.front ? "front" : "rear"; photo = image; camera.stop()
                        } catch {}
                    }
                }.buttonStyle(.borderedProminent).disabled(!camera.ready || shutter != nil)
            }
            Button("Cancel", role: .cancel) { dismiss() }
        }.padding()
        .onAppear { camera.start(preferFront: lens == "front") }
        .onDisappear { shutter?.cancel(); camera.stop() }
        .onChange(of: phase) { _, state in
            if state == .background { shutter?.cancel(); camera.stop(); dismiss() }
        }
    }
}
#endif

private struct PortableVideoRecordView: View {
    let name: String
    let lens: String
    let seconds: Int
    let countdown: Int
    let frameCount: Int
    let instructions: String?
    let accept: (PortableCardCaptureSession.ReviewedMovie) -> Void
    @DialogDismiss private var dismiss
    @Environment(\.scenePhase) private var phase
    @StateObject private var camera = PortableVideoRecorder()
    @State private var work: Task<Void, Never>?
    @State private var remaining = 0
    @State private var recording = false
    @State private var movie: PortableCardMovie?
    @State private var player: AVPlayer?
    @State private var problem: String?
    @State private var opened = false
    var body: some View {
        VStack(spacing: 14) {
            Text(name).font(.headline)
            Text("Record video for this applet").font(.subheadline)
            if let instructions { Text(instructions).font(.callout) }
            if !opened {
                Text("You can review the video before using it. No audio is recorded.")
                Button("Open camera") { opened = true }.buttonStyle(.borderedProminent)
            } else if let movie {
                VideoPlayer(player: player).frame(minWidth: 320, minHeight: 240)
                HStack {
                    Button("Retake") { player?.pause(); player = nil; self.movie = nil; Task { await camera.start(lens: lens) } }
                    Button("Use video") { work = Task { await finish(movie) } }.buttonStyle(.borderedProminent).disabled(work != nil)
                }
            } else {
                PortableVideoPreview(session: camera.session).aspectRatio(16/9, contentMode: .fit).frame(minWidth: 320, minHeight: 180)
                Text(camera.camera == "front" ? "Front camera" : (camera.camera == "rear" ? "Rear camera" : "Default camera")).font(.caption)
                if recording { Button("Finish recording") { camera.finishRecording() } }
                else {
                    #if os(iOS)
                    Button("Flip camera") { camera.flip() }.disabled(!camera.canFlip || !camera.ready || work != nil)
                    #endif
                    Button(remaining > 0 ? "Recording in \(remaining)" : "Record up to \(seconds) seconds") { work = Task { await record() } }
                        .disabled(!camera.ready || work != nil)
                }
            }
            if let message = problem ?? camera.problem { Text(message).foregroundStyle(B.error) }
            Button("Cancel", role: .cancel) { dismiss() }
        }.padding()
        .task(id: opened) { if opened { await camera.start(lens: lens) } }
        .onDisappear { work?.cancel(); camera.stop(); player?.pause(); player = nil; movie = nil }
        .onChange(of: phase) { _, value in
            #if os(macOS)
            if value != .active { work?.cancel(); camera.stop(); dismiss() }
            #else
            if value == .background { work?.cancel(); camera.stop(); dismiss() }
            #endif
        }
    }
    @MainActor private func record() async {
        defer { remaining = 0; recording = false; work = nil }
        do {
            problem = nil
            for value in (0..<countdown).reversed() { remaining = value + 1; try await Task.sleep(for: .seconds(1)) }
            try Task.checkCancellation(); remaining = 0; recording = true
            let result = try await camera.record(seconds: seconds)
            try Task.checkCancellation(); camera.stop(); movie = result; player = AVPlayer(url: result.url)
        } catch is CancellationError {} catch { problem = error.localizedDescription }
    }
    @MainActor private func finish(_ movie: PortableCardMovie) async {
        defer { work = nil }
        do {
            let asset = AVURLAsset(url: movie.url)
            let duration = try await asset.load(.duration).seconds
            guard duration.isFinite, duration > 0 else { throw AppletRuntimeError.invalid("No video frames are available.") }
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 30); generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 30)
            generator.maximumSize = CGSize(width: 960, height: 960)
            var frames = [Data]()
            for index in 0..<frameCount {
                try Task.checkCancellation()
                let time = CMTime(seconds: duration * (Double(index) + 0.5) / Double(frameCount), preferredTimescale: 600)
                let image = try await generator.image(at: time).image
                let data = NSMutableData()
                guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { throw AppletRuntimeError.invalid("Could not prepare video frames.") }
                CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
                guard CGImageDestinationFinalize(destination) else { throw AppletRuntimeError.invalid("Could not prepare video frames.") }
                frames.append(data as Data)
            }
            try Task.checkCancellation(); accept(.init(movie: movie, frames: frames, camera: camera.camera)); dismiss()
        } catch is CancellationError {} catch { problem = error.localizedDescription }
    }
}

private struct PortableVideoReviewView: View {
    let movie: PortableCardMovie
    let finish: (Bool) -> Void
    @DialogDismiss private var dismiss
    @State private var player: AVPlayer?
    var body: some View {
        VStack {
            VideoPlayer(player: player).frame(minWidth: 320, minHeight: 240)
            HStack {
                Button("Keep video") { finish(false); dismiss() }
                Button("Delete video", role: .destructive) { finish(true); dismiss() }
            }
        }.padding().onAppear { player = AVPlayer(url: movie.url) }.onDisappear { player?.pause(); player = nil }
    }
}

#if os(macOS)
private struct PortableVideoPreview: NSViewRepresentable {
    let session: AVCaptureSession
    func makeNSView(context: Context) -> NSView { let view = NSView(); view.wantsLayer = true; let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspect; view.layer = layer; return view }
    func updateNSView(_ view: NSView, context: Context) { view.layer?.frame = view.bounds }
}
#else
private struct PortableVideoPreview: UIViewRepresentable {
    let session: AVCaptureSession
    final class Preview: UIView { override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self } }
    func makeUIView(context: Context) -> Preview { let view = Preview(); let layer = view.layer as! AVCaptureVideoPreviewLayer; layer.session = session; layer.videoGravity = .resizeAspect; return view }
    func updateUIView(_ view: Preview, context: Context) {}
}
#endif
