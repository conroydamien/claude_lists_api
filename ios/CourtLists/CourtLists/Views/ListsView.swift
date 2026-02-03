import SwiftUI

struct ListsView: View {
    @EnvironmentObject var viewModel: MainViewModel
    @EnvironmentObject var authManager: AuthManager
    @State private var datePickerId = UUID()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Filters row
                HStack(spacing: 12) {
                    // Date picker
                    DatePicker(
                        "Date",
                        selection: $viewModel.selectedDate,
                        displayedComponents: .date
                    )
                    .datePickerStyle(.compact)
                    .labelsHidden()
                    .id(datePickerId)
                    .onChange(of: viewModel.selectedDate) { _ in
                        // Force dismiss the picker by changing its id
                        datePickerId = UUID()
                        viewModel.selectedVenue = nil
                        Task {
                            await viewModel.loadLists()
                        }
                    }

                    // Venue filter
                    Picker("Venue", selection: $viewModel.selectedVenue) {
                        Text("All venues").tag(nil as String?)
                        ForEach(viewModel.venues, id: \.self) { venue in
                            Text(venue).tag(venue as String?)
                        }
                    }
                    .pickerStyle(.menu)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color(.systemGray6))
                    .cornerRadius(8)
                }
                .padding()

                Divider()

                // List count
                if !viewModel.isLoadingLists && !viewModel.lists.isEmpty {
                    Text("\(viewModel.filteredLists.count) lists")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal)
                        .padding(.top, 8)
                }

                // Lists
                if viewModel.isLoadingLists {
                    Spacer()
                    ProgressView("Loading lists...")
                    Spacer()
                } else if viewModel.filteredLists.isEmpty {
                    Spacer()
                    Text(viewModel.lists.isEmpty ? "No court lists for this date" : "No lists match the selected filter")
                        .foregroundColor(.secondary)
                    Spacer()
                } else {
                    List(viewModel.filteredLists) { list in
                        NavigationLink(value: list) {
                            ListRow(list: list)
                        }
                        .swipeActions(edge: .trailing) {
                            Button {
                                viewModel.openListComments(for: list)
                            } label: {
                                Label("Comments", systemImage: "bubble.right")
                            }
                            .tint(.blue)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Court Lists")
            .navigationDestination(for: CourtList.self) { list in
                ItemsView(list: list)
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if let user = authManager.currentUser {
                        Menu {
                            Text(user.displayName)
                            if let email = user.email {
                                Text(email)
                            }
                            Divider()
                            Button("Sign Out", role: .destructive) {
                                authManager.signOut()
                            }
                        } label: {
                            if let photoUrl = user.photoUrl {
                                AsyncImage(url: photoUrl) { image in
                                    image
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 32, height: 32)
                                        .clipShape(Circle())
                                } placeholder: {
                                    Circle()
                                        .fill(Color.gray.opacity(0.3))
                                        .frame(width: 32, height: 32)
                                        .overlay(Text(user.initials).font(.caption))
                                }
                            } else {
                                Circle()
                                    .fill(Color.blue)
                                    .frame(width: 32, height: 32)
                                    .overlay(Text(user.initials).font(.caption).foregroundColor(.white))
                            }
                        }
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.showNotifications = true
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bell")
                            if viewModel.unreadNotificationCount > 0 {
                                Text("\(viewModel.unreadNotificationCount)")
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .foregroundColor(.white)
                                    .padding(4)
                                    .background(Color.red)
                                    .clipShape(Circle())
                                    .offset(x: 8, y: -8)
                            }
                        }
                    }
                }
            }
            .sheet(isPresented: $viewModel.showNotifications) {
                NotificationsView()
            }
            .sheet(isPresented: $viewModel.showListCommentsSheet) {
                if let item = viewModel.selectedItem {
                    CommentsSheet(
                        title: viewModel.listCommentsTitle ?? item.title,
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
            .task {
                await viewModel.loadLists()
            }
        }
    }
}

struct ListRow: View {
    let list: CourtList

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(list.name)
                .font(.headline)

            HStack {
                Text(list.venue)
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                if let type = list.type, !type.isEmpty {
                    Text("•")
                        .foregroundColor(.secondary)
                    Text(type)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }

            if let updated = list.updated {
                Text("Updated: \(updated)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    ListsView()
        .environmentObject(MainViewModel())
        .environmentObject(AuthManager())
}
