import ImageIO
import CryptoKit
import Foundation

/// The registry's hydrated definition, also used for offline applet.json files.
/// Keep the full document so installation can review every declared resource.
public struct AppletPackage: Sendable {
    public struct Media: Sendable {
        public let size: Int
        public let digest: String

        public func verify(_ data: Data) throws {
            guard data.count == size, SHA256.hash(data:data).map({ String(format:"%02x",$0) }).joined() == digest,
                  data.starts(with:[255,216,255]), data.suffix(2).elementsEqual([255,217]),
                  let source = CGImageSourceCreateWithData(data as CFData,nil),
                  let props = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
                  let width = props[kCGImagePropertyPixelWidth] as? Int,
                  let height = props[kCGImagePropertyPixelHeight] as? Int,
                  (1...4096).contains(width), (1...4096).contains(height)
            else { throw AppletRuntimeError.invalid("Invalid revision JPEG or checksum") }
        }
    }
    public let media: [String: Media]
    public let definition: JSONValue
    public let id: String
    public let version: String
    public let defaults: [String: JSONValue]
    public let iconPNG: Data?
    public let flows: [String: JSONValue]

    public init(data: Data) throws {
        try self.init(data: data, reviewingAndroidMonitor: false)
    }

    init(data: Data, reviewingAndroidMonitor: Bool) throws {
        guard data.count <= 131072 else { throw AppletRuntimeError.invalid("Package exceeds 128 KiB") }
        let doc = try JSONDecoder().decode(JSONValue.self, from: data)
        try AppletPractices.check(doc)
        guard doc["format"]?.text == "bridgit-template/1", doc["kind"]?.text == "applet",
              let id = doc["id"]?.text, id.range(of: #"^[a-z][a-z0-9-]{1,63}$"#, options: .regularExpression) != nil,
              let version = doc["version"]?.text,
              version.range(of: #"^(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})$"#, options: .regularExpression) != nil,
              let defaults = doc["defaults"]?.fields else { throw AppletRuntimeError.invalid("Invalid package header or defaults") }
        guard let runtime = doc["runtime"] else { throw AppletRuntimeError.invalid("Missing runtime") }
        let portable = ["bridgit.package.v1", "android.package.v1"].contains(runtime["adapter"]?.text)
        let monitor = runtime["adapter"] == .string("android.dog.v1") && runtime["applet_key"] == .string("dog")
        guard portable || (reviewingAndroidMonitor && monitor),
              runtime["supports_dynamic_execution"] == .bool(false),
              doc["permissions"]?["automatic_grants"] == .bool(false) else { throw AppletRuntimeError.invalid("Unsupported runtime or permission policy") }
        let icon = doc["ui"]?["icon"]
        var media = [String:Media]()
        var iconPNG: Data?
        if let assets = doc["assets"] {
            guard let entries = assets.fields, entries.count <= 16 else { throw AppletRuntimeError.invalid("Invalid assets") }
            for (name, asset) in entries {
                if asset["storage"] != nil {
                    guard asset["storage"] == .string("revision"), asset["media_type"] == .string("image/jpeg"),
                          asset.fields?.count == 4,
                          name.range(of: #"\A[a-z][a-z0-9_.-]{0,79}\z"#,options:.regularExpression) != nil,
                          let digest = asset["sha256"]?.text, digest.range(of:#"\A[a-f0-9]{64}\z"#,options:.regularExpression) != nil,
                          case .number(let size) = asset["size"], (4...4194304).contains(size), size.rounded() == size
                    else { throw AppletRuntimeError.invalid("Invalid revision asset: " + name) }
                    media[name] = Media(size:Int(size),digest:digest)
                    continue
                }
                let data: Data
                guard asset["blob"] == nil, asset["path"] == nil else { throw AppletRuntimeError.invalid("Unresolved asset: " + name) }
                if asset["media_type"]?.text == "image/png" {
                    guard let encoded = asset["base64"]?.text, encoded.utf8.count <= 65536,
                          asset["text"] == nil, let bytes = Data(base64Encoded: encoded),
                          bytes.base64EncodedString() == encoded, bytes.count >= 33,
                          asset["size"] == .number(Double(bytes.count)),
                          bytes.starts(with: [137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82]) else {
                        throw AppletRuntimeError.invalid("Invalid PNG payload: " + name)
                    }
                    for offset in [16,20] {
                        let length = bytes[offset..<offset+4].reduce(0) { $0 * 256 + Int($1) }
                        guard (1...1024).contains(length) else { throw AppletRuntimeError.invalid("Invalid PNG dimensions") }
                    }
                    data = bytes
                    if icon?.text == name { iconPNG = bytes }
                } else {
                    guard let text = asset["text"]?.text, asset["base64"] == nil else { throw AppletRuntimeError.invalid("Invalid asset: " + name) }
                    data = Data(text.utf8)
                }
                guard data.count <= 49152 else { throw AppletRuntimeError.invalid("Asset too large: " + name) }
                let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                guard asset["sha256"]?.text == digest else { throw AppletRuntimeError.invalid("Asset checksum mismatch: " + name) }
            }
        }
        guard icon == nil || iconPNG != nil else { throw AppletRuntimeError.invalid("ui.icon must name a PNG package asset") }
        self.media = media
        self.iconPNG = iconPNG
        var flows: [String: JSONValue] = [:]
        if let processing = doc["processing"] {
            guard processing.fields != nil else { throw AppletRuntimeError.invalid("Invalid processing object") }
            if let single = processing["workflow"] {
                guard processing["flows"] == nil else { throw AppletRuntimeError.invalid("Use workflow or flows") }
                flows["main"] = single
            } else if let many = processing["flows"] {
                guard case .array(let list) = many, (1...4).contains(list.count) else { throw AppletRuntimeError.invalid("Use 1–4 named flows") }
                for flow in list {
                    guard let name = flow["id"]?.text, name.range(of: #"^[a-z][a-z0-9_]{0,23}$"#, options: .regularExpression) != nil,
                          flows[name] == nil else { throw AppletRuntimeError.invalid("Invalid or duplicate flow id") }
                    flows[name] = flow
                }
            }
        }
        self.definition = doc
        self.id = id
        self.version = version
        self.defaults = defaults
        self.flows = flows
    }

    /// Only a digest-verified HTML asset explicitly selected by this revision can run as its card.
    public var cardHTML: String? {
        guard let name = definition["ui"]?["card"]?["asset"]?.text,
              let asset = definition["assets"]?[name], asset["media_type"] == .string("text/html") else { return nil }
        return asset["text"]?.text
    }

    public struct BatchButton: Sendable {
        public let flow: String
        let table: String
        let sourceLimit: Int
        let maximum: Int
        let columns: [String:String]
        let filterColumn: String
        let excluded: JSONValue
    }

    public func batchButton(_ item: JSONValue) -> BatchButton? {
        guard declaresButton(item), let action = item["do"]?.fields,
              Set(action.keys).isSubset(of:["flow","for_each","only_if","with","max","progress"]),
              let name = action["flow"]?["$flow"]?.text, action["flow"]?.fields?.count == 1,
              let flow = flows[name], flow["trigger"]?["type"] == .string("called"),
              let signature = flow["trigger"]?["with"]?.fields,
              let values = action["with"]?.fields, Set(values.keys) == Set(signature.keys),
              signature.values.allSatisfy({ $0 == .string("text") }),
              let sourceName = action["for_each"]?["$source"]?.text, action["for_each"]?.fields?.count == 1,
              let source = definition["ui"]?["card"]?["sources"]?[sourceName],
              let table = source["table"]?.text, source.fields?.keys.allSatisfy({ ["table","limit"].contains($0) }) == true,
              case .number(let limit) = source["limit"] ?? .number(20), (1...500).contains(limit), limit.rounded() == limit,
              case .number(let maximum)? = action["max"], (1...200).contains(maximum), maximum.rounded() == maximum,
              let filter = action["only_if"]?.fields, filter.count == 2,
              let column = filter["item"]?.text, let excluded = filter["is_not"] else { return nil }
        let columns = values.compactMapValues { $0.fields?.count == 1 ? $0["$item"]?.text : nil }
        guard columns.count == values.count else { return nil }
        return BatchButton(flow:name,table:table,sourceLimit:Int(limit),maximum:Int(maximum),columns:columns,filterColumn:column,excluded:excluded)
    }

    private func declaresButton(_ item: JSONValue, extra: Set<String> = []) -> Bool {
        guard case .array(let pieces)? = definition["ui"]?["card"]?["pieces"],
              item.fields?.keys.allSatisfy({ ["id","label","style","do"].contains($0) || extra.contains($0) }) == true,
              item["label"]?.text?.isEmpty == false else { return false }
        return pieces.contains { piece in
            guard piece["type"] == .string("buttons"), piece["only_if"] == nil,
                  case .array(let items)? = piece["items"] else { return false }
            return items.contains(item)
        }
    }

    public struct MicrophoneButton: Sendable {
        public let seconds: Int
        public let reply: String
    }

    public func microphoneButton(_ item: JSONValue) -> MicrophoneButton? {
        guard declaresButton(item, extra:["into"]), let reply = item["into"]?.text,
              definition["ui"]?["card"]?["sources"]?[reply] == .object(["reply":.string(reply)]),
              case .array(let grants)? = definition["permissions"]?["required"], grants.contains(.string("mic")),
              let action = item["do"], let body = action["body"], case .number(let seconds)? = body["duration_seconds"],
              seconds.rounded() == seconds, (1...30).contains(seconds),
              action == .object(["method":.string("POST"),"path":.string("/v1/mic/record"),
                    "body":.object(["duration_seconds":.number(seconds),"transcribe":.bool(true),"return_audio":.bool(false)])])
        else { return nil }
        return MicrophoneButton(seconds:Int(seconds),reply:reply)
    }

    public struct OCRButton: Sendable {
        public let reply: String
        public let input: String
    }

    public func ocrButton(_ item: JSONValue) -> OCRButton? {
        guard declaresButton(item,extra:["into"]), let reply = item["into"]?.text,
              definition["ui"]?["card"]?["sources"]?[reply] == .object(["reply":.string(reply)]),
              case .array(let scopes)? = definition["permissions"]?["required"], scopes.contains(.string("ocr")),
              let action = item["do"], let input = action["body"]?["gallery_id"]?["$input"]?.text,
              definition["config_schema"]?["properties"]?[input]?["type"] == .string("integer"),
              action == .object(["method":.string("POST"),"path":.string("/v1/ocr"),
                  "body":.object(["gallery_id":.object(["$input":.string(input)])])]) else { return nil }
        return OCRButton(reply:reply,input:input)
    }

    public struct CardDraft: Sendable {
        public let id: String
        public let label: String
        public let limit: Int
        public let source: String
    }

    public var cardDrafts: [CardDraft] {
        guard case .array(let pieces)? = definition["ui"]?["card"]?["pieces"] else { return [] }
        return pieces.compactMap { piece in
            guard piece["type"] == .string("draft"), piece["only_if"] == nil,
                  let id = piece["id"]?.text, let label = piece["label"]?.text,
                  let source = piece["value"]?["$source"]?.text, piece["value"]?.fields?.count == 1,
                  case .number(let limit)? = piece["max"], limit.rounded() == limit, (1...1024).contains(limit)
            else { return nil }
            return CardDraft(id:id,label:label,limit:Int(limit),source:source)
        }
    }

    public func isDraftSave(_ item: JSONValue) -> Bool {
        guard declaresButton(item,extra:["confirm"]), item["confirm"]?.text?.isEmpty == false,
              let action = item["do"], action.fields?.count == 2, action["record"]?.text != nil,
              let row = action["row"]?.fields, !row.isEmpty else { return false }
        let draftIDs = Set(cardDrafts.map(\.id))
        return row.values.contains { $0["$draft"]?.text.map(draftIDs.contains) == true }
            && row.values.allSatisfy { $0.fields?.count == 1 && ($0["$draft"]?.text.map(draftIDs.contains) == true || $0["$source"]?.text != nil || $0["$input"]?.text != nil) }
    }

    public enum CameraSize: String, Sendable { case medium, large }
    public struct CameraButton: Sendable {
        public let lens: String
        public let size: CameraSize
        let table: String
        let photo: String
        let values: [String:JSONValue]
    }

    public func cameraButton(_ item: JSONValue) -> CameraButton? {
        guard declaresButton(item), item["do"]?.fields?.count == 1,
              let camera = item["do"]?["camera"]?.fields,
              Set(camera.keys).isSubset(of:["table","photo","row","size","lens"]),
              let table = camera["table"]?.text, let photo = (camera["photo"] ?? .string("photo")).text,
              let values = (camera["row"] ?? .object([:])).fields, values[photo] == nil,
              let size = CameraSize(rawValue:(camera["size"] ?? .string("large")).text ?? ""),
              let lens = (camera["lens"] ?? .string("back")).text, ["front","back"].contains(lens) else { return nil }
        return CameraButton(lens:lens == "back" ? "rear" : "front",size:size,table:table,photo:photo,values:values)
    }

    public struct RunButton: Hashable, Sendable {
        public let label: String
        public let flow: String
        public let onCard: Bool
    }

    /// Foreground cooking sessions implement the single interval/observe contract.
    /// More complex flows must run through a host that implements every declared step.
    public struct CookingTiming: Equatable, Sendable {
        public let interval: Int
        public let session: Int
        public let tipGap: Int
    }
    public func cookingInterval(_ name: String) -> Int? { cookingTiming(name)?.interval }

    public func cookingTiming(_ name: String) -> CookingTiming? {
        guard let flow = flows[name]?.fields,
              flow.keys.allSatisfy({ ["id","name","trigger","steps","max_paid_per_day"].contains($0) }),
              let trigger = flow["trigger"]?.fields, trigger["type"] == .string("interval"),
              trigger.keys.allSatisfy({ ["type","seconds"].contains($0) }),
              case .number(let seconds) = trigger["seconds"] ?? .number(900),
              seconds.rounded() == seconds, (10...86400).contains(seconds),
              case .array(let steps)? = flow["steps"], steps.count == 1, let raw = steps.first, let step = try? FlowStep.hostFields(raw),
              step.keys.allSatisfy({ ["id","type","mode","session_s","tip_gap_s"].contains($0) }),
              case .number(let session) = step["session_s"] ?? .number(3600),
              case .number(let tipGap) = step["tip_gap_s"] ?? .number(45),
              session.rounded() == session, (10...3600).contains(session),
              tipGap.rounded() == tipGap, (10...3600).contains(tipGap),
              step["type"] == .string("kitchen"), step["mode"] == .string("observe") else { return nil }
        return CookingTiming(interval:Int(seconds),session:Int(session),tipGap:Int(tipGap))
    }

    public var runButtons: [RunButton] {
        var actions = [RunButton]()
        if case .array(let pieces)? = definition["ui"]?["card"]?["pieces"] {
            for piece in pieces where piece["type"]?.text == "buttons" && piece["only_if"] == nil {
                guard case .array(let items)? = piece["items"] else { continue }
                for item in items {
                    if let button = cardButton(item) { actions.append(button) }
                }
            }
        }
        for name in flows.keys.sorted() where flows[name]?["trigger"]?["type"]?.text == "button" {
            if !actions.contains(where: { $0.flow == name }) {
                actions.append(RunButton(label: flows[name]?["name"]?.text ?? name, flow: name, onCard:false))
            }
        }
        return actions
    }

    public func cardButton(_ item: JSONValue) -> RunButton? {
        guard item.fields?.keys.allSatisfy({ ["label","style","do"].contains($0) }) == true,
              let action = item["do"]?.fields, action.count == 1, let label = item["label"]?.text else { return nil }
        if action["run_workflow"] == .bool(true) {
            let main: String?
            if definition["processing"]?["workflow"] != nil { main = "main" }
            else if case .array(let named)? = definition["processing"]?["flows"] { main = named.first?["id"]?.text }
            else { main = nil }
            guard let main, flows[main]?["trigger"]?["type"] == .string("button") else { return nil }
            return RunButton(label:label, flow:main, onCard:true)
        }
        guard let reference = action["flow"]?.fields, reference.count == 1,
              let name = reference["$flow"]?.text, flows[name]?["trigger"]?["type"]?.text == "called" else { return nil }
        return RunButton(label:label, flow:name, onCard:true)
    }

    /// The caller validates the complete instance configuration against config_schema before compiling.
    public func steps(for flow: String, configuration: [String: JSONValue]) throws -> [FlowStep] {
        guard case .array(let steps)? = flows[flow]?["steps"], (1...32).contains(steps.count) else {
            throw AppletRuntimeError.invalid("Missing flow or invalid steps: " + flow)
        }
        func bind(_ value: JSONValue) throws -> JSONValue {
            switch value {
            case .object(let fields):
                if let ref = fields["$input"] {
                    guard fields.count == 1, let name = ref.text, let input = configuration[name] else {
                        throw AppletRuntimeError.invalid("Missing or mixed configuration reference")
                    }
                    return input
                }
                if fields.count == 1, let name = fields["$flow"]?.text {
                    guard flows[name] != nil else { throw AppletRuntimeError.missingReference(name) }
                    return .string(name)
                }
                return .object(try fields.mapValues(bind))
            case .array(let items): return .array(try items.map(bind))
            default: return value
            }
        }
        return try steps.map { try FlowStep(bind($0)) }
    }
}
