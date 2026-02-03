import SwiftUI

struct CommentsSheet: View {
    let title: String
    let comments: [Comment]
    let currentUserId: String
    let isLoading: Bool
    let onDismiss: () -> Void
    let onSendComment: (String, Bool) -> Void
    let onDeleteComment: (Comment) -> Void

    @State private var commentText = ""
    @State private var isUrgent = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Title
                Text(title)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.bottom, 8)

                Divider()

                // Comments list
                if isLoading {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else if comments.isEmpty {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "bubble.right")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                        Text("No comments yet")
                            .foregroundColor(.secondary)
                        Text("Start the discussion!")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(comments) { comment in
                                CommentRow(
                                    comment: comment,
                                    isOwn: comment.userId == currentUserId,
                                    onDelete: { onDeleteComment(comment) }
                                )
                            }
                        }
                        .padding()
                    }
                }

                Divider()

                // Urgency toggle
                HStack {
                    Image(systemName: "hand.raised.fill")
                        .foregroundColor(isUrgent ? .red : .secondary)

                    Text("Need help")
                        .foregroundColor(isUrgent ? .red : .secondary)

                    Toggle("", isOn: $isUrgent)
                        .labelsHidden()
                        .tint(.red)
                }
                .padding(.horizontal)
                .padding(.top, 12)

                // Input field
                HStack(spacing: 12) {
                    TextField("Add a comment...", text: $commentText, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...3)

                    Button {
                        if !commentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            onSendComment(commentText, isUrgent)
                            commentText = ""
                            isUrgent = false
                        }
                    } label: {
                        Image(systemName: "paperplane.fill")
                            .foregroundColor(commentText.isEmpty ? .gray : .blue)
                    }
                    .disabled(commentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding()
            }
            .navigationTitle("Comments")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        onDismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

struct CommentRow: View {
    let comment: Comment
    let isOwn: Bool
    let onDelete: () -> Void

    var formattedDate: String {
        guard let createdAt = comment.createdAt else { return "" }

        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        // Try with fractional seconds first
        if let date = parser.date(from: createdAt) {
            return formatDate(date)
        }

        // Try without fractional seconds
        parser.formatOptions = [.withInternetDateTime]
        if let date = parser.date(from: createdAt) {
            return formatDate(date)
        }

        // Fallback: try basic format
        let basicParser = DateFormatter()
        basicParser.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        let cleanedDate = createdAt
            .replacingOccurrences(of: "+00:00", with: "")
            .replacingOccurrences(of: "Z", with: "")
            .components(separatedBy: ".").first ?? createdAt

        if let date = basicParser.date(from: cleanedDate) {
            return formatDate(date)
        }

        return createdAt
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy HH:mm"
        return formatter.string(from: date)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                if comment.urgent {
                    Image(systemName: "hand.raised.fill")
                        .font(.caption)
                        .foregroundColor(.red)
                }

                Text(comment.authorName)
                    .font(.caption)
                    .fontWeight(.semibold)

                Text(formattedDate)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                Spacer()

                if isOwn {
                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                }
            }

            Text(comment.content)
                .font(.body)
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(backgroundColor)
        )
    }

    var backgroundColor: Color {
        if comment.urgent {
            return Color.red.opacity(0.1)
        } else if isOwn {
            return Color.blue.opacity(0.1)
        } else {
            return Color.gray.opacity(0.1)
        }
    }
}

#Preview {
    CommentsSheet(
        title: "Sample Case",
        comments: [
            Comment(
                id: 1,
                listSourceUrl: "test",
                caseNumber: "123",
                userId: "user1",
                authorName: "John Doe",
                content: "This is a test comment",
                urgent: false,
                createdAt: "2024-01-15T10:30:00Z"
            ),
            Comment(
                id: 2,
                listSourceUrl: "test",
                caseNumber: "123",
                userId: "user2",
                authorName: "Jane Smith",
                content: "Need help with this!",
                urgent: true,
                createdAt: "2024-01-15T11:00:00Z"
            )
        ],
        currentUserId: "user1",
        isLoading: false,
        onDismiss: {},
        onSendComment: { _, _ in },
        onDeleteComment: { _ in }
    )
}
