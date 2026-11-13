package cc.bridgit.app.satellite

import cc.bridgit.app.BridgeApp

/** The hub's side of pairing and checking satellites. Blocking: call off the main thread. */
object SatelliteHub {
    /** Claims a satellite's pairing [link], reads its manifest and saves it. Throws with a plain message on failure. */
    fun add(app: BridgeApp, link: String): SatelliteStore.Satellite {
        val p = SatelliteLink.parse(link)
        val own = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull()
        require(p.host !in app.listener.value.boundTo.map { cc.bridgit.app.core.Net.normalise(it) } && (p.install == null || p.install != own)) {
            "That link is from this phone. Make the link on the spare phone, then add it here." }
        val hubName = cc.bridgit.app.platform.DeviceName.forLinks(app)
        val (key, name) = SatelliteClient.claim(p, hubName)
        val base = SatelliteClient.base(p.host, p.port)
        val manifest = runCatching { SatelliteClient.manifest(base, key, hubName) }.getOrNull()
        // The satellite already made its hub link; if this phone can't keep the key, undo it there too, so the
        // satellite doesn't show a hub that doesn't know it.
        val s = try { app.satellites.add(p.copy(name = name), key, manifest) } catch (e: Exception) {
            runCatching { SatelliteClient.unlink(base, key) }
            throw e
        }
        app.log.system("audit", "satellite", "Added satellite ${s.name} (${s.id})")
        pushHubInfo(app, s.id, hubName)
        return s
    }

    /**
     * Removes a satellite and the return link it called back with. Unless [tellSatellite] is false (it already
     * dropped this hub), the satellite is told in the background so it drops its hub link too. A satellite that is
     * offline then finds out on its next check, when its return key stops working (HubLinks.checkHubsIfDue).
     */
    fun remove(app: BridgeApp, id: String, tellSatellite: Boolean = true) {
        val s = app.satellites.get(id) ?: return
        s.returnAgent?.let { app.agents.remove(it) }
        app.satellites.remove(id)
        app.log.system("audit", "satellite", "Removed satellite ${s.name}")
        if (tellSatellite) kotlin.concurrent.thread(isDaemon = true, name = "satellite-unlink") {
            runCatching { SatelliteClient.unlink(s.base, s.key) }
        }
    }

    /**
     * The satellite's way back (owner, 5 Nov 2026): a satellite's Camera tab uses this hub's photo applets and its
     * Portals tab shows this hub's portals, with no setup. The hub makes one direct agent per satellite, "Satellite
     * <name>", locked to the satellite's Tailscale address, that can call only [SatellitePolicy.RETURN_PATHS], and sends
     * its key with what this hub offers. Sent after every manifest read, so a link made before this existed picks it up.
     * Satellites on an older version answer 404; that's ignored.
     */
    fun pushHubInfo(app: BridgeApp, id: String, hubName: String = cc.bridgit.app.platform.DeviceName.forLinks(app)) {
        if (LinkPause.satellites) return
        // A link over the relay has no way back yet: the return agent is locked to a Tailscale address.
        if (app.satellites.meta(id) == null || app.satellites.get(id)?.relay != null) return
        try {
            var s = app.satellites.get(id) ?: return
            val live = s.returnAgent?.let { app.agents.get(it) }
            if (live == null || s.returnKey == null) {
                s.returnAgent?.let { app.agents.remove(it) }
                val name = (1..50).map { returnName(s.name, it) }.firstOrNull { app.agents.checkName(it, exceptId = "-") == null } // a valid, unused name; the slot count doesn't apply
                    ?: throw IllegalStateException("No free name for the return link")
                // Doesn't use one of the owner's 16 agent slots: a full phone still shares with its satellites.
                val (a, key) = app.agents.add(name, cc.bridgit.app.core.Connection.DIRECT, limited = false)
                app.agents.update(a.id) { it.copy(caps = app.links.returnCaps(id) ?: SatellitePolicy.RETURN_CAPS, fixer = false, superPowers = false,
                    boundIps = listOf(cc.bridgit.app.core.Net.normalise(s.host)),
                    notes = "Satellite ${s.name} calling back: uses this phone's photo applets, portals and Home. Remove the satellite to remove this.") }
                s = app.satellites.update(id) { it.copy(returnAgent = a.id, returnKey = key) } ?: return
                app.log.system("audit", "satellite", "Made a return link for satellite ${s.name}")
            }
            // The return agent's grants are the link's service list for "the satellite uses this hub" (LNK-11); a
            // migrated link has view, which is what return links always had.
            val want = app.links.returnCaps(id) ?: SatellitePolicy.RETURN_CAPS
            s.returnAgent?.let { rid -> if (app.agents.get(rid)?.caps != want) app.agents.update(rid) { it.copy(caps = want) } }
            val rec = app.links.all().firstOrNull { it.role == cc.bridgit.app.link.Links.Role.HUB && it.ref == id }
            val body = SatelliteEndpoints.hubInfo(app, hubName).put("port", app.listener.value.port).put("key", s.returnKey)
                .put("bridgit_name", app.links.registeredName() ?: org.json.JSONObject.NULL)
                .put("install", runCatching { cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId }.getOrNull() ?: org.json.JSONObject.NULL)
                .put("link", app.links.linkBlock(rec) ?: org.json.JSONObject.NULL)
            SatelliteClient.hubInfo(s.base, s.key, hubName, body)
            val wasFailing = s.shareError != null
            app.satellites.update(id) { it.copy(sharedAt = System.currentTimeMillis(), shareError = null) }
            if (wasFailing || s.sharedAt == 0L) app.log.system("audit", "satellite", "Shared this phone's applets and portals with ${s.name}")
        } catch (e: Exception) {
            val why = when {
                e is SatelliteClient.Failure && e.status == 404 -> "it needs the latest Bridgit"
                e is SatelliteClient.Failure -> e.message ?: e.code
                else -> e.message ?: e.javaClass.simpleName
            }
            val before = app.satellites.get(id)
            app.satellites.update(id) { it.copy(shareError = why) }
            if (before?.shareError != why) app.log.system("audit", "satellite", "Couldn't share applets with ${before?.name ?: id}: $why")
        }
    }

    /** "Satellite Pixel 10", "Satellite Pixel 10 2", … within the agent-name rules (as HubLinks.linkName). */
    fun returnName(satName: String, n: Int): String {
        val clean = satName.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') it else ' ' }.joinToString("")
            .replace(Regex(" +"), " ").trim()
        val suffix = if (n == 1) "" else " $n"
        return ("Satellite " + clean).trim().take(24 - suffix.length).trim() + suffix
    }

    /** Re-reads one satellite's manifest; records when it was last seen, or why it couldn't be reached. */
    fun refresh(app: BridgeApp, id: String): SatelliteStore.Satellite? {
        val s = app.satellites.get(id) ?: return null
        if (LinkPause.satellites) return s // paused by the owner: no call, and no "offline" mark
        return try {
            val m = SatelliteClient.manifest(s.base, s.key, cc.bridgit.app.platform.DeviceName.forLinks(app))
            app.satellites.update(id) { it.copy(manifest = m, lastSeen = System.currentTimeMillis(), lastError = null) }
                .also { runCatching { app.links.noteManifest(id, m) }; pushHubInfo(app, id) }
        } catch (e: SatelliteClient.Failure) {
            // Over the relay, a target from before relay manifests refuses the route but did answer: it is there.
            if (s.relay != null && e.code == "relay_route_excluded")
                return app.satellites.update(id) { it.copy(lastSeen = System.currentTimeMillis(), lastError = null) }
            if (e.code == "link_revoked") {
                app.links.all().firstOrNull { it.role == cc.bridgit.app.link.Links.Role.HUB && it.ref == id }?.let { app.links.farRevoked(it, e.scope) }
                return app.satellites.get(id)
            }
            // The satellite no longer knows this hub's key: it was unlinked there. Drop it here too, so the two agree.
            if (SatelliteClient.isGone(e)) {
                remove(app, id, tellSatellite = false)
                app.log.system("audit", "satellite", "${s.name} unlinked this phone, so it was removed here too")
                return null
            }
            val why = when { e.code == "satellite_offline" -> "offline"; e.status == 401 -> "unlinked"; else -> e.code }
            app.satellites.update(id) { it.copy(lastError = why) }
        }
    }

    @Volatile private var lastAll = 0L
    private val relayRead = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RELAY_MANIFEST_MS = 60 * 60_000L

    /** From the service's 5-minute check: every satellite, at most once a minute. */
    fun refreshAllIfDue(app: BridgeApp, now: Long = System.currentTimeMillis()) {
        if (now - lastAll < 60_000 || app.satellites.count() == 0 || LinkPause.satellites) return
        lastAll = now
        app.satellites.all().forEach { s ->
            // Each relay call counts against the relay's allowance and may wake the other device: a link over the relay
            // reads its manifest hourly (its events sync keeps last seen current).
            if (s.relay != null && s.manifest != null && now - (relayRead[s.id] ?: 0L) < RELAY_MANIFEST_MS) return@forEach
            if (s.relay != null) relayRead[s.id] = now
            runCatching { refresh(app, s.id) }
        }
    }
}
