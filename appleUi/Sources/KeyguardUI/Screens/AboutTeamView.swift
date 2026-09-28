import SwiftUI
import KeyguardShared

struct AboutTeamView: View {
    @Environment(AppInformationModel.self) private var appInformationModel
    @State private var content: AboutTeamSnapshot?

    var body: some View {
        Group {
            if let content {
                Form {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                Text(content.name)
                                    .font(.title2.weight(.semibold))
                                Text(content.flag)
                                    .font(.title2)
                            }
                            Text(content.about)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 4)
                    }
                    Section(L10n.teamFollowMeSection) {
                        ForEach(content.socialNetworks, id: \.url) { social in
                            socialRow(social)
                        }
                    }
                }
                .formStyle(.grouped)
            } else {
                LoadingIndicator()
            }
        }
        .task { content = await appInformationModel.loadAboutTeam() }
    }

    @ViewBuilder
    private func socialRow(_ social: AboutTeamSocialSnapshot) -> some View {
        if let url = URL(string: social.url) {
            Link(destination: url) {
                HStack(spacing: 12) {
                    Image(systemName: "link")
                        .foregroundStyle(.tint)
                        .frame(width: 20)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(social.title)
                            .foregroundStyle(.primary)
                        Text("@\(social.username)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "arrow.up.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}
