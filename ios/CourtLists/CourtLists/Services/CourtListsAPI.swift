import Foundation

enum APIError: Error {
    case invalidURL
    case requestFailed(Error)
    case decodingFailed(Error)
    case httpError(Int)
    case unauthorized
}

class CourtListsAPI {
    private let baseUrl: String
    private let session: URLSession
    private weak var authManager: AuthManager?

    init(baseUrl: String = Configuration.apiBaseUrl, authManager: AuthManager? = nil) {
        self.baseUrl = baseUrl
        self.session = URLSession.shared
        self.authManager = authManager
    }

    func setAuthManager(_ authManager: AuthManager) {
        self.authManager = authManager
    }

    // MARK: - Helper Methods

    private func createRequest(url: URL, method: String = "GET") async -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        // Always add auth token - all endpoints require JWT
        if let authManager = authManager {
            await authManager.refreshTokenIfNeeded()
            if let token = await MainActor.run(body: { authManager.idToken }) {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }
        }

        return request
    }

    private func performRequest<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.requestFailed(NSError(domain: "Invalid response", code: -1))
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            if httpResponse.statusCode == 401 {
                throw APIError.unauthorized
            }
            throw APIError.httpError(httpResponse.statusCode)
        }

        do {
            let decoder = JSONDecoder()
            return try decoder.decode(T.self, from: data)
        } catch {
            if let rawString = String(data: data, encoding: .utf8) {
                print("Decoding failed. Raw response: \(rawString.prefix(500))")
            }
            print("Decoding error: \(error)")
            throw APIError.decodingFailed(error)
        }
    }

    private func performRequestNoResponse(_ request: URLRequest) async throws {
        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.requestFailed(NSError(domain: "Invalid response", code: -1))
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            if httpResponse.statusCode == 401 {
                throw APIError.unauthorized
            }
            if let rawString = String(data: data, encoding: .utf8) {
                print("Request failed. Response: \(rawString.prefix(500))")
            }
            throw APIError.httpError(httpResponse.statusCode)
        }
    }

    // MARK: - Listings & Cases (Public)

    func getListings(date: String) async throws -> [DiaryEntry] {
        guard let url = URL(string: "\(baseUrl)/functions/v1/listings") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "POST")
        request.httpBody = try JSONEncoder().encode(ListingsRequest(date: date))

        return try await performRequest(request)
    }

    func getCases(url: String) async throws -> CasesResponse {
        guard let apiUrl = URL(string: "\(baseUrl)/functions/v1/cases") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: apiUrl, method: "POST")
        request.httpBody = try JSONEncoder().encode(CasesRequest(url: url))

        return try await performRequest(request)
    }

    // MARK: - Comments

    func getComments(listSourceUrl: String, caseNumber: String) async throws -> [Comment] {
        guard var components = URLComponents(string: "\(baseUrl)/functions/v1/comments") else {
            throw APIError.invalidURL
        }

        components.queryItems = [
            URLQueryItem(name: "list_source_url", value: listSourceUrl),
            URLQueryItem(name: "case_number", value: caseNumber)
        ]

        guard let url = components.url else {
            throw APIError.invalidURL
        }

        let request = await createRequest(url: url)
        return try await performRequest(request)
    }

    func getCommentCounts(listSourceUrl: String, caseNumbers: [String]) async throws -> [CommentCount] {
        guard var components = URLComponents(string: "\(baseUrl)/functions/v1/comments") else {
            throw APIError.invalidURL
        }

        components.queryItems = [
            URLQueryItem(name: "list_source_url", value: listSourceUrl),
            URLQueryItem(name: "case_numbers", value: caseNumbers.joined(separator: ","))
        ]

        guard let url = components.url else {
            throw APIError.invalidURL
        }

        let request = await createRequest(url: url)
        return try await performRequest(request)
    }

    func addComment(listSourceUrl: String, caseNumber: String, content: String, urgent: Bool = false) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/comments") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "POST")

        let body: [String: Any] = [
            "list_source_url": listSourceUrl,
            "case_number": caseNumber,
            "content": content,
            "urgent": urgent
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        try await performRequestNoResponse(request)
    }

    func deleteComment(id: Int64) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/comments") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "DELETE")
        request.httpBody = try JSONEncoder().encode(["id": id])

        try await performRequestNoResponse(request)
    }

    // MARK: - Case Status

    func getCaseStatuses(listSourceUrl: String, caseNumbers: [String]) async throws -> [CaseStatus] {
        guard var components = URLComponents(string: "\(baseUrl)/functions/v1/case-status") else {
            throw APIError.invalidURL
        }

        components.queryItems = [
            URLQueryItem(name: "list_source_url", value: listSourceUrl),
            URLQueryItem(name: "case_numbers", value: caseNumbers.joined(separator: ","))
        ]

        guard let url = components.url else {
            throw APIError.invalidURL
        }

        let request = await createRequest(url: url)
        return try await performRequest(request)
    }

    func upsertCaseStatus(listSourceUrl: String, caseNumber: String, done: Bool) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/case-status") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "POST")

        let body: [String: Any] = [
            "list_source_url": listSourceUrl,
            "case_number": caseNumber,
            "done": done
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        try await performRequestNoResponse(request)
    }

    // MARK: - Watched Cases

    func getWatchedCases() async throws -> [WatchedCase] {
        guard let url = URL(string: "\(baseUrl)/functions/v1/watched-cases") else {
            throw APIError.invalidURL
        }

        let request = await createRequest(url: url)
        return try await performRequest(request)
    }

    func watchCase(listSourceUrl: String, caseNumber: String) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/watched-cases") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "POST")

        let body: [String: String] = [
            "list_source_url": listSourceUrl,
            "case_number": caseNumber,
            "source": "manual"
        ]
        request.httpBody = try JSONEncoder().encode(body)

        try await performRequestNoResponse(request)
    }

    func unwatchCase(listSourceUrl: String, caseNumber: String) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/watched-cases") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "DELETE")

        let body: [String: String] = [
            "list_source_url": listSourceUrl,
            "case_number": caseNumber
        ]
        request.httpBody = try JSONEncoder().encode(body)

        try await performRequestNoResponse(request)
    }

    // MARK: - Notifications

    func getNotifications(limit: Int = 50) async throws -> [AppNotification] {
        guard var components = URLComponents(string: "\(baseUrl)/functions/v1/notifications") else {
            throw APIError.invalidURL
        }

        components.queryItems = [
            URLQueryItem(name: "limit", value: "\(limit)")
        ]

        guard let url = components.url else {
            throw APIError.invalidURL
        }

        let request = await createRequest(url: url)
        return try await performRequest(request)
    }

    func markNotificationRead(id: Int64) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/notifications") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "PATCH")
        request.httpBody = try JSONEncoder().encode(["id": id])

        try await performRequestNoResponse(request)
    }

    func markAllNotificationsRead() async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/notifications") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "PATCH")
        request.httpBody = try JSONEncoder().encode(["all": true])

        try await performRequestNoResponse(request)
    }

    func deleteNotification(id: Int64) async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/notifications") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "DELETE")
        request.httpBody = try JSONEncoder().encode(["id": id])

        try await performRequestNoResponse(request)
    }

    func deleteAllNotifications() async throws {
        guard let url = URL(string: "\(baseUrl)/functions/v1/notifications") else {
            throw APIError.invalidURL
        }

        var request = await createRequest(url: url, method: "DELETE")
        request.httpBody = try JSONEncoder().encode(["all": true])

        try await performRequestNoResponse(request)
    }
}
