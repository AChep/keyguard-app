/// One client's log (a `CliAgentUsageHistoryClient` name), or one environment's log across clients.
struct CliAgentHistoryTarget: Equatable {
    static let cli = "CLI"
    static let mcp = "MCP"
    static let env = "ENV"

    let client: String?
    let cipherId: String?
}
