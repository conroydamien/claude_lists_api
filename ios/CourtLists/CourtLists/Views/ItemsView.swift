import SwiftUI

struct ItemsView: View {
    let list: CourtList
    @EnvironmentObject var viewModel: MainViewModel
    @EnvironmentObject var authManager: AuthManager

    // Timer for live updating counter
    @State private var currentTime = Date()
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var lastUpdateText: String {
        guard let lastUpdate = viewModel.lastStatusUpdateDate else {
            return "No updates"
        }
        let elapsed = Int(currentTime.timeIntervalSince(lastUpdate))
        let days = elapsed / 86400
        let hours = (elapsed % 86400) / 3600
        let minutes = (elapsed % 3600) / 60
        let seconds = elapsed % 60

        if days > 0 {
            return "Updated: \(days)d ago"
        } else if hours > 0 {
            return "Updated: \(hours)h \(minutes)m \(seconds)s ago"
        } else if minutes > 0 {
            return "Updated: \(minutes)m \(seconds)s ago"
        } else {
            return "Updated: \(seconds)s ago"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Custom header area (matches Android)
            VStack(alignment: .leading, spacing: 4) {
                Text(list.venue.isEmpty ? list.name : list.venue)
                    .font(.headline)
                if !list.dateText.isEmpty {
                    Text(list.dateText)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Text(lastUpdateText)
                    .font(.caption)
                    .foregroundColor(.blue)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(.systemBackground))

            // Main content
            ZStack {
                if viewModel.isLoadingItems {
                    ProgressView("Loading cases...")
                } else if let error = viewModel.errorMessage {
                    VStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundColor(.orange)
                        Text("Error loading cases")
                            .font(.headline)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                } else if viewModel.items.isEmpty {
                    Text("No cases in this list")
                        .foregroundColor(.secondary)
                } else {
                    List {
                        // Collapsible headers row (matches Android)
                        if !viewModel.headers.isEmpty {
                            Section {
                                CollapsibleHeaderRow(headers: viewModel.headers)
                            }
                        }

                        // List Notes row
                        Section {
                            ListNotesRow(list: list)
                        }

                        // Cases section
                        Section {
                            ForEach(viewModel.items) { item in
                                ItemRow(item: item) {
                                    viewModel.toggleDone(for: item)
                                } onCommentsTap: {
                                    viewModel.openComments(for: item)
                                } onWatchTap: {
                                    viewModel.toggleWatch(for: item)
                                }
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 16) {
                    // Notifications bell with badge
                    Button {
                        viewModel.showNotifications = true
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bell")
                            if viewModel.unreadNotificationCount > 0 {
                                Text(viewModel.unreadNotificationCount > 99 ? "99+" : "\(viewModel.unreadNotificationCount)")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(4)
                                    .background(Color.red)
                                    .clipShape(Circle())
                                    .offset(x: 8, y: -8)
                            }
                        }
                    }

                    // Refresh button
                    Button {
                        viewModel.refreshItems()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
        .onReceive(timer) { time in
            currentTime = time
        }
        .sheet(isPresented: $viewModel.showCommentsSheet) {
            if let item = viewModel.selectedItem {
                CommentsSheet(
                    title: item.title,
                    comments: viewModel.comments,
                    currentUserId: authManager.userId ?? "",
                    isLoading: viewModel.isLoadingComments,
                    onDismiss: { viewModel.showCommentsSheet = false },
                    onSendComment: { content, urgent in
                        viewModel.sendComment(content: content, urgent: urgent)
                    },
                    onDeleteComment: { comment in
                        viewModel.deleteComment(comment)
                    }
                )
            }
        }
        .sheet(isPresented: $viewModel.showListCommentsSheet) {
            if viewModel.selectedItem != nil {
                CommentsSheet(
                    title: viewModel.listCommentsTitle ?? list.name,
                    comments: viewModel.comments,
                    currentUserId: authManager.userId ?? "",
                    isLoading: viewModel.isLoadingComments,
                    onDismiss: { viewModel.showListCommentsSheet = false },
                    onSendComment: { content, urgent in
                        viewModel.sendComment(content: content, urgent: urgent)
                    },
                    onDeleteComment: { comment in
                        viewModel.deleteComment(comment)
                    }
                )
            }
        }
        .sheet(isPresented: $viewModel.showNotifications) {
            NotificationsView()
        }
        .onAppear {
            viewModel.selectList(list)
        }
    }
}

struct CollapsibleHeaderRow: View {
    let headers: [String]
    @State private var isExpanded = false

    private var headerText: String {
        headers.joined(separator: " · ")
    }

    var body: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                isExpanded.toggle()
            }
        } label: {
            HStack(spacing: 12) {
                // Vertical blue bar
                Rectangle()
                    .fill(Color(red: 0.098, green: 0.463, blue: 0.824)) // #1976D2
                    .frame(width: 3, height: 24)

                // Header text
                Text(headerText)
                    .font(.subheadline)
                    .foregroundColor(Color(red: 0.082, green: 0.396, blue: 0.753)) // #1565C0
                    .lineLimit(isExpanded ? nil : 1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)

                // Expand/collapse chevron
                Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    .font(.system(size: 14))
                    .foregroundColor(Color(red: 0.098, green: 0.463, blue: 0.824)) // #1976D2
            }
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .listRowBackground(Color(red: 0.941, green: 0.969, blue: 1.0)) // #F0F7FF
    }
}

struct ListNotesRow: View {
    let list: CourtList
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        Button {
            viewModel.openListComments(for: list)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "bubble.right")
                    .font(.system(size: 16))
                    .foregroundColor(.secondary)

                Text("List Notes")
                    .font(.body)
                    .foregroundColor(.secondary)

                Spacer()

                Image(systemName: "bell.slash")
                    .font(.system(size: 16))
                    .foregroundColor(.gray)
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

struct ItemRow: View {
    let item: CaseItem
    let onDoneTap: () -> Void
    let onCommentsTap: () -> Void
    let onWatchTap: () -> Void

    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        HStack(spacing: 12) {
            // Undo button or spacer (matches Android)
            if item.done {
                Button(action: onDoneTap) {
                    Image(systemName: "arrow.counterclockwise")
                        .font(.system(size: 16))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .frame(width: 32)
            } else {
                Spacer()
                    .frame(width: 32)
            }

            // List number
            if let listNumber = item.listNumber {
                Text("\(listNumber)")
                    .font(.subheadline)
                    .foregroundColor(.blue)
                    .frame(width: 32, alignment: .leading)
            }

            // Case info (case number bold on top, then title - matches Android)
            VStack(alignment: .leading, spacing: 2) {
                if let caseNumber = item.caseNumber {
                    Text(caseNumber)
                        .font(.subheadline)
                        .fontWeight(.bold)
                        .strikethrough(item.done)
                        .foregroundColor(item.done ? .secondary : .primary)
                }

                Text(item.title)
                    .font(.body)
                    .strikethrough(item.done)
                    .foregroundColor(item.done ? .secondary : .primary)
            }
            .contentShape(Rectangle())
            .onTapGesture {
                if !item.done {
                    onDoneTap()
                }
            }

            Spacer()

            // Watch button (bell icon like Android)
            Button(action: onWatchTap) {
                Image(systemName: viewModel.isWatching(item) ? "bell.fill" : "bell.slash")
                    .font(.system(size: 16))
                    .foregroundColor(viewModel.isWatching(item) ? .blue : .gray)
            }
            .buttonStyle(.plain)

            // Comments button
            Button(action: onCommentsTap) {
                HStack(spacing: 4) {
                    if item.hasUrgent {
                        Image(systemName: "hand.raised.fill")
                            .foregroundColor(.red)
                    } else {
                        Image(systemName: "bubble.right")
                            .foregroundColor(item.commentCount > 0 ? .blue : .gray)
                    }

                    if item.commentCount > 0 {
                        Text("\(item.commentCount)")
                            .font(.caption)
                            .foregroundColor(item.hasUrgent ? .red : .blue)
                    }
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
        .background(item.done ? Color.gray.opacity(0.15) : Color.clear)
    }
}

#Preview {
    NavigationStack {
        ItemsView(list: CourtList(
            id: 0,
            name: "Sample List",
            dateIso: "2024-01-15",
            dateText: "15th January 2024",
            venue: "Dublin",
            type: "Civil",
            sourceUrl: "https://example.com",
            updated: nil
        ))
        .environmentObject(MainViewModel())
        .environmentObject(AuthManager())
    }
}
