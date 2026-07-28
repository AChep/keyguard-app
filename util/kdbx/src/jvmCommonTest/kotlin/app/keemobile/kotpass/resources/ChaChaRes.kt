package app.keemobile.kotpass.resources

internal object ChaChaRes {
    val ChaChaTestCases = listOf(
        StreamCipherTestCase(
            rounds = 8,
            key = "00000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0000000000000000",
            output = "3E00EF2F895F40D67F5BB8E81F09A5A1" +
                "2C840EC3CE9A7F3B181BE188EF711A1E" +
                "984CE172B9216F419F445367456D5619" +
                "314A42A3DA86B001387BFDB80E0CFE42"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "00000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0000000000000000",
            output = "9BF49A6A0755F953811FCE125F2683D5" +
                "0429C3BB49E074147E0089A52EAE155F" +
                "0564F879D27AE3C02CE82834ACFA8C79" +
                "3A629F2CA0DE6919610BE82F411326BE"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "01000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0000000000000000",
            output = "CF5EE9A0494AA9613E05D5ED725B804B" +
                "12F4A465EE635ACC3A311DE8740489EA" +
                "289D04F43C7518DB56EB4433E498A123" +
                "8CD8464D3763DDBB9222EE3BD8FAE3C8"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "01000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0000000000000000",
            output = "12056E595D56B0F6EEF090F0CD25A209" +
                "49248C2790525D0F930218FF0B4DDD10" +
                "A6002239D9A454E29E107A7D06FEFDFE" +
                "F0210FEBA044F9F29B1772C960DC29C0"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "00000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0100000000000000",
            output = "2B8F4BB3798306CA5130D47C4F8D4ED1" +
                "3AA0EDCCC1BE6942090FAEECA0D7599B" +
                "7FF0FE616BB25AA0153AD6FDC88B9549" +
                "03C22426D478B97B22B8F9B1DB00CF06"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "00000000000000000000000000000000" +
                "00000000000000000000000000000000",
            iv = "0100000000000000",
            output = "64B8BDF87B828C4B6DBAF7EF698DE03D" +
                "F8B33F635714418F9836ADE59BE12969" +
                "46C953A0F38ECFFC9ECB98E81D5D99A5" +
                "EDFC8F9A0A45B9E41EF3B31F028F1D0F"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            iv = "FFFFFFFFFFFFFFFF",
            output = "E163BBF8C9A739D18925EE8362DAD2CD" +
                "C973DF05225AFB2AA26396F2A9849A4A" +
                "445E0547D31C1623C537DF4BA85C70A9" +
                "884A35BCBF3DFAB077E98B0F68135F54"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
                "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
            iv = "FFFFFFFFFFFFFFFF",
            output = "04BF88DAE8E47A228FA47B7E6379434B" +
                "A664A7D28F4DAB84E5F8B464ADD20C3A" +
                "CAA69C5AB221A23A57EB5F345C96F4D1" +
                "322D0A2FF7A9CD43401CD536639A615A"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "55555555555555555555555555555555" +
                "55555555555555555555555555555555",
            iv = "5555555555555555",
            output = "7CB78214E4D3465B6DC62CF7A1538C88" +
                "996952B4FB72CB6105F1243CE3442E29" +
                "75A59EBCD2B2A598290D7538491FE65B" +
                "DBFEFD060D88798120A70D049DC2677D"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "55555555555555555555555555555555" +
                "55555555555555555555555555555555",
            iv = "5555555555555555",
            output = "A600F07727FF93F3DA00DD74CC3E8BFB" +
                "5CA7302F6A0A2944953DE00450EECD40" +
                "B860F66049F2EAED63B2EF39CC310D2C" +
                "488F5D9A241B615DC0AB70F921B91B95"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            iv = "AAAAAAAAAAAAAAAA",
            output = "40F9AB86C8F9A1A0CDC05A75E5531B61" +
                "2D71EF7F0CF9E387DF6ED6972F0AAE21" +
                "311AA581F816C90E8A99DE990B6B95AA" +
                "C92450F4E112712667B804C99E9C6EDA"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            iv = "AAAAAAAAAAAAAAAA",
            output = "856505B01D3B47AAE03D6A97AA0F033A" +
                "9ADCC94377BABD8608864FB3F625B6E3" +
                "14F086158F9F725D811EEB953B7F7470" +
                "76E4C3F639FA841FAD6C9A709E621397"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "00112233445566778899AABBCCDDEEFF" +
                "FFEEDDCCBBAA99887766554433221100",
            iv = "0F1E2D3C4B5A6978",
            output = "DB43AD9D1E842D1272E4530E276B3F56" +
                "8F8859B3F7CF6D9D2C74FA53808CB515" +
                "7A8EBF46AD3DCC4B6C7DADDE131784B0" +
                "120E0E22F6D5F9FFA7407D4A21B695D9"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "00112233445566778899AABBCCDDEEFF" +
                "FFEEDDCCBBAA99887766554433221100",
            iv = "0F1E2D3C4B5A6978",
            output = "7ED12A3A63912AE941BA6D4C0D5E862E" +
                "568B0E5589346935505F064B8C2698DB" +
                "F7D850667D8E67BE639F3B4F6A16F92E" +
                "65EA80F6C7429445DA1FC2C1B9365040"
        ),
        StreamCipherTestCase(
            rounds = 8,
            key = "C46EC1B18CE8A878725A37E780DFB735" +
                "1F68ED2E194C79FBC6AEBEE1A667975D",
            iv = "1ADA31D5CF688221",
            output = "838751B42D8DDD8A3D77F48825A2BA75" +
                "2CF4047CB308A5978EF274973BE374C9" +
                "6AD848065871417B08F034E681FE46A9" +
                "3F7D5C61D1306614D4AAF257A7CFF08B"
        ),
        StreamCipherTestCase(
            rounds = 12,
            key = "C46EC1B18CE8A878725A37E780DFB735" +
                "1F68ED2E194C79FBC6AEBEE1A667975D",
            iv = "1ADA31D5CF688221",
            output = "1482072784BC6D06B4E73BDC118BC010" +
                "3C7976786CA918E06986AA251F7E9CC1" +
                "B2749A0A16EE83B4242D2E99B08D7C20" +
                "092B80BC466C87283B61B1B39D0FFBAB"
        )
    )
}
