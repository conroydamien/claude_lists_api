import Foundation

protocol RealtimeClientDelegate: AnyObject {
    func realtimeClient(_ client: RealtimeClient, didReceiveNotification notification: AppNotification)
    func realtimeClient(_ client: RealtimeClient, didReceiveCommentUpdate comment: Comment, eventType: String)
    func realtimeClient(_ client: RealtimeClient, didReceiveStatusUpdate status: CaseStatus, eventType: String)
    func realtimeClientDidConnect(_ client: RealtimeClient)
    func realtimeClientDidDisconnect(_ client: RealtimeClient)
}

class RealtimeClient: NSObject {
    weak var delegate: RealtimeClientDelegate?

    private var webSocket: URLSessionWebSocketTask?
    private var session: URLSession?
    private var userId: String?
    private var accessToken: String?
    private var isConnected = false
    private var heartbeatTimer: Timer?
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 5

    private let baseUrl = Configuration.realtimeUrl
    private let anonKey = Configuration.apiAnonKey

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue.main)
    }

    // MARK: - Connection Management

    func connect(userId: String, accessToken: String) {
        self.userId = userId
        self.accessToken = accessToken
        print("RealtimeClient: Connecting for user \(userId)")

        // Connect without token in URL (will send via message after connection)
        guard let url = URL(string: "\(baseUrl)?apikey=\(anonKey)&vsn=1.0.0") else {
            print("RealtimeClient: Invalid WebSocket URL")
            return
        }

        print("RealtimeClient: WebSocket connecting...")
        webSocket = session?.webSocketTask(with: url)
        webSocket?.resume()
        receiveMessage()
    }

    func disconnect() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
        isConnected = false
    }

    // MARK: - Subscriptions

    private func subscribeToChannels() {
        guard let userId = userId else { return }

        print("RealtimeClient: Subscribing to channels for user \(userId)")

        // Subscribe to all tables in a single channel
        // Supabase Realtime v2 format
        let postgresChanges: [[String: Any]] = [
            [
                "event": "*",
                "schema": "public",
                "table": "notifications",
                "filter": "user_id=eq.\(userId)"
            ],
            [
                "event": "*",
                "schema": "public",
                "table": "comments"
            ],
            [
                "event": "*",
                "schema": "public",
                "table": "case_status"
            ]
        ]

        let payload: [String: Any] = [
            "event": "phx_join",
            "topic": "realtime:postgres_changes",
            "payload": [
                "config": [
                    "postgres_changes": postgresChanges
                ],
                "access_token": accessToken as Any
            ],
            "ref": UUID().uuidString
        ]

        sendMessage(payload)
        print("RealtimeClient: Subscription message sent")
    }

    // MARK: - Message Handling

    private func receiveMessage() {
        webSocket?.receive { [weak self] result in
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self?.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self?.handleMessage(text)
                    }
                @unknown default:
                    break
                }
                self?.receiveMessage()

            case .failure(let error):
                print("WebSocket receive error: \(error)")
                self?.handleDisconnect()
            }
        }
    }

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("RealtimeClient: Failed to parse message: \(text.prefix(200))")
            return
        }

        let event = json["event"] as? String ?? ""
        let topic = json["topic"] as? String ?? ""
        print("RealtimeClient: Received event '\(event)' on topic '\(topic)'")

        switch event {
        case "phx_reply":
            if let payload = json["payload"] as? [String: Any],
               let status = payload["status"] as? String {
                print("RealtimeClient: phx_reply status: \(status)")
                if status == "ok" {
                    reconnectAttempts = 0
                    print("RealtimeClient: Channel subscribed successfully")
                } else if status == "error" {
                    if let response = payload["response"] as? [String: Any] {
                        print("RealtimeClient: Error response: \(response)")
                    }
                }
            }

        case "postgres_changes":
            handlePostgresChange(json, topic: topic)

        default:
            break // Don't log unhandled events (too noisy)
        }
    }

    private func handlePostgresChange(_ json: [String: Any], topic: String) {
        guard let payload = json["payload"] as? [String: Any],
              let data = payload["data"] as? [String: Any],
              let record = data["record"] as? [String: Any],
              let eventType = data["type"] as? String else {
            print("RealtimeClient: Failed to parse postgres change - payload structure issue")
            if let payload = json["payload"] {
                print("RealtimeClient: payload type: \(type(of: payload))")
            }
            return
        }

        // Get table name from the payload
        let table = data["table"] as? String ?? ""
        print("RealtimeClient: Change on table '\(table)', type: \(eventType)")
        print("RealtimeClient: Record keys: \(record.keys.sorted())")

        switch table {
        case "notifications":
            if let notification = parseNotification(record) {
                delegate?.realtimeClient(self, didReceiveNotification: notification)
            }
        case "comments":
            if let comment = parseComment(record) {
                delegate?.realtimeClient(self, didReceiveCommentUpdate: comment, eventType: eventType)
            }
        case "case_status":
            if let status = parseStatus(record) {
                delegate?.realtimeClient(self, didReceiveStatusUpdate: status, eventType: eventType)
            }
        default:
            print("RealtimeClient: Unknown table '\(table)'")
        }
    }

    // MARK: - Parsing

    private func parseNotification(_ record: [String: Any]) -> AppNotification? {
        guard let idNumber = record["id"] as? NSNumber,
              let userId = record["user_id"] as? String,
              let type = record["type"] as? String,
              let listSourceUrl = record["list_source_url"] as? String,
              let caseNumber = record["case_number"] as? String,
              let actorName = record["actor_name"] as? String,
              let createdAt = record["created_at"] as? String else {
            print("RealtimeClient: parseNotification failed - missing required fields")
            return nil
        }

        // Handle read as either Bool or Int
        var read = false
        if let readBool = record["read"] as? Bool {
            read = readBool
        } else if let readInt = record["read"] as? Int {
            read = readInt != 0
        }

        return AppNotification(
            id: idNumber.int64Value,
            userId: userId,
            type: type,
            listSourceUrl: listSourceUrl,
            caseNumber: caseNumber,
            caseTitle: record["case_title"] as? String,
            actorName: actorName,
            actorId: record["actor_id"] as? String,
            content: record["content"] as? String,
            read: read,
            createdAt: createdAt
        )
    }

    private func parseComment(_ record: [String: Any]) -> Comment? {
        guard let listSourceUrl = record["list_source_url"] as? String,
              let caseNumber = record["case_number"] as? String,
              let authorName = record["author_name"] as? String,
              let content = record["content"] as? String else {
            print("RealtimeClient: parseComment failed - missing required fields")
            print("RealtimeClient: record keys: \(record.keys)")
            return nil
        }

        // Handle id as NSNumber (JSON numbers aren't Int64 directly)
        var commentId: Int64? = nil
        if let idNumber = record["id"] as? NSNumber {
            commentId = idNumber.int64Value
        }

        // Handle urgent as either Bool or Int (database might send 0/1)
        var urgent = false
        if let urgentBool = record["urgent"] as? Bool {
            urgent = urgentBool
        } else if let urgentInt = record["urgent"] as? Int {
            urgent = urgentInt != 0
        }

        return Comment(
            id: commentId,
            listSourceUrl: listSourceUrl,
            caseNumber: caseNumber,
            userId: record["user_id"] as? String,
            authorName: authorName,
            content: content,
            urgent: urgent,
            createdAt: record["created_at"] as? String
        )
    }

    private func parseStatus(_ record: [String: Any]) -> CaseStatus? {
        guard let listSourceUrl = record["list_source_url"] as? String,
              let caseNumber = record["case_number"] as? String else {
            print("RealtimeClient: parseStatus failed - missing required fields")
            return nil
        }

        // Handle done as either Bool or Int
        var done = false
        if let doneBool = record["done"] as? Bool {
            done = doneBool
        } else if let doneInt = record["done"] as? Int {
            done = doneInt != 0
        }

        return CaseStatus(
            listSourceUrl: listSourceUrl,
            caseNumber: caseNumber,
            done: done,
            updatedBy: record["updated_by"] as? String,
            updatedAt: record["updated_at"] as? String
        )
    }

    // MARK: - Heartbeat

    private func startHeartbeat() {
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.sendHeartbeat()
        }
    }

    private func sendHeartbeat() {
        let payload: [String: Any] = [
            "event": "heartbeat",
            "topic": "phoenix",
            "payload": [:],
            "ref": UUID().uuidString
        ]
        sendMessage(payload)
    }

    // MARK: - Sending

    private func sendMessage(_ payload: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            return
        }

        webSocket?.send(.string(text)) { error in
            if let error = error {
                print("WebSocket send error: \(error)")
            }
        }
    }

    // MARK: - Reconnection

    private func handleDisconnect() {
        isConnected = false
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        delegate?.realtimeClientDidDisconnect(self)

        if reconnectAttempts < maxReconnectAttempts {
            reconnectAttempts += 1
            let delay = Double(reconnectAttempts) * 2.0
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self = self,
                      let userId = self.userId,
                      let accessToken = self.accessToken else { return }
                self.connect(userId: userId, accessToken: accessToken)
            }
        }
    }
}

// MARK: - URLSessionWebSocketDelegate

extension RealtimeClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        print("RealtimeClient: WebSocket opened")

        // Send access token to realtime:system
        if let token = accessToken {
            let tokenPayload: [String: Any] = [
                "event": "access_token",
                "topic": "realtime:system",
                "payload": ["access_token": token],
                "ref": UUID().uuidString
            ]
            sendMessage(tokenPayload)
            print("RealtimeClient: Sent access token")
        }

        // Directly subscribe to channels (skip phoenix join like Android)
        isConnected = true
        subscribeToChannels()
        startHeartbeat()
        delegate?.realtimeClientDidConnect(self)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        handleDisconnect()
    }
}
