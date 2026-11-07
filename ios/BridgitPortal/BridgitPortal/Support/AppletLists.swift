import Foundation

/// The pure parts of Applets' three tabs, matching the APK's AppletLists (ui/applets/AppletLists.kt): which web
/// portals the Portals tab offers, which pages stay inside it, and which cards the Cards library lists.
enum AppletLists {
    static let portalPage = "https://bridgit.cc/myapps/"
    /// Applets › Config › How to build a Bridgit applet (ApiDocs.APPLET_URL on Android).
    static let buildGuide = URL(string: "https://bridgit.cc/recipes/build-an-applet/")!

    /// Links select an applet/device, while the portal retains its normal account authorization.
    static func settingsTarget(_ url: URL) -> URL? {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.user == nil, c.password == nil, c.port == nil, c.fragment == nil else { return nil }
        let web = c.scheme == "https" && ["bridgit.cc", "www.bridgit.cc"].contains(c.host ?? "") && ["/applet-settings", "/applet-settings/"].contains(c.path)
        let custom = c.scheme == "bridgitportal" && c.host == "settings" && ["", "/"].contains(c.path)
        guard web || custom else { return nil }
        let phones = (c.queryItems ?? []).filter { $0.name == "device" }, applets = (c.queryItems ?? []).filter { $0.name == "applet" }
        guard phones.count == 1, applets.count == 1, let phone = phones[0].value, let applet = applets[0].value,
              phone.range(of: "^bgi_[a-z2-7]{24}$", options: .regularExpression) != nil,
              applet == "dog" || applet.range(of: "^pkg\\.[a-z][a-z0-9-]{1,63}$", options: .regularExpression) != nil else { return nil }
        var target = URLComponents(string: portalPage)!
        target.queryItems = [URLQueryItem(name: "device", value: phone), URLQueryItem(name: "screen", value: "settings")]
        target.fragment = applet
        return target.url
    }

    /// One web portal into an applet: `id` is its section on bridgit.cc/myapps ("overview" = no section).
    struct Portal: Identifiable, Hashable {
        var id: String
        var name: String
        var url: URL
    }

    /// Pet monitor while its web portal is on, Agent activity while remote view is on, and the overview whenever
    /// either is. Most specific first, so a single applet portal opens straight away.
    static func portals(dogPortal: Bool, activityPortal: Bool, device: String?) -> [Portal] {
        func url(_ section: String) -> URL {
            var c = URLComponents(string: portalPage)!
            if let device, !device.isEmpty { c.queryItems = [URLQueryItem(name: "device", value: device)] }
            if !section.isEmpty { c.fragment = section }
            return c.url!
        }
        var out: [Portal] = []
        if dogPortal { out.append(Portal(id: "dog", name: "Pet monitor", url: url("dog"))) }
        if activityPortal { out.append(Portal(id: "activity", name: "Agent activity", url: url("activity"))) }
        if !out.isEmpty { out.append(Portal(id: "overview", name: "All my applets", url: url(""))) }
        return out
    }

    /// The portals for one phone as this app can see it. The phone doesn't serve its portal switches, so they are
    /// read from what they produce: a Pet monitor applet on the account (or on the phone), and the phone being linked
    /// to the account (remote view on).
    static func portals(device: PortalDevice?, accountApplets: [AppletSummary], phoneApplets: [AppletRow]) -> [Portal] {
        guard let device else { return [] }
        let install = device.cloud?.phone ?? device.pairing?.install
        let dog = accountApplets.contains { $0.applet == "dog" && $0.phone == install && install != nil }
            || phoneApplets.contains { $0.kind == "dog" }
        return portals(dogPortal: dog, activityPortal: device.cloud != nil, device: install)
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
    }

    /// The HTML cards in a samples file (site-content/public/samples/samples.json, copied into the app). Titles drop
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

    /// The library bundled with the app (the build copies samples.json in, like the APK's Gradle task).
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
