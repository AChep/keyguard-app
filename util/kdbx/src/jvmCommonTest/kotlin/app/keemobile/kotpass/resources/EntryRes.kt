package app.keemobile.kotpass.resources

import app.keemobile.kotpass.constants.Defaults

internal object EntryRes {
    val BasicXml = """
    <Entry>
        <UUID>tbwabsUKQEu3LmJ/wwKMpA==</UUID>
        <IconID>42</IconID>
        <ForegroundColor>#000000</ForegroundColor>
        <BackgroundColor>#000000</BackgroundColor>
        <OverrideURL></OverrideURL>
        <Tags>lorem; ipsum; dolor</Tags>
        <Times>
            <CreationTime>${TimeDataRes.DateTimeText}</CreationTime>
            <LastModificationTime>${TimeDataRes.DateTimeText}</LastModificationTime>
            <Expires>False</Expires>
            <UsageCount>0</UsageCount>
            <LocationChanged>${TimeDataRes.DateTimeText}</LocationChanged>
        </Times>
        <String>
            <Key>custom1</Key>
            <Value>dummy</Value>
        </String>
        <String>
            <Key>custom2</Key>
            <Value>dummy</Value>
        </String>
        <String>
            <Value>content 1</Value>
        </String>
        <String>
            <Key>Notes</Key>
            <Value></Value>
        </String>
        <String>
            <Key>Password</Key>
            <Value>pwd</Value>
        </String>
        <String>
            <Key>Title</Key>
            <Value>Lorem</Value>
        </String>
        <String>
            <Key>URL</Key>
            <Value>http://lorem.ipsum</Value>
        </String>
        <String>
            <Key>UserName</Key>
            <Value>User</Value>
        </String>
        <String>
            <Value>content 2</Value>
        </String>
        <String>
            <Value>content 3</Value>
        </String>
        <String>
            <Key>${Defaults.UntitledLabel} (1)</Key>
            <Value>Fizz</Value>
        </String>
        <AutoType>
            <Enabled>True</Enabled>
            <DataTransferObfuscation>0</DataTransferObfuscation>
            <DefaultSequence>{USERNAME}{TAB}{PASSWORD}{ENTER}{custom}</DefaultSequence>
            <Association>
                <Window>chrome</Window>
                <KeystrokeSequence>{USERNAME}{TAB}{PASSWORD}{ENTER}{custom}{custom-key}</KeystrokeSequence>
            </Association>
        </AutoType>
        <History>
            <Entry>
                <UUID>tbwabsUKQEu3LmJ/wwKMpA==</UUID>
                <IconID>10</IconID>
                <OverrideURL></OverrideURL>
                <Times>
                    <CreationTime>${TimeDataRes.DateTimeText}</CreationTime>
                    <Expires>False</Expires>
                    <UsageCount>0</UsageCount>
                    <LocationChanged>${TimeDataRes.DateTimeText}</LocationChanged>
                </Times>
                <String>
                    <Key>custom3</Key>
                    <Value>dummy</Value>
                </String>
                <AutoType>
                    <Enabled>True</Enabled>
                    <DataTransferObfuscation>0</DataTransferObfuscation>
                    <DefaultSequence>{USERNAME}{TAB}{PASSWORD}{ENTER}{custom}</DefaultSequence>
                    <Association>
                        <Window>chrome</Window>
                        <KeystrokeSequence>{USERNAME}{TAB}{PASSWORD}{ENTER}{custom}{custom-key}</KeystrokeSequence>
                    </Association>
                </AutoType>
            </Entry>
        </History>
    </Entry>
    """.trimIndent()
}
