@file:Suppress("ktlint:standard:max-line-length", "HasPlatformType")

package app.keemobile.kotpass.resources

import kotlin.uuid.Uuid

internal object DatabaseRes {
    object GroupsAndEntries {
        val Group1 = Uuid.parse("c997344c-952b-e02b-06a6-29510ce71a12")
        val Group2 = Uuid.parse("928f39d5-e1b6-88a9-f4f1-b60b36399186")
        val Group3 = Uuid.parse("36023bf1-4278-b680-ee34-653e7a1348bc")

        val Entry1 = Uuid.parse("ba06b36c-c7c8-8f8c-655f-a39dc403c6fa")
        val Entry2 = Uuid.parse("208e2034-9fc5-c955-5cbf-46d892123316")
        val Entry3 = Uuid.parse("4e805fdc-8305-7909-2574-d8e2ae2e520a")
    }
}
