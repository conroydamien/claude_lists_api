import Foundation

struct User: Identifiable {
    let id: String
    let email: String?
    let displayName: String
    let photoUrl: URL?

    var initials: String {
        let components = displayName.split(separator: " ")
        if components.count >= 2 {
            return String(components[0].prefix(1) + components[1].prefix(1)).uppercased()
        }
        return String(displayName.prefix(2)).uppercased()
    }
}
