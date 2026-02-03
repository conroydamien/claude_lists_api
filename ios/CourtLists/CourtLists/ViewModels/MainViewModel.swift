import Foundation
import SwiftUI

@MainActor
class MainViewModel: ObservableObject {
    // MARK: - Published State

    @Published var lists: [CourtList] = []
    @Published var items: [CaseItem] = []
    @Published var headers: [String] = []
    @Published var comments: [Comment] = []
    @Published var notifications: [AppNotification] = []
    @Published var watchedCaseKeys: Set<String> = []

    @Published var selectedDate: Date = Date()
    @Published var selectedVenue: String? = nil
    @Published var selectedList: CourtList?
    @Published var selectedItem: CaseItem?
    @Published var listCommentsTitle: String?

    var venues: [String] {
        Array(Set(lists.map { $0.venue })).sorted()
    }

    var filteredLists: [CourtList] {
        guard let venue = selectedVenue else { return lists }
        return lists.filter { $0.venue == venue }
    }

    @Published var isLoadingLists = false
    @Published var isLoadingItems = false
    @Published var isLoadingComments = false

    @Published var showCommentsSheet = false
    @Published var showListCommentsSheet = false
    @Published var showNotifications = false

    @Published var errorMessage: String?

    /// Timestamp of the last update per list (keyed by sourceUrl)
    @Published var lastUpdateByList: [String: Date] = [:]

    /// Get the last update date for the currently selected list
    var lastStatusUpdateDate: Date? {
        guard let list = selectedList else { return nil }
        return lastUpdateByList[list.sourceUrl]
    }

    var unreadNotificationCount: Int {
        notifications.filter { !$0.read }.count
    }

    // MARK: - Dependencies

    private let api = CourtListsAPI()
    private let realtimeClient = RealtimeClient()
    var authManager: AuthManager?

    // MARK: - Initialization

    init() {
        realtimeClient.delegate = self
    }

    func setup(authManager: AuthManager) {
        self.authManager = authManager
        api.setAuthManager(authManager)

        // Connect to realtime with Supabase access token for RLS
        // userUuid is used for filtering notifications client-side
        if let userUuid = authManager.userUuid,
           let accessToken = authManager.supabaseAccessToken {
            realtimeClient.connect(userId: userUuid, accessToken: accessToken)
            Task {
                await loadWatchedCases()
                await loadNotifications()
            }
        }
    }

    // MARK: - Lists

    func loadLists() async {
        isLoadingLists = true
        errorMessage = nil

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let dateString = dateFormatter.string(from: selectedDate)

        print("Loading lists for date: \(dateString)")

        do {
            let entries = try await api.getListings(date: dateString)
            print("Loaded \(entries.count) entries")
            lists = entries.enumerated().map { CourtList.fromDiaryEntry($1, index: $0) }
            print("Venues: \(venues)")
        } catch {
            print("Error loading lists: \(error)")
            errorMessage = "Failed to load lists: \(error.localizedDescription)"
        }

        isLoadingLists = false
    }

    func selectList(_ list: CourtList) {
        selectedList = list
        Task {
            await loadItems(for: list)
        }
    }

    // MARK: - Items

    func loadItems(for list: CourtList) async {
        isLoadingItems = true
        errorMessage = nil

        do {
            let response = try await api.getCases(url: list.sourceUrl)
            headers = response.headers
            items = response.cases.enumerated().map { index, parsedCase in
                CaseItem.fromParsedCase(parsedCase, index: index, listSourceUrl: list.sourceUrl)
            }

            // Load comment counts and statuses
            await loadCommentCounts(for: list.sourceUrl)
            await loadCaseStatuses(for: list.sourceUrl)
        } catch {
            errorMessage = "Failed to load items: \(error.localizedDescription)"
        }

        isLoadingItems = false
    }

    func refreshItems() {
        guard let list = selectedList else { return }
        Task {
            await loadItems(for: list)
        }
    }

    private func loadCommentCounts(for listSourceUrl: String) async {
        let caseNumbers = items.map { $0.caseKey }
        guard !caseNumbers.isEmpty else { return }

        do {
            let counts = try await api.getCommentCounts(listSourceUrl: listSourceUrl, caseNumbers: caseNumbers)

            // Group by case number
            var countMap: [String: Int] = [:]
            var urgentSet: Set<String> = []

            for count in counts {
                countMap[count.caseNumber, default: 0] += 1
                if count.urgent {
                    urgentSet.insert(count.caseNumber)
                }
            }

            // Update items
            items = items.map { item in
                var updated = item
                updated.commentCount = countMap[item.caseKey] ?? 0
                updated.hasUrgent = urgentSet.contains(item.caseKey)
                return updated
            }
        } catch {
            print("Failed to load comment counts: \(error)")
        }
    }

    private func loadCaseStatuses(for listSourceUrl: String) async {
        let caseNumbers = items.map { $0.caseKey }
        guard !caseNumbers.isEmpty else { return }

        do {
            let statuses = try await api.getCaseStatuses(listSourceUrl: listSourceUrl, caseNumbers: caseNumbers)
            let statusMap = Dictionary(uniqueKeysWithValues: statuses.map { ($0.caseNumber, $0.done) })

            items = items.map { item in
                var updated = item
                updated.done = statusMap[item.caseKey] ?? false
                return updated
            }
        } catch {
            print("Failed to load case statuses: \(error)")
        }
    }

    // MARK: - Comments

    func openComments(for item: CaseItem) {
        selectedItem = item
        showCommentsSheet = true
        Task {
            await loadComments(for: item)
        }
    }

    func openListComments(for list: CourtList, overrideCaseKey: String? = nil) {
        let caseKey = overrideCaseKey ?? list.dateText
        listCommentsTitle = list.name

        // Create a temporary item for list comments
        selectedItem = CaseItem(
            id: -1,
            listSourceUrl: list.sourceUrl,
            listNumber: nil,
            caseNumber: caseKey,
            title: list.name,
            parties: nil
        )
        showListCommentsSheet = true
        Task {
            await loadComments(listSourceUrl: list.sourceUrl, caseNumber: caseKey)
        }
    }

    func loadComments(for item: CaseItem) async {
        await loadComments(listSourceUrl: item.listSourceUrl, caseNumber: item.caseKey)
    }

    private func loadComments(listSourceUrl: String, caseNumber: String) async {
        isLoadingComments = true

        do {
            comments = try await api.getComments(listSourceUrl: listSourceUrl, caseNumber: caseNumber)
        } catch {
            print("Failed to load comments: \(error)")
            comments = []
        }

        isLoadingComments = false
    }

    func sendComment(content: String, urgent: Bool = false) {
        guard let item = selectedItem else { return }

        Task {
            do {
                try await api.addComment(
                    listSourceUrl: item.listSourceUrl,
                    caseNumber: item.caseKey,
                    content: content,
                    urgent: urgent
                )

                // Reload comments
                await loadComments(for: item)

                // Update comment count in list
                updateCommentCount(for: item, increment: 1, hasUrgent: urgent)
            } catch {
                errorMessage = "Failed to send comment: \(error.localizedDescription)"
            }
        }
    }

    func deleteComment(_ comment: Comment) {
        guard let id = comment.id else { return }

        Task {
            do {
                try await api.deleteComment(id: id)

                // Remove from local list
                comments.removeAll { $0.id == id }

                // Update comment count
                if let item = selectedItem {
                    let stillHasUrgent = comments.contains { $0.urgent }
                    updateCommentCount(for: item, increment: -1, hasUrgent: stillHasUrgent)
                }
            } catch {
                errorMessage = "Failed to delete comment: \(error.localizedDescription)"
            }
        }
    }

    private func updateCommentCount(for item: CaseItem, increment: Int, hasUrgent: Bool) {
        if let index = items.firstIndex(where: { $0.id == item.id }) {
            items[index].commentCount = max(0, items[index].commentCount + increment)
            items[index].hasUrgent = hasUrgent
        }
    }

    // MARK: - Done Status

    func toggleDone(for item: CaseItem) {
        let newDone = !item.done

        // Optimistic update
        if let index = items.firstIndex(where: { $0.id == item.id }) {
            items[index].done = newDone
        }

        Task {
            do {
                try await api.upsertCaseStatus(
                    listSourceUrl: item.listSourceUrl,
                    caseNumber: item.caseKey,
                    done: newDone
                )
            } catch {
                // Revert on failure
                if let index = items.firstIndex(where: { $0.id == item.id }) {
                    items[index].done = !newDone
                }
                errorMessage = "Failed to update status: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Watched Cases

    func loadWatchedCases() async {
        do {
            let watched = try await api.getWatchedCases()
            watchedCaseKeys = Set(watched.map { "\($0.listSourceUrl)|\($0.caseNumber)" })
        } catch {
            print("Failed to load watched cases: \(error)")
        }
    }

    func isWatching(_ item: CaseItem) -> Bool {
        watchedCaseKeys.contains("\(item.listSourceUrl)|\(item.caseKey)")
    }

    func toggleWatch(for item: CaseItem) {
        let key = "\(item.listSourceUrl)|\(item.caseKey)"

        if watchedCaseKeys.contains(key) {
            // Unwatch
            watchedCaseKeys.remove(key)
            Task {
                do {
                    try await api.unwatchCase(listSourceUrl: item.listSourceUrl, caseNumber: item.caseKey)
                } catch {
                    print("Unwatch error: \(error)")
                    watchedCaseKeys.insert(key)
                    errorMessage = "Failed to unwatch: \(error.localizedDescription)"
                }
            }
        } else {
            // Watch
            watchedCaseKeys.insert(key)
            Task {
                do {
                    try await api.watchCase(listSourceUrl: item.listSourceUrl, caseNumber: item.caseKey)
                } catch {
                    print("Watch error: \(error)")
                    watchedCaseKeys.remove(key)
                    errorMessage = "Failed to watch: \(error.localizedDescription)"
                }
            }
        }
    }

    // MARK: - Notifications

    func loadNotifications() async {
        do {
            notifications = try await api.getNotifications()
        } catch {
            print("Failed to load notifications: \(error)")
        }
    }

    func markNotificationRead(_ notification: AppNotification) {
        Task {
            do {
                try await api.markNotificationRead(id: notification.id)
                if let index = notifications.firstIndex(where: { $0.id == notification.id }) {
                    // Update local state (can't directly modify since it's immutable)
                    var updated = notifications
                    let old = updated[index]
                    updated[index] = AppNotification(
                        id: old.id,
                        userId: old.userId,
                        type: old.type,
                        listSourceUrl: old.listSourceUrl,
                        caseNumber: old.caseNumber,
                        caseTitle: old.caseTitle,
                        actorName: old.actorName,
                        actorId: old.actorId,
                        content: old.content,
                        read: true,
                        createdAt: old.createdAt
                    )
                    notifications = updated
                }
            } catch {
                print("Failed to mark notification read: \(error)")
            }
        }
    }

    func markAllNotificationsRead() {
        Task {
            do {
                try await api.markAllNotificationsRead()
                notifications = notifications.map { notification in
                    AppNotification(
                        id: notification.id,
                        userId: notification.userId,
                        type: notification.type,
                        listSourceUrl: notification.listSourceUrl,
                        caseNumber: notification.caseNumber,
                        caseTitle: notification.caseTitle,
                        actorName: notification.actorName,
                        actorId: notification.actorId,
                        content: notification.content,
                        read: true,
                        createdAt: notification.createdAt
                    )
                }
            } catch {
                print("Failed to mark all notifications read: \(error)")
            }
        }
    }

    func deleteNotification(_ notification: AppNotification) {
        Task {
            do {
                try await api.deleteNotification(id: notification.id)
                notifications.removeAll { $0.id == notification.id }
            } catch {
                print("Failed to delete notification: \(error)")
            }
        }
    }

    func deleteAllNotifications() {
        Task {
            do {
                try await api.deleteAllNotifications()
                notifications.removeAll()
            } catch {
                print("Failed to delete all notifications: \(error)")
            }
        }
    }

    // MARK: - Cleanup

    func disconnect() {
        realtimeClient.disconnect()
    }
}

// MARK: - RealtimeClientDelegate

extension MainViewModel: RealtimeClientDelegate {
    nonisolated func realtimeClient(_ client: RealtimeClient, didReceiveNotification notification: AppNotification) {
        Task { @MainActor in
            notifications.insert(notification, at: 0)
        }
    }

    nonisolated func realtimeClient(_ client: RealtimeClient, didReceiveCommentUpdate comment: Comment, eventType: String) {
        Task { @MainActor in
            // Update timestamp for this specific list
            lastUpdateByList[comment.listSourceUrl] = Date()

            // Refresh comments if we're viewing the same case
            if let item = selectedItem,
               comment.listSourceUrl == item.listSourceUrl,
               comment.caseNumber == item.caseKey {
                await loadComments(for: item)
            }

            // Update comment counts
            if let list = selectedList, comment.listSourceUrl == list.sourceUrl {
                await loadCommentCounts(for: list.sourceUrl)
            }
        }
    }

    nonisolated func realtimeClient(_ client: RealtimeClient, didReceiveStatusUpdate status: CaseStatus, eventType: String) {
        Task { @MainActor in
            // Update timestamp for this specific list
            lastUpdateByList[status.listSourceUrl] = Date()

            if let list = selectedList, status.listSourceUrl == list.sourceUrl {
                if let index = items.firstIndex(where: { $0.caseKey == status.caseNumber }) {
                    items[index].done = status.done
                }
            }
        }
    }

    nonisolated func realtimeClientDidConnect(_ client: RealtimeClient) {
        print("Realtime connected")
    }

    nonisolated func realtimeClientDidDisconnect(_ client: RealtimeClient) {
        print("Realtime disconnected")
    }
}
