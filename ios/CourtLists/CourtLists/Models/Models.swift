import Foundation

// MARK: - API Request Models

struct ListingsRequest: Codable {
    let date: String
}

struct CasesRequest: Codable {
    let url: String
}

// MARK: - API Response Models

struct DiaryEntry: Codable {
    let dateText: String
    let dateIso: String?
    let venue: String
    let type: String
    let subtitle: String
    let updated: String
    let sourceUrl: String
}

struct CasesResponse: Codable {
    let cases: [ParsedCase]
    let headers: [String]
}

struct ParsedCase: Codable {
    let listNumber: Int?
    let caseNumber: String?
    let title: String
    let parties: String?
    let isCase: Bool
}

// MARK: - Database Models

struct Comment: Codable, Identifiable {
    let id: Int64?
    let listSourceUrl: String
    let caseNumber: String
    let userId: String?
    let authorName: String
    let content: String
    let urgent: Bool
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case listSourceUrl = "list_source_url"
        case caseNumber = "case_number"
        case userId = "user_id"
        case authorName = "author_name"
        case content
        case urgent
        case createdAt = "created_at"
    }

    init(id: Int64? = nil, listSourceUrl: String, caseNumber: String, userId: String? = nil, authorName: String, content: String, urgent: Bool = false, createdAt: String? = nil) {
        self.id = id
        self.listSourceUrl = listSourceUrl
        self.caseNumber = caseNumber
        self.userId = userId
        self.authorName = authorName
        self.content = content
        self.urgent = urgent
        self.createdAt = createdAt
    }
}

struct CaseStatus: Codable {
    let listSourceUrl: String
    let caseNumber: String
    let done: Bool
    let updatedBy: String?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case listSourceUrl = "list_source_url"
        case caseNumber = "case_number"
        case done
        case updatedBy = "updated_by"
        case updatedAt = "updated_at"
    }

    init(listSourceUrl: String, caseNumber: String, done: Bool = false, updatedBy: String? = nil, updatedAt: String? = nil) {
        self.listSourceUrl = listSourceUrl
        self.caseNumber = caseNumber
        self.done = done
        self.updatedBy = updatedBy
        self.updatedAt = updatedAt
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(listSourceUrl, forKey: .listSourceUrl)
        try container.encode(caseNumber, forKey: .caseNumber)
        try container.encode(done, forKey: .done)
        try container.encodeIfPresent(updatedBy, forKey: .updatedBy)
        try container.encodeIfPresent(updatedAt, forKey: .updatedAt)
    }
}

struct WatchedCase: Codable, Identifiable {
    let id: Int64?
    let userId: String
    let listSourceUrl: String
    let caseNumber: String
    let source: String
    let createdAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case listSourceUrl = "list_source_url"
        case caseNumber = "case_number"
        case source
        case createdAt = "created_at"
    }

    init(id: Int64? = nil, userId: String, listSourceUrl: String, caseNumber: String, source: String = "manual", createdAt: String? = nil) {
        self.id = id
        self.userId = userId
        self.listSourceUrl = listSourceUrl
        self.caseNumber = caseNumber
        self.source = source
        self.createdAt = createdAt
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encode(userId, forKey: .userId)
        try container.encode(listSourceUrl, forKey: .listSourceUrl)
        try container.encode(caseNumber, forKey: .caseNumber)
        try container.encode(source, forKey: .source)
        try container.encodeIfPresent(createdAt, forKey: .createdAt)
    }
}

struct AppNotification: Codable, Identifiable {
    let id: Int64
    let userId: String
    let type: String  // 'comment', 'status_done', 'status_undone'
    let listSourceUrl: String
    let caseNumber: String
    let caseTitle: String?
    let actorName: String
    let actorId: String?
    let content: String?
    let read: Bool
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case type
        case listSourceUrl = "list_source_url"
        case caseNumber = "case_number"
        case caseTitle = "case_title"
        case actorName = "actor_name"
        case actorId = "actor_id"
        case content
        case read
        case createdAt = "created_at"
    }
}

// MARK: - UI Models

struct CourtList: Identifiable, Hashable {
    let id: Int
    let name: String
    let dateIso: String?
    let dateText: String
    let venue: String
    let type: String?
    let sourceUrl: String
    let updated: String?

    static func fromDiaryEntry(_ entry: DiaryEntry, index: Int) -> CourtList {
        CourtList(
            id: index,
            name: entry.subtitle.isEmpty ? "\(entry.venue) - \(entry.dateText)" : entry.subtitle,
            dateIso: entry.dateIso,
            dateText: entry.dateText,
            venue: entry.venue,
            type: entry.type.isEmpty ? nil : entry.type,
            sourceUrl: entry.sourceUrl,
            updated: entry.updated.isEmpty ? nil : entry.updated
        )
    }
}

struct CaseItem: Identifiable {
    let id: Int
    let listSourceUrl: String
    let listNumber: Int?
    let caseNumber: String?
    let title: String
    let parties: String?
    var done: Bool = false
    var commentCount: Int = 0
    var hasUrgent: Bool = false

    var caseKey: String {
        caseNumber ?? "item-\(listNumber ?? 0)"
    }

    static func fromParsedCase(_ parsedCase: ParsedCase, index: Int, listSourceUrl: String) -> CaseItem {
        CaseItem(
            id: index,
            listSourceUrl: listSourceUrl,
            listNumber: parsedCase.listNumber,
            caseNumber: parsedCase.caseNumber,
            title: parsedCase.title,
            parties: parsedCase.parties
        )
    }
}

// MARK: - Comment Count Helper

struct CommentCount: Codable {
    let caseNumber: String
    let urgent: Bool

    enum CodingKeys: String, CodingKey {
        case caseNumber = "case_number"
        case urgent
    }
}
