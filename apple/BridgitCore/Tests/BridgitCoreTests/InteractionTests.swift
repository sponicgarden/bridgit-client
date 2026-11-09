import Foundation
import XCTest
@testable import BridgitCore
final class InteractionTests: XCTestCase {
    private func store() throws -> AppletStore {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        return try AppletStore(directory: path)
    }
    private var question: [String: Any] { ["kind": "question", "title": "Fixture choice", "no_takeover": true, "controls": [["type": "button", "id": "yes", "label": "Yes"]], "expires_in_seconds": 60] }
    private var answer: [String: Any] { ["button": "yes", "values": [String: String](), "data": NSNull()] }
    func testBadgeTracksSeenNoticesAndLateQuestions() async throws {
        let store = try store(), now = Date(timeIntervalSince1970: 1000)
        let notice = ["kind": "notice", "title": "Update", "no_takeover": true, "expires_in_seconds": 60] as [String: Any]
        let card = try await store.createInteraction(owner: "agent", input: question, now: now)
        let first = try await store.createInteraction(owner: "agent", input: notice, now: now)
        _ = try await store.createInteraction(owner: "agent", input: notice, now: now)
        let initial = try await store.inboxBadge(now: now)
        XCTAssertEqual(initial.count, 3)
        XCTAssertEqual(initial.nextExpiry, now.addingTimeInterval(60))
        try await store.markInteractionSeen(id: try XCTUnwrap(first["interaction_id"] as? String), now: now)
        let seen = try await store.inboxBadge(now: now)
        XCTAssertEqual(seen.count, 2)
        let expired = try await store.inboxBadge(now: now.addingTimeInterval(60))
        XCTAssertEqual(expired.count, 1)
        XCTAssertNil(expired.nextExpiry)
        _ = try await store.answerInteraction(id: try XCTUnwrap(card["interaction_id"] as? String),
                                              answer: answer, now: now.addingTimeInterval(61))
        let answered = try await store.inboxBadge(now: now.addingTimeInterval(61))
        XCTAssertEqual(answered.count, 0)
    }

    func testOpeningNoticeEmitsOneSeenEventOnlyForItsSender() async throws {
        let store = try store()
        let notice = try await store.createInteraction(owner: "sender", input: ["kind": "notice", "title": "Update", "no_takeover": true])
        let id = try XCTUnwrap(notice["interaction_id"] as? String)
        try await store.markInteractionSeen(id: id)
        try await store.markInteractionSeen(id: id)
        let own = try await store.interactionEvents(owner: "sender", types: ["interaction.seen"])
        let events = try XCTUnwrap(own["events"] as? [[String: Any]])
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual((events.first?["data"] as? [String: Any])?["interaction_id"] as? String, id)
        let other = try await store.interactionEvents(owner: "another", types: ["interaction.seen"])
        XCTAssertEqual((other["events"] as? [[String: Any]])?.count, 0)
    }

    func testAnswersPersistWithScopedEventsAndResubmission() async throws {
        let store = try store(), card = try await store.createInteraction(owner: "a:generation", input: question)
        let id = card["interaction_id"] as! String
        _ = try await store.answerInteraction(id: id, answer: answer)
        _ = try await store.answerInteraction(id: id, answer: answer)
        let result = try await store.interaction(owner: "a:generation", id: id)
        XCTAssertEqual(result["status"] as? String, "answered")
        let events = try await store.interactionEvents(owner: "a:generation")
        let rows = events["events"] as! [[String: Any]]
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual((rows[1]["data"] as? [String: Any])?["resubmission"] as? Bool, true)
        XCTAssertNotNil(events["next_cursor"] as? String)
        let other = try await store.interactionEvents(owner: "another:generation")
        XCTAssertTrue((other["events"] as! [Any]).isEmpty)
        do { _ = try await store.interaction(owner: "a:rotated", id: id); XCTFail("Cross-generation read") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404) }
        do { _ = try await store.cancelInteraction(owner: "other", id: id); XCTFail("Cross-owner cancel") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 404) }
    }
    func testDismissedHTMLCardRetainsContentAndRejectsAnotherAnswer() async throws {
        let store = try store()
        let html = "<button>Test received</button>"
        let card = try await store.createInteraction(owner: "sender", input: [
            "kind": "html", "title": "Connection check", "html": html, "no_takeover": true
        ])
        let id = try XCTUnwrap(card["interaction_id"] as? String)
        try await store.dismissInteraction(id: id)
        do {
            _ = try await store.answerInteraction(id: id, answer: ["data": ["button": "received"]])
            XCTFail("A dismissed card accepted a reply")
        } catch let error as DeviceAPIError { XCTAssertEqual(error.code, "interaction_closed") }
        let stored = try await store.interaction(owner: "sender", id: id)
        XCTAssertEqual(stored["html"] as? String, html)
        XCTAssertEqual(stored["status"] as? String, "dismissed")
        XCTAssertNil(stored["answer"])
        let changes = try await store.interactionEvents(owner: "sender")
        let events = try XCTUnwrap(changes["events"] as? [[String: Any]])
        XCTAssertEqual(events.compactMap { $0["type"] as? String }, ["interaction.dismissed"])
    }

    func testExpiredCardAcceptsLateAnswerAndCancelledCardCannotAnswer() async throws {
        let store = try store(), now = Date(timeIntervalSince1970: 1000)
        let card = try await store.createInteraction(owner: "owner", input: question, now: now)
        let id = card["interaction_id"] as! String
        let expired = try await store.interaction(owner: "owner", id: id, now: now.addingTimeInterval(61))
        XCTAssertEqual(expired["status"] as? String, "expired")
        let late = try await store.answerInteraction(id: id, answer: answer, now: now.addingTimeInterval(62))
        XCTAssertEqual(late["late"] as? Bool, true)
        let second = try await store.createInteraction(owner: "owner", input: question)
        let secondID = second["interaction_id"] as! String
        _ = try await store.cancelInteraction(owner: "owner", id: secondID)
        do { _ = try await store.answerInteraction(id: secondID, answer: answer); XCTFail("Cancelled card answered") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.code, "interaction_closed") }
    }
    func testUnsupportedDeliveryAndInvalidControlsLeaveInboxEmpty() async throws {
        let store = try store()
        for patch: [String: Any] in [["speak": true], ["priority": "urgent"], ["no_takeover": false], ["follow_up": true], ["expires_in_seconds": true], ["no_takeover": 1], ["controls": [["id": "x", "type": "link", "label": "Bad", "url": "javascript:alert(1)"]]], ["controls": [["id": "x", "type": "button", "label": "Bad", "options": ["ignored"]]]]] {
            var body = question; body.merge(patch) { _, value in value }
            do { _ = try await store.createInteraction(owner: "owner", input: body); XCTFail("Invalid interaction accepted") } catch { }
        }
        let rows = try await store.interactions(); XCTAssertTrue(rows.isEmpty)
    }
    func testQuestionCannotAnswerWithUnlistedButtonOrChoice() async throws {
        let store = try store(), card = try await store.createInteraction(owner: "owner", input: question)
        let id = card["interaction_id"] as! String
        do { _ = try await store.answerInteraction(id: id, answer: ["button": "other", "values": [String: String]()]); XCTFail("Unknown button") } catch { }
        let saved = try await store.interaction(owner: "owner", id: id)
        XCTAssertEqual(saved["status"] as? String, "pending")
    }
    func testInboxQuotaAndEventFilteringStayPerAgent() async throws {
        let store = try store()
        for _ in 0..<5 { _ = try await store.createInteraction(owner: "first", input: question) }
        do { _ = try await store.createInteraction(owner: "first", input: question); XCTFail("No open-card bound") }
        catch let error as DeviceAPIError { XCTAssertEqual(error.status, 429) }
        let card = try await store.createInteraction(owner: "second", input: question)
        try await store.dismissInteraction(id: card["interaction_id"] as! String)
        let result = try await store.interactionEvents(owner: "second", after: 0, types: ["interaction.answered"])
        XCTAssertTrue((result["events"] as! [Any]).isEmpty)
        let dismissed = try await store.interactionEvents(owner: "second", after: 0, types: ["interaction.dismissed"])
        XCTAssertEqual((dismissed["events"] as! [Any]).count, 1)
    }
    func testDispatcherUsesLiveGrantsIdempotencyAndAgentScopedAnswerEvents() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Inbox fixture", operations: [.interactionsRead, .interactionsWrite], applets: [])
        let (other, _) = try await authority.create(name: "Other fixture", operations: [.interactionsRead], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let engine = AppletEngine(store: store) { _, _, _ in [:] }
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: engine, profile: { _ in throw CancellationError() })
        func job(_ caller: HostAgent, _ sequence: Int64, _ request: HostRequest) -> HostJob {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return .init(id: UUID().uuidString, sequence: sequence, agentID: caller.id, generation: caller.generation, deviceID: "fixture", deadline: now + 30000, createdAt: now, request: request)
        }
        let request = HostRequest(method: "POST", path: "/v1/interactions", body: question, idempotencyKey: "create-once")
        let first = await dispatcher.dispatch(job(agent, 1, request))
        let retry = await dispatcher.dispatch(job(agent, 2, request))
        XCTAssertEqual(first.status, 201); XCTAssertEqual(first.data, retry.data)
        let body = try JSONSerialization.jsonObject(with: first.data) as! [String: Any], id = body["interaction_id"] as! String
        let denied = await dispatcher.dispatch(job(other, 1, request)); XCTAssertEqual(denied.status, 403)
        let hidden = await dispatcher.dispatch(job(other, 2, .init(method: "GET", path: "/v1/interactions/" + id))); XCTAssertEqual(hidden.status, 404)
        _ = try await store.answerInteraction(id: id, answer: answer)
        let events = await dispatcher.dispatch(job(agent, 3, .init(method: "GET", path: "/v1/events", query: ["since": "0", "types": "interaction.answered"])))
        let result = try JSONSerialization.jsonObject(with: events.data) as! [String: Any]
        XCTAssertEqual((result["events"] as? [Any])?.count, 1)
        let all = try await store.interactions(); XCTAssertEqual(all.count, 1)
        try await authority.pause(agent.id, paused: true)
        let paused = await dispatcher.dispatch(job(agent, 4, .init(method: "GET", path: "/v1/interactions/" + id)))
        XCTAssertEqual(paused.status, 403)
    }
    func testDefiniteRejectionRetriesReturnTheSameErrorWithoutPoisoningTheKey() async throws {
        let store = try store(), authority = HostAuthority(store: store, deviceID: "fixture")
        let (agent, _) = try await authority.create(name: "Fixture", operations: [.interactionsWrite], applets: [])
        await authority.setState(enabled: true, awake: true, foreground: true)
        let dispatcher = HostDispatcher(authority: authority, store: store, engine: AppletEngine(store: store) { _, _, _ in [:] }, profile: { _ in throw CancellationError() })
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var body = question; body["speak"] = true
        var job = HostJob(id: "first", sequence: 1, agentID: agent.id, generation: agent.generation, deviceID: "fixture", deadline: now + 30000, createdAt: now, request: .init(method: "POST", path: "/v1/interactions", body: body, idempotencyKey: "rejected-once"))
        let first = await dispatcher.dispatch(job)
        job.id = "retry"; job.sequence = 2
        let retry = await dispatcher.dispatch(job)
        XCTAssertEqual(first.status, 422); XCTAssertEqual(retry.status, 422); XCTAssertEqual(first.data, retry.data)
        let rows = try await store.interactions(); XCTAssertTrue(rows.isEmpty)
    }
    func testTerminalRetentionRecoversCapacityAfterMoreThanFiveHundredCards() async throws {
        let store = try store()
        for _ in 0..<510 {
            let card = try await store.createInteraction(owner: "first", input: question)
            _ = try await store.cancelInteraction(owner: "first", id: card["interaction_id"] as! String)
        }
        let other = try await store.createInteraction(owner: "second", input: question)
        XCTAssertEqual(other["status"] as? String, "pending")
        let rows = try await store.interactions()
        XCTAssertLessThanOrEqual(rows.count, 201)
    }
    func testInboxAndEventCursorSurviveDatabaseReopen() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: path) }
        let store = try AppletStore(directory: path)
        let card = try await store.createInteraction(owner: "fixture", input: question)
        let id = card["interaction_id"] as! String
        _ = try await store.answerInteraction(id: id, answer: answer)
        let first = try await store.interactionEvents(owner: "fixture"), cursor = first["next_cursor"] as! String
        let reopened = try AppletStore(directory: path)
        let saved = try await reopened.interaction(owner: "fixture", id: id)
        XCTAssertEqual(saved["status"] as? String, "answered")
        let events = try await reopened.interactionEvents(owner: "fixture", after: Int64(cursor)!)
        XCTAssertTrue((events["events"] as! [Any]).isEmpty)
        XCTAssertEqual(events["next_cursor"] as? String, cursor)
    }

}
