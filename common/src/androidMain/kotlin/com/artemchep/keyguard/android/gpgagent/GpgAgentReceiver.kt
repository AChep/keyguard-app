package com.artemchep.keyguard.android.gpgagent

import com.artemchep.keyguard.android.agentbridge.AgentBridge
import com.artemchep.keyguard.android.agentbridge.AgentBridgeReceiver

/** The broadcast target of the Termux GPG agent helper; its name is part of the helper's contract. */
class GpgAgentReceiver : AgentBridgeReceiver(AgentBridge.GPG)
