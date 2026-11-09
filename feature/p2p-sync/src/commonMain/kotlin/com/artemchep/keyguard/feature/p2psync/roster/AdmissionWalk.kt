package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.model.RosterDevice

/**
 * Returns the devices reachable from the [founder] through the admissions of the [records],
 * by id, following an admission only if [admits] it.
 *
 * The founder admits itself; any other record names its inviter in
 * [RosterDevice.addedBy]. The walk goes breadth first, inviters in the order they were
 * reached and the records each one admits by id. Of the records of one id that an inviter
 * admits, the newer one is kept, see [RosterCrdt.recordOrder].
 */
internal fun walkAdmissions(
    founder: RosterDevice?,
    records: Collection<RosterDevice>,
    admits: (inviter: RosterDevice, device: RosterDevice) -> Boolean,
): Map<String, RosterDevice> {
    val found = mutableMapOf<String, RosterDevice>()
    founder ?: return found
    val invited = records
        .filter { it.id != founder.id && it.addedBy != it.id }
        .groupBy { it.addedBy }
    found[founder.id] = founder
    val queue = ArrayDeque(listOf(founder))
    while (true) {
        val inviter = queue.removeFirstOrNull() ?: break
        invited[inviter.id].orEmpty()
            .filter { it.id !in found && admits(inviter, it) }
            .groupBy { it.id }
            .entries
            .sortedBy { it.key }
            .forEach { (id, candidates) ->
                val record = candidates.maxWith(RosterCrdt.recordOrder)
                found[id] = record
                queue += record
            }
    }
    return found
}
