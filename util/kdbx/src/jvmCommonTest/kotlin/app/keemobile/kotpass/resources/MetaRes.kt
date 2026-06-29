package app.keemobile.kotpass.resources

import app.keemobile.kotpass.io.encodeBase64

internal object MetaRes {
    const val DummyText = "Kotpass demo"

    val BasicXml = """
    <Meta>
        <Generator>$DummyText</Generator>
        <DatabaseName>$DummyText</DatabaseName>
        <DatabaseNameChanged>${TimeDataRes.DateTimeText}</DatabaseNameChanged>
        <DatabaseDescription>$DummyText</DatabaseDescription>
        <DatabaseDescriptionChanged>${TimeDataRes.DateTimeText}</DatabaseDescriptionChanged>
        <DefaultUserName>$DummyText</DefaultUserName>
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
            <Binary ID="0" Compressed="False">${DummyText.toByteArray().encodeBase64()}</Binary>
        </Binaries>
        <CustomData />
    </Meta>
    """.trimIndent()
}
