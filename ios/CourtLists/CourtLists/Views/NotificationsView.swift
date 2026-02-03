import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.notifications.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "bell.slash")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text("No notifications")
                            .font(.headline)
                            .foregroundColor(.secondary)
                        Text("You'll see notifications here when someone comments on your watched cases")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                } else {
                    List {
                        ForEach(viewModel.notifications) { notification in
                            NotificationRow(notification: notification)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        viewModel.deleteNotification(notification)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                                .swipeActions(edge: .leading) {
                                    if !notification.read {
                                        Button {
                                            viewModel.markNotificationRead(notification)
                                        } label: {
                                            Label("Read", systemImage: "envelope.open")
                                        }
                                        .tint(.blue)
                                    }
                                }
                                .onTapGesture {
                                    handleNotificationTap(notification)
                                }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Notifications")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            viewModel.markAllNotificationsRead()
                        } label: {
                            Label("Mark All Read", systemImage: "envelope.open")
                        }

                        Button(role: .destructive) {
                            viewModel.deleteAllNotifications()
                        } label: {
                            Label("Delete All", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .disabled(viewModel.notifications.isEmpty)
                }
            }
        }
    }

    private func handleNotificationTap(_ notification: AppNotification) {
        viewModel.markNotificationRead(notification)
        // TODO: Navigate to the relevant case/list
        dismiss()
    }
}

struct NotificationRow: View {
    let notification: AppNotification

    var icon: String {
        switch notification.type {
        case "comment":
            return "bubble.right.fill"
        case "status_done":
            return "checkmark.circle.fill"
        case "status_undone":
            return "circle"
        default:
            return "bell.fill"
        }
    }

    var iconColor: Color {
        switch notification.type {
        case "comment":
            return .blue
        case "status_done":
            return .green
        case "status_undone":
            return .orange
        default:
            return .gray
        }
    }

    var message: String {
        switch notification.type {
        case "comment":
            return "\(notification.actorName) commented on \(notification.caseTitle ?? notification.caseNumber)"
        case "status_done":
            return "\(notification.actorName) marked \(notification.caseTitle ?? notification.caseNumber) as done"
        case "status_undone":
            return "\(notification.actorName) marked \(notification.caseTitle ?? notification.caseNumber) as not done"
        default:
            return "New notification"
        }
    }

    var formattedTime: String {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        guard let date = parser.date(from: notification.createdAt) ?? {
            parser.formatOptions = [.withInternetDateTime]
            return parser.date(from: notification.createdAt)
        }() else {
            return notification.createdAt
        }

        let now = Date()
        let interval = now.timeIntervalSince(date)

        if interval < 60 {
            return "Just now"
        } else if interval < 3600 {
            let minutes = Int(interval / 60)
            return "\(minutes)m ago"
        } else if interval < 86400 {
            let hours = Int(interval / 3600)
            return "\(hours)h ago"
        } else {
            let formatter = DateFormatter()
            formatter.dateFormat = "MMM d"
            return formatter.string(from: date)
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Unread indicator
            Circle()
                .fill(notification.read ? Color.clear : Color.blue)
                .frame(width: 8, height: 8)

            // Icon
            Image(systemName: icon)
                .foregroundColor(iconColor)
                .frame(width: 24)

            // Content
            VStack(alignment: .leading, spacing: 4) {
                Text(message)
                    .font(.subheadline)
                    .fontWeight(notification.read ? .regular : .semibold)

                if let content = notification.content, !content.isEmpty {
                    Text(content)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }

                Text(formattedTime)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

#Preview {
    NotificationsView()
        .environmentObject(MainViewModel())
}
