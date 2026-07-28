package app.keemobile.kotpass.resources

import app.keemobile.kotpass.io.encodeBase64

object DefaultXmlContentParserRes {
    val BasicXml = """
    <?xml version="1.0" encoding="utf-8" standalone="yes"?>
    <KeePassFile>
        <Meta>
            <Generator>${MetaRes.DummyText}</Generator>
            <DatabaseName>${MetaRes.DummyText}</DatabaseName>
            <DatabaseNameChanged>${TimeDataRes.DateTimeText}</DatabaseNameChanged>
            <DatabaseDescription>${MetaRes.DummyText}</DatabaseDescription>
            <DatabaseDescriptionChanged>${TimeDataRes.DateTimeText}</DatabaseDescriptionChanged>
            <DefaultUserName>${MetaRes.DummyText}</DefaultUserName>
            <DefaultUserNameChanged>${TimeDataRes.DateTimeText}</DefaultUserNameChanged>
            <MaintenanceHistoryDays>365</MaintenanceHistoryDays>
            <Color>#000000</Color>
            <MasterKeyChanged>${TimeDataRes.DateTimeText}</MasterKeyChanged>
            <MasterKeyChangeRec>-1</MasterKeyChangeRec>
            <MasterKeyChangeForce>-1</MasterKeyChangeForce>
            <MemoryProtection>
                <ProtectTitle>True</ProtectTitle>
                <ProtectUserName>True</ProtectUserName>
                <ProtectPassword>True</ProtectPassword>
                <ProtectURL>True</ProtectURL>
                <ProtectNotes>True</ProtectNotes>
            </MemoryProtection>
            <CustomIcons />
            <RecycleBinEnabled>False</RecycleBinEnabled>
            <RecycleBinChanged>${TimeDataRes.DateTimeText}</RecycleBinChanged>
            <HistoryMaxItems>10</HistoryMaxItems>
            <HistoryMaxSize>6291456</HistoryMaxSize>
            <Binaries>
                <Binary ID="0" Compressed="False">${MetaRes.DummyText.toByteArray().encodeBase64()}</Binary>
            </Binaries>
            <CustomData />
        </Meta>
        <Root>
            <Group>
                <UUID>tbwabsUKQEu3LmJ/wwKMpA==</UUID>
                <Name>Lorem</Name>
                <Notes />
                <IsExpanded>False</IsExpanded>
                <DefaultAutoTypeSequence />
                <EnableAutoType>null</EnableAutoType>
                <EnableSearching>null</EnableSearching>
                <LastTopVisibleEntry>AAAAAAAAAAAAAAAAAAAAAA==</LastTopVisibleEntry>
                <Group>
                    <UUID>tbwabsaKQEu3LmJ/wwKMpA==</UUID>
                    <Name>Ipsum</Name>
                    <Notes />
                    <IsExpanded>True</IsExpanded>
                    <DefaultAutoTypeSequence />
                    <EnableAutoType>null</EnableAutoType>
                    <EnableSearching>null</EnableSearching>
                    <LastTopVisibleEntry>AAAAAAAAAAAAAAAAAAAAAA==</LastTopVisibleEntry>
                </Group>
            </Group>
            <DeletedObjects />
        </Root>
    </KeePassFile>
    """.trimIndent()
}
