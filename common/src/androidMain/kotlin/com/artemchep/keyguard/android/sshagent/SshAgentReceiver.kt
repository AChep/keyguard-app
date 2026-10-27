package com.artemchep.keyguard.android.sshagent

import com.artemchep.keyguard.android.agentbridge.AgentBridge
import com.artemchep.keyguard.android.agentbridge.AgentBridgeReceiver

/** The broadcast target of the Termux SSH agent helper; its name is part of the helper's contract. */
class SshAgentReceiver : AgentBridgeReceiver(AgentBridge.SSH)
