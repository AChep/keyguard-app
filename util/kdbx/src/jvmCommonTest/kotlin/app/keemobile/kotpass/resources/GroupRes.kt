package app.keemobile.kotpass.resources

import app.keemobile.kotpass.constants.PredefinedIcon

internal object GroupRes {
    val BasicXml = """
    <Group>
        <UUID>tbwabsUKQEu3LmJ/wwKMpA==</UUID>
        <Name>Lorem</Name>
        <Notes />
        <IconID>${PredefinedIcon.Folder.ordinal}</IconID>
        <Times>
            <CreationTime>${TimeDataRes.DateTimeText}</CreationTime>
            <LastModificationTime>${TimeDataRes.DateTimeText}</LastModificationTime>
        </Times>
        <IsExpanded>False</IsExpanded>
        <DefaultAutoTypeSequence />
        <EnableAutoType>null</EnableAutoType>
        <EnableSearching>null</EnableSearching>
        <LastTopVisibleEntry>AAAAAAAAAAAAAAAAAAAAAA==</LastTopVisibleEntry>
        <Group>
            <UUID>tbwabsaKQEu3LmJ/wwKMpA==</UUID>
            <Name>Ipsum</Name>
            <Notes />
            <IconID>${PredefinedIcon.Folder.ordinal}</IconID>
            <IsExpanded>True</IsExpanded>
            <DefaultAutoTypeSequence />
            <EnableAutoType>null</EnableAutoType>
            <EnableSearching>null</EnableSearching>
            <LastTopVisibleEntry>AAAAAAAAAAAAAAAAAAAAAA==</LastTopVisibleEntry>
        </Group>
        ${EntryRes.BasicXml}
    </Group>
    """.trimIndent()
}
