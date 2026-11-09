import BridgitCore
import Foundation

/// The pure parts of Applets' three tabs, matching the APK's AppletLists (ui/applets/AppletLists.kt): which web
/// portals the Portals tab offers, which pages stay inside it, and which cards the Cards library lists.
enum AppletsSection: String, CaseIterable, Identifiable {
    case portals = "Portals", config = "Applets", cards = "Cards"
    var id: String { rawValue }
}

enum AppletLists {
    static let portalPage = "https://bridgit.cc/myapps/"
    /// Applets › Config › How to build a Bridgit applet (ApiDocs.APPLET_URL on Android).
    static let buildGuide = URL(string: "https://bridgit.cc/recipes/build-an-applet/")!

    /// One web portal into an applet: `id` is its section on bridgit.cc/myapps ("overview" = no section).
    struct Portal: Identifiable, Hashable {
        var id: String
        var name: String
        var url: URL
    }

    /// The APK places Pet monitor first, then package pages by name. Only pages for the selected installation belong here.
    static func portals(install: String?, account: [AppletSummary]) -> [Portal] {
        let rows = account.filter { $0.phone == install }
        func page(id: String, name: String) -> Portal {
            var parts = URLComponents(string: portalPage)!
            if let install, !install.isEmpty { parts.queryItems = [URLQueryItem(name: "device", value: install)] }
            parts.fragment = id
            return Portal(id: id, name: name, url: parts.url!)
        }
        var packages: [String: Portal] = [:]
        for row in rows where row.applet.range(of: #"^pkg\.[a-z][a-z0-9-]{1,63}$"#, options: .regularExpression) != nil {
            let label = row.friendly_name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let fallback = row.applet.dropFirst(4).replacingOccurrences(of: "-", with: " ")
            let name = label.isEmpty ? fallback.prefix(1).uppercased() + fallback.dropFirst() : label
            packages[row.applet] = page(id: row.applet, name: name)
        }
        let pet = rows.contains { $0.applet == "dog" }
        return (pet ? [page(id: "dog", name: "Pet monitor")] : []) + packages.values.sorted {
            ($0.name.lowercased(), $0.id) < ($1.name.lowercased(), $1.id)
        }
    }

    /// Pages a portal may show in place: bridgit.cc and its subdomains, https only. Anything else opens outside.
    static func staysInPortal(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else { return false }
        return host == "bridgit.cc" || host.hasSuffix(".bridgit.cc")
    }

    /// One card in the library; `html` is the whole page, as an agent would post it.
    struct Card: Identifiable, Hashable {
        var id: String
        var title: String
        var summary: String
        var html: String

        /// Preview answers remain local; do not claim a live agent received them.
        var previewHTML: String {
            guard ["html-wrapup", "html-photo-check"].contains(id) else { return html }
            return html.replacingOccurrences(of: "Your answer reached the agent.", with: "Your answer stays on this device.")
        }
    }

    /// The HTML cards in a Bridgit samples catalog. Titles drop
    /// the "HTML card: " prefix.
    static func cards(json: Data) -> [Card] {
        guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let list = root["samples"] as? [[String: Any]] else { return [] }
        return list.compactMap { s in
            let html = (s["request"] as? [String: Any])?["html"] as? String ?? ""
            guard s["kind"] as? String == "html", !html.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            var title = s["title"] as? String ?? ""
            if title.hasPrefix("HTML card: ") { title.removeFirst("HTML card: ".count) }
            title = title.prefix(1).uppercased() + title.dropFirst()
            return Card(id: s["id"] as? String ?? title, title: title, summary: s["summary"] as? String ?? "", html: html)
        }
    }

    static func sample(_ id: String?) throws -> [String: Any] {
        guard let url = Bundle.main.url(forResource: "samples", withExtension: "json"),
              let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else { throw DeviceAPIError(503, "samples_unavailable", "The sample library did not load.") }
        // The bundle is Android's catalog; serve it as this host's contract (UUID interaction_id).
        return try HostSamples.appleHost(root, id: id)
    }

    /// The Apple bundle carries the Android 3347 catalog with the accessible checklist slider.
    static let bundledCards: [Card] = {
        guard let url = Bundle.main.url(forResource: "samples", withExtension: "json"), let d = try? Data(contentsOf: url) else { return [] }
        return cards(json: d)
    }()

    /// Sets the page's own sign-in keys before its scripts run, so bridgit.cc/myapps starts signed in with the
    /// app's `bws_` session (watch.js reads `bridgit-watch-session` as a bearer session when there's no cookie).
    /// Signed out, it clears the session so one the app put there earlier doesn't linger (a cookie
    /// session from the page's own email code is untouched).
    static func signInScript(session: String?, email: String?) -> String {
        func js(_ s: String?) -> String {
            guard let s, let d = try? JSONSerialization.data(withJSONObject: [s], options: [.fragmentsAllowed]),
                  let arr = String(data: d, encoding: .utf8) else { return "null" }
            return String(arr.dropFirst().dropLast())
        }
        return """
        (function(){var h=location.hostname;if(location.protocol!=='https:'||(h!=='bridgit.cc'&&!/\\.bridgit\\.cc$/.test(h)))return;
        var s=\(js(session)),e=\(js(email));
        try{if(s){localStorage.setItem('bridgit-watch-session',s);if(e)localStorage.setItem('bridgit-account-email',e);}
        else{localStorage.removeItem('bridgit-watch-session');}}catch(_){}})();
        """
    }
}
