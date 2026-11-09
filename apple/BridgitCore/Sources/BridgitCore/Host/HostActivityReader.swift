import Foundation

/// Reads the same device-local rows as native Activity. A response carries its
/// read scope until delivery, including when wrapped by MCP or a sealed relay.
struct HostActivityReader {
    let store: AppletStore
    let authorize: () async throws -> HostAgent
    private static let exportBytes = 8 * 1024 * 1024

    func response(to request: HostRequest, streamAuthorization: (() async throws -> HostAgent)? = nil) async throws -> HostResponse {
        let caller = try await authorize()
        let roster = try await store.transferAgents()
        let names = Dictionary(uniqueKeysWithValues: roster.map {
            ($0.id, HostLogNote.scrub($0.name, max: 200))
        })
        func currentName(_ entry: HostActivityEntry) -> String? {
            entry.agentID.flatMap { names[$0] }
        }
        func fields(_ entry: HostActivityEntry) -> [String: Any] {
            HostActivityDetail(entry, currentAgentName: currentName(entry)).fields
        }

        let scope: HostLogReadScope
        var reply: HostResponse
        switch request.path {
        case "/v1/log":
            var query = try HostLogQuery(request.query, caller: caller, agents: roster)
            if query.wait > 0 {
                guard query.ascending, query.hasIDSince else {
                    throw HostLogQuery.invalid("Tail mode needs order=asc and since=<log id>.")
                }
                query.cursor = nil
            }
            let page = query.wait > 0 ? try await waitFor(query) : try await readPage(query)
            scope = page.scope
            let latestNames = query.wait > 0
                ? Dictionary(uniqueKeysWithValues: try await store.transferAgents().map { ($0.id, HostLogNote.scrub($0.name, max: 200)) })
                : names
            let entries = page.rows.map { entry in
                HostActivityDetail(entry, currentAgentName: entry.agentID.flatMap { latestNames[$0] }).fields
            }
            let next = page.rows.count == query.limit ? page.rows.last.map { HostLogQuery.encodeCursor($0.id) } : nil
            reply = try .json(["entries": entries, "next_cursor": next as Any? ?? NSNull(), "latest_id": page.latest])
        case "/v1/log/stats":
            var query = try HostLogQuery(request.query, caller: caller, agents: roster)
            if query.since == nil { query.since = .time(Date().addingTimeInterval(-86_400)) }
            query.channels = ["audit"]; query.kinds = ["request"]
            scope = try await readScope()
            reply = try .json(await statistics(query, scope: scope, names: names))
        case "/v1/log/export":
            let raw = try HostLogQuery.text(request.query["format"], name: "format") ?? "jsonl"
            guard let format = HostActivityExport.Format(rawValue: raw) else {
                throw HostLogQuery.invalid("format must be jsonl or csv.")
            }
            let query = try HostLogQuery(request.query, caller: caller, agents: roster)
            scope = try await readScope()
            let contentType = format == .csv ? "text/csv; charset=utf-8" : "application/x-ndjson"
            if let streamAuthorization {
                let reader = Self(store: store, authorize: streamAuthorization)
                let maximum = try await store.hostLogLatestID()
                reply = .init(contentType: contentType, data: Data())
                reply.stream = { write in
                    try await reader.stream(query, scope: scope, maximum: maximum, format: format, names: names, write: write)
                }
            } else {
                let bytes = try await export(query, scope: scope, format: format, names: names)
                reply = .init(contentType: contentType, data: bytes)
            }
        default:
            guard let value = request.path.split(separator: "/").last, let id = Int64(value), id >= 0 else {
                throw HostLogQuery.invalid("id must be a log entry id.")
            }
            scope = try await readScope()
            guard let entry = try await store.hostLogEntry(id, scope: scope) else {
                throw DeviceAPIError(404, "not_found", "No log entry that you can read.")
            }
            reply = try .json(fields(entry))
        }
        try await confirm(scope)
        reply.headers["Cache-Control"] = "no-store"
        let deliveryReader = reply.stream != nil ? streamAuthorization.map { Self(store: store, authorize: $0) } ?? self : self
        reply.beforeDelivery = { try await deliveryReader.confirm(scope) }
        return reply
    }

    private struct Page {
        let rows: [HostActivityEntry]
        let latest: Int64
        let scope: HostLogReadScope
    }

    private func readScope() async throws -> HostLogReadScope {
        try Task.checkCancellation()
        let agent = try await authorize()
        return try await store.hostLogReadScope(for: agent)
    }

    private func confirm(_ previous: HostLogReadScope) async throws {
        let current = try await readScope()
        guard current == previous || current.ownID == nil else {
            throw DeviceAPIError(403, "log_visibility_changed", "Activity visibility changed during this request.")
        }
    }

    private func readPage(_ query: HostLogQuery) async throws -> Page {
        let scope = try await readScope()
        let rows = try await store.hostLogRows(query, scope: scope)
        return .init(rows: rows, latest: try await store.hostLogLatestID(), scope: scope)
    }

    private func waitFor(_ query: HostLogQuery) async throws -> Page {
        let changes = await store.changes(to: .activity)
        let first = try await readPage(query)
        guard first.rows.isEmpty else { return first }
        return try await withThrowingTaskGroup(of: Page?.self) { group in
            group.addTask {
                for await _ in changes {
                    try Task.checkCancellation()
                    let page = try await readPage(query)
                    if !page.rows.isEmpty { return page }
                }
                return nil
            }
            group.addTask {
                try await Task.sleep(for: .seconds(query.wait))
                return nil
            }
            let found = try await group.next() ?? nil
            group.cancelAll()
            if let found { return found }
            return try await readPage(query)
        }
    }

    private func visit(_ initial: HostLogQuery, scope: HostLogReadScope, maximum snapshot: Int64? = nil,
                       _ consume: (HostActivityEntry) async throws -> Void) async throws {
        var query = initial
        query.ascending = true
        query.limit = HostLogQuery.maximumPage
        let maximum: Int64
        if let snapshot { maximum = snapshot } else { maximum = try await store.hostLogLatestID() }
        while true {
            try await confirm(scope)
            let rows = try await store.hostLogRows(query, scope: scope, maximumID: maximum)
            for row in rows {
                try Task.checkCancellation()
                try await consume(row)
            }
            guard rows.count == query.limit, let cursor = rows.last?.id else { return }
            query.cursor = cursor
        }
    }

    private func export(_ query: HostLogQuery, scope: HostLogReadScope, format: HostActivityExport.Format,
                        names: [String: String]) async throws -> Data {
        var data = HostActivityExport.header(for: format)
        try await visit(query, scope: scope) { entry in
            let line = try HostActivityExport.line(entry, format: format, currentAgentName: entry.agentID.flatMap { names[$0] })
            guard line.count <= Self.exportBytes - data.count else {
                throw DeviceAPIError(413, "export_too_large", "This export exceeds the relay result limit. Narrow its filters or date range.")
            }
            data.append(line)
        }
        return data
    }

    private func stream(_ initial: HostLogQuery, scope: HostLogReadScope, maximum: Int64,
                        format: HostActivityExport.Format, names: [String: String],
                        write: (Data) async throws -> Void) async throws {
        var chunk = HostActivityExport.header(for: format)
        try await visit(initial, scope: scope, maximum: maximum) { entry in
            chunk.append(try HostActivityExport.line(entry, format: format, currentAgentName: entry.agentID.flatMap { names[$0] }))
            if chunk.count >= 32 * 1024 {
                try await confirm(scope)
                try await write(chunk)
                chunk.removeAll(keepingCapacity: true)
            }
        }
        try await confirm(scope)
        if !chunk.isEmpty { try await write(chunk) }
        try await confirm(scope)
    }

    private struct Counts {
        var count = 0
        var errors = 0
        var durations: [Int64] = []
        var bytes: Int64?
        mutating func add(_ row: HostActivityEntry) {
            count += 1
            if row.status >= 400 { errors += 1 }
            if let sample = row.requestFacts?.durationMS { durations.append(sample) }
            if let sample = row.requestFacts?.bytesOut { bytes = (bytes ?? 0) + Int64(sample) }
        }
        func object(key: String) -> [String: Any] {
            let samples = durations.sorted()
            func percentile(_ fraction: Double) -> Any {
                guard !samples.isEmpty else { return NSNull() }
                return samples[Int(ceil(fraction * Double(samples.count))) - 1]
            }
            return ["key": key, "count": count, "errors": errors, "p50_ms": percentile(0.5),
                    "p95_ms": percentile(0.95), "bytes_out": bytes as Any? ?? NSNull()]
        }
    }

    private func statistics(_ query: HostLogQuery, scope: HostLogReadScope, names: [String: String]) async throws -> [String: Any] {
        var byAgent: [String: Counts] = [:], byEndpoint: [String: Counts] = [:]
        var entries = 0
        try await visit(query, scope: scope) { row in
            entries += 1
            let agent = row.isAnonymousRequest ? "(no key)" : row.agentID.flatMap { names[$0] } ?? row.agent
            let endpoint = row.requestFacts.map { $0.method + " " + $0.endpoint } ?? "(unreported)"
            byAgent[agent, default: Counts()].add(row)
            byEndpoint[endpoint, default: Counts()].add(row)
        }
        return ["by_agent": byAgent.keys.sorted().map { byAgent[$0]!.object(key: $0) },
                "by_endpoint": byEndpoint.keys.sorted().map { byEndpoint[$0]!.object(key: $0) }, "entries": entries]
    }
}
