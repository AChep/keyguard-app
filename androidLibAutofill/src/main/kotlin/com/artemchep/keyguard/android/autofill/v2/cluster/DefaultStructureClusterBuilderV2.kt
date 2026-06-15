package com.artemchep.keyguard.android.autofill.v2.cluster

import com.artemchep.keyguard.android.autofill.v2.model.ButtonNode
import com.artemchep.keyguard.android.autofill.v2.model.ClusterType
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2

/**
 * Default [StructureClusterBuilderV2] that groups fields by their cluster ID
 * (assigned during extraction from `<form>` boundaries) and classifies each
 * cluster's type using multilingual regex patterns (21 languages).
 *
 * Classification priority: OTP > AUTH > PAYMENT > ADDRESS > CONTACT > PROFILE > ACCOUNT > GENERAL.
 */
class DefaultStructureClusterBuilderV2 : StructureClusterBuilderV2 {
    override fun build(extracted: NormalizedStructureV2): NormalizedStructureV2 {
        if (extracted.fields.isEmpty()) {
            return extracted
        }

        val fieldsByCluster = extracted.fields.groupBy { it.clusterId ?: DEFAULT_CLUSTER_ID }
        val buttonsByCluster = extracted.buttons.groupBy { it.clusterId ?: DEFAULT_CLUSTER_ID }
        val clusters =
            fieldsByCluster.entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<FieldNode>>> { entry ->
                        entry.value.minOf { it.index }
                    }.thenBy { it.key },
                ).map { (clusterId, fields) ->
                    val orderedFields = fields.sortedBy { it.index }
                    val buttons = buttonsByCluster[clusterId].orEmpty().sortedBy { it.index }
                    val formAction = extracted.formActions[clusterId]
                    FieldCluster(
                        id = clusterId,
                        type = classifyClusterType(orderedFields, buttons, formAction),
                        fieldIds = orderedFields.map { it.id },
                        buttons = buttons,
                        label = buildClusterLabel(orderedFields, buttons),
                        surroundingText = buildClusterText(orderedFields, buttons),
                    )
                }

        return extracted.copy(
            clusters = clusters,
        )
    }

    /**
     * Heuristically classifies a cluster's type based on its fields,
     * buttons, and form action URL. Priority order: OTP > AUTH > PAYMENT
     * > ADDRESS > CONTACT > PROFILE > GENERAL.
     */
    private fun classifyClusterType(
        fields: List<FieldNode>,
        buttons: List<ButtonNode>,
        formAction: String?,
    ): ClusterType {
        // Cache per-field blobs to avoid recomputing 3× per field
        // (once in fieldBlob, once in password check, once in identifier check).
        val cachedFieldBlobs = fields.associateWith { clusterFieldBlob(it) }

        val fieldBlob = cachedFieldBlobs.values.joinToString(" ")
        val buttonBlob = buildButtonClassificationBlob(buttons)
        val actionBlob = formAction?.lowercase().orEmpty()
        val combinedBlob = "$fieldBlob $buttonBlob $actionBlob"

        val hasPasswordField =
            fields.any { f ->
                f.effectiveType == "password" ||
                        PASSWORD_CLUSTER_REGEX.containsMatchIn(cachedFieldBlobs.getValue(f))
            }
        val hasIdentifierField =
            fields.any { f ->
                val effectiveType = f.effectiveType
                effectiveType == "email" || effectiveType == "tel" ||
                        IDENTIFIER_CLUSTER_REGEX.containsMatchIn(cachedFieldBlobs.getValue(f))
            }

        // OTP: small cluster with OTP keywords
        if (fields.size <= 2 && OTP_REGEX.containsMatchIn(combinedBlob)) {
            return ClusterType.OTP
        }

        // AUTH: has password or login/auth action
        if (hasPasswordField) return ClusterType.AUTH
        if (hasIdentifierField && AUTH_ACTION_REGEX.containsMatchIn(actionBlob)) {
            return ClusterType.AUTH
        }

        // PAYMENT: card/cvv/expiry keywords
        if (PAYMENT_REGEX.containsMatchIn(combinedBlob)) return ClusterType.PAYMENT

        // ADDRESS: address/postal/city/zip keywords
        if (ADDRESS_REGEX.containsMatchIn(combinedBlob)) return ClusterType.ADDRESS

        // CONTACT: contact/message/feedback but not auth
        if (CONTACT_REGEX.containsMatchIn(combinedBlob) && !hasIdentifierField) {
            return ClusterType.CONTACT
        }

        // PROFILE: has name fields + identifier but no password
        val hasNameField = NAME_REGEX.containsMatchIn(fieldBlob)
        if (hasNameField && hasIdentifierField && !hasPasswordField) {
            return ClusterType.PROFILE
        }

        // ACCOUNT: identifier field with account/settings context
        if (hasIdentifierField && ACCOUNT_REGEX.containsMatchIn(combinedBlob)) {
            return ClusterType.ACCOUNT
        }

        return ClusterType.GENERAL
    }

    /** Extended blob for cluster classification; includes autocomplete, id, type, and native signals. */
    private fun clusterFieldBlob(field: FieldNode): String =
        buildString {
            append(field.label.orEmpty())
            append(' ')
            append(field.name.orEmpty())
            append(' ')
            append(field.viewHint.orEmpty())
            append(' ')
            append(field.attributes["placeholder"].orEmpty())
            append(' ')
            append(field.attributes["autocomplete"].orEmpty())
            append(' ')
            append(field.attributes["id"].orEmpty())
            append(' ')
            append(field.htmlType.orEmpty())
            append(' ')
            append(field.contentDescription.orEmpty())
            append(' ')
            append(field.text.orEmpty())
            append(' ')
            append(field.idEntry.orEmpty())
        }.lowercase()

    @Suppress("unused") // Retained for readability; hot path uses cachedFieldBlobs instead.
    private fun buildFieldClassificationBlob(fields: List<FieldNode>): String = fields.joinToString(" ") { clusterFieldBlob(it) }

    private fun buildButtonClassificationBlob(buttons: List<ButtonNode>): String =
        buttons.joinToString(" ") { b ->
            "${b.label.orEmpty()} ${b.name.orEmpty()} ${b.htmlType.orEmpty()} ${b.attributes["id"].orEmpty()} ${b.text.orEmpty()} ${b.contentDescription.orEmpty()}"
                .lowercase()
        }

    private fun buildClusterLabel(
        fields: List<FieldNode>,
        buttons: List<ButtonNode>,
    ): String? {
        val candidates =
            buildList {
                buttons.forEach { button ->
                    add(button.label)
                    add(button.text)
                    add(button.contentDescription)
                    add(button.htmlType)
                    addAll(identifierTokens(button.name))
                    addAll(identifierTokens(button.attributes["id"]))
                }
                fields.forEach { field ->
                    add(field.label)
                    add(field.viewHint)
                    add(field.contentDescription)
                    add(field.attributes["placeholder"])
                    addAll(identifierTokens(field.name))
                    addAll(identifierTokens(field.attributes["id"]))
                    addAll(identifierTokens(field.idEntry))
                }
            }
        return candidates.firstNotBlankOrNull()
    }

    private fun buildClusterText(
        fields: List<FieldNode>,
        buttons: List<ButtonNode>,
    ): String? {
        val tokens = linkedSetOf<String>()
        buttons.forEach { button ->
            appendSignal(tokens, button.label)
            appendSignal(tokens, button.text)
            appendSignal(tokens, button.contentDescription)
            appendSignal(tokens, button.htmlType)
            appendSignal(tokens, button.attributes["value"])
            appendIdentifierSignals(tokens, button.name)
            appendIdentifierSignals(tokens, button.attributes["id"])
        }
        fields.forEach { field ->
            appendSignal(tokens, field.label)
            appendSignal(tokens, field.viewHint)
            appendSignal(tokens, field.contentDescription)
            appendSignal(tokens, field.attributes["placeholder"])
            appendIdentifierSignals(tokens, field.name)
            appendIdentifierSignals(tokens, field.attributes["id"])
            appendIdentifierSignals(tokens, field.idEntry)
        }
        return tokens.joinToString(separator = " ").takeIf { it.isNotBlank() }
    }

    private fun appendSignal(
        tokens: LinkedHashSet<String>,
        value: String?,
    ) {
        val normalized =
            value
                ?.trim()
                ?.replace(whitespaceRegex, " ")
                ?.takeIf { it.isNotBlank() }
                ?: return
        tokens += normalized
    }

    private fun appendIdentifierSignals(
        tokens: LinkedHashSet<String>,
        value: String?,
    ) {
        identifierTokens(value).forEach(tokens::add)
    }

    private fun identifierTokens(value: String?): List<String> {
        val normalized =
            value
                ?.trim()
                ?.replace(camelCaseRegex, "$1 $2")
                ?.replace(nonAlphaNumericRegex, " ")
                ?.replace(whitespaceRegex, " ")
                ?.trim()
                .orEmpty()
        if (normalized.isBlank()) {
            return emptyList()
        }
        return normalized
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun Iterable<String?>.firstNotBlankOrNull(): String? = firstOrNull { !it.isNullOrBlank() }?.trim()

    private companion object {
        private const val DEFAULT_CLUSTER_ID = "cluster-default"
        private val camelCaseRegex = Regex("([a-z0-9])([A-Z])")
        private val nonAlphaNumericRegex = Regex("[^A-Za-z0-9]+")
        private val whitespaceRegex = Regex("\\s+")

        // Cluster type classification patterns — multilingual (21 languages)
        private val PASSWORD_CLUSTER_REGEX =
            Regex(
                "(\\b(password|passwd|pwd|passcode|psw|pin" +
                        "|passwort|kennwort" + // German
                        "|mot.de.passe|motdepasse" + // French
                        "|senha" + // Portuguese
                        "|contrase.a|clave" + // Spanish
                        "|wachtwoord" + // Dutch
                        "|has.o|haslo|heslo" + // Polish / Czech
                        "|l.senord|losenord" + // Swedish
                        "|sifre|.ifre|parola" + // Turkish
                        "|sandi|kata.sandi|kata.laluan" + // Indonesian / Malay
                        ")\\b" +
                        // CJK / Cyrillic / Arabic / Thai / Hindi / Korean — no word boundaries
                        "|密码|密碼|口令" + // Chinese
                        "|パスワード|暗証番号" + // Japanese
                        "|비밀번호|암호" + // Korean
                        "|пароль|парол" + // Russian / Ukrainian
                        "|كلمة المرور|كلمة السر|رمز المرور" + // Arabic
                        "|mật khẩu|mat khau" + // Vietnamese
                        "|รหัสผ่าน" + // Thai
                        "|पासवर्ड" + // Hindi
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val IDENTIFIER_CLUSTER_REGEX =
            Regex(
                "(\\b(email|e-mail|username|user.?name|login|phone|tel|mobile" +
                        "|usuario|usu.rio" + // Spanish / Portuguese
                        "|identifiant|courriel" + // French
                        "|benutzername|anmeldename" + // German
                        "|gebruikersnaam" + // Dutch
                        "|u.ytkownik|uzytkownik" + // Polish
                        "|u.ivatel|uzivatel" + // Czech
                        "|anv.ndarnamn|anvandarnamn" + // Swedish
                        "|kullan.c." + // Turkish (kullanıcı)
                        ")\\b" +
                        // CJK / Cyrillic / Arabic / Thai / Hindi / Korean
                        "|邮箱|电子邮箱|電子郵箱|用户名|用戶名|账号|帳號" + // Chinese
                        "|メール|ユーザー名|ユーザ名" + // Japanese
                        "|이메일|사용자|아이디" + // Korean
                        "|почта|эл.почта|логин|пользователь|логін|користувач" + // Russian / Ukrainian
                        "|البريد الإلكتروني|اسم المستخدم|ايميل" + // Arabic
                        "|tên đăng nhập|tài khoản" + // Vietnamese
                        "|ชื่อผู้ใช้|อีเมล" + // Thai
                        "|ईमेल|उपयोगकर्ता" + // Hindi
                        "|nama pengguna|akun" + // Indonesian
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val OTP_REGEX =
            Regex(
                "(\\b(otp|one.?time|verification.?code|auth.?code|2fa|mfa|totp" +
                        "|best.tigungscode|verifizierungscode" + // German
                        "|code.de.v.rification" + // French
                        "|c.digo.de.verificaci.n" + // Spanish
                        "|verificatiecode" + // Dutch
                        "|kod.weryfikacyjny" + // Polish
                        "|ov..ovac..k.d" + // Czech (ověřovací kód)
                        "|verifieringskod" + // Swedish
                        "|do.rulama.kodu" + // Turkish
                        "|kode.verifikasi" + // Indonesian
                        ")\\b" +
                        // CJK / Cyrillic / Arabic / Thai / Hindi / Korean
                        "|验证码|驗證碼|动态密码|動態密碼" + // Chinese
                        "|認証コード|確認コード|ワンタイムパスワード" + // Japanese
                        "|인증번호|인증 코드|확인 코드" + // Korean
                        "|код подтверждения|одноразовый код|код підтвердження|одноразовий код" + // Russian / Ukrainian
                        "|رمز التحقق|رمز التأكيد" + // Arabic
                        "|mã xác minh|ma xac minh" + // Vietnamese
                        "|รหัสยืนยัน" + // Thai
                        "|सत्यापन कोड|ओटीपी" + // Hindi
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val AUTH_ACTION_REGEX =
            Regex(
                "(login|signin|sign.in|log.in|authenticate|auth" +
                        "|iniciar.sesi.n|entrar|acceder" + // Spanish
                        "|connexion|connecter" + // French
                        "|anmelden|einloggen" + // German
                        "|inloggen|aanmelden" + // Dutch
                        "|zaloguj" + // Polish
                        "|prihlasit|p.ihl.sit" + // Czech
                        "|logga.in" + // Swedish
                        "|giris|giri." + // Turkish
                        "|masuk|log.masuk" + // Indonesian / Malay
                        "|dang.nhap" + // Vietnamese
                        // CJK / Cyrillic / Arabic / Thai / Hindi / Korean
                        "|登录|登錄|登入" + // Chinese
                        "|ログイン|サインイン" + // Japanese
                        "|로그인" + // Korean
                        "|войти|вход|увійти|вхід" + // Russian / Ukrainian
                        "|تسجيل الدخول|دخول" + // Arabic
                        "|เข้าสู่ระบบ" + // Thai
                        "|लॉग इन|लॉगिन" + // Hindi
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val PAYMENT_REGEX =
            Regex(
                "(\\b(card.?number|credit.?card|debit.?card|cvv|cvc|expir|billing" +
                        "|kartennummer" + // German
                        "|num.ro.de.carte" + // French
                        "|n.mero.de.tarjeta" + // Spanish
                        "|numero.carta" + // Italian
                        "|kortnummer" + // Swedish
                        ")\\b" +
                        "|卡号|カード番号|카드번호" + // CJK / Korean
                        "|номер карты|номер картки" + // Russian / Ukrainian
                        "|رقم البطاقة" + // Arabic
                        "|หมายเลขบัตร" + // Thai
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val ADDRESS_REGEX =
            Regex(
                "(\\b(street|address|city|state|zip|postal|country|province" +
                        "|adresse|stra.e|strasse" + // German
                        "|adresse" + // French (same spelling)
                        "|direcci.n|direccion" + // Spanish
                        "|endere.o|endereco" + // Portuguese
                        "|indirizzo" + // Italian
                        "|adres" + // Dutch / Turkish / Polish
                        "|adresa" + // Czech
                        "|adress" + // Swedish
                        "|alamat" + // Indonesian
                        ")\\b" +
                        "|地址|住所|주소" + // CJK / Korean
                        "|адрес|адреса" + // Russian / Ukrainian
                        "|العنوان|عنوان" + // Arabic
                        "|ที่อยู่" + // Thai
                        "|पता" + // Hindi
                        "|địa chỉ|dia chi" + // Vietnamese
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val CONTACT_REGEX =
            Regex(
                "(\\b(contact|message|feedback|comment|review|inquiry" +
                        "|nachricht|kommentar" + // German
                        "|commentaire" + // French
                        "|comentario|mensaje" + // Spanish
                        "|messaggio|commento" + // Italian
                        "|bericht|opmerking" + // Dutch
                        "|komentarz|wiadomo.." + // Polish
                        "|yorum|mesaj" + // Turkish
                        "|komentar|pesan" + // Indonesian
                        "|meddelande|kommentar" + // Swedish
                        ")\\b" +
                        "|评论|留言|消息|コメント|メッセージ|댓글|메시지" + // CJK / Korean
                        "|комментарий|сообщение|коментар|повідомлення" + // Russian / Ukrainian
                        "|تعليق|رسالة" + // Arabic
                        "|ความคิดเห็น|ข้อความ" + // Thai
                        "|टिप्पणी|संदेश" + // Hindi
                        "|bình luận|tin nhắn" + // Vietnamese
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val NAME_REGEX =
            Regex(
                "(\\b(first.?name|last.?name|full.?name|given.?name|family.?name|surname" +
                        "|vorname|nachname" + // German
                        "|pr.nom|prenom|nom.de.famille" + // French
                        "|nombre|apellido" + // Spanish
                        "|nome|cognome|sobrenome" + // Italian / Portuguese
                        "|voornaam|achternaam" + // Dutch
                        "|imi.|imie|nazwisko" + // Polish
                        "|ad.soyad|soyad" + // Turkish
                        "|jm.no|jmeno|p..jmen.|prijmeni" + // Czech
                        "|f.rnamn|fornamn|efternamn" + // Swedish
                        "|nama.lengkap|nama.depan" + // Indonesian
                        ")\\b" +
                        "|姓名|名前|名字|氏名" + // CJK
                        "|이름|성명" + // Korean
                        "|имя|фамилия|ім'я|прізвище" + // Russian / Ukrainian
                        "|الاسم|الاسم الأول|اسم العائلة" + // Arabic
                        "|ชื่อ|นามสกุล" + // Thai
                        "|नाम|पूरा नाम" + // Hindi
                        "|họ tên|ho ten" + // Vietnamese
                        ")",
                RegexOption.IGNORE_CASE,
            )
        private val ACCOUNT_REGEX =
            Regex(
                "(\\b(account|settings|profile|preferences" +
                        "|konto|einstellungen" + // German
                        "|compte|param.tres|parametres" + // French
                        "|cuenta|configuraci.n" + // Spanish
                        "|instellingen|profiel" + // Dutch
                        "|ustawienia|profil" + // Polish
                        "|ayarlar|hesap" + // Turkish
                        "|nastaven.|nastaveni" + // Czech
                        "|inst.llningar|installningar" + // Swedish
                        "|pengaturan|akun" + // Indonesian
                        ")\\b" +
                        "|账号|帳號|设置|設定|アカウント|設定|계정|설정" + // CJK / Korean
                        "|аккаунт|настройки|обліковий запис|налаштування" + // Russian / Ukrainian
                        "|حساب|إعدادات" + // Arabic
                        "|บัญชี|การตั้งค่า" + // Thai
                        "|खाता|सेटिंग" + // Hindi
                        "|tài khoản|cài đặt" + // Vietnamese
                        ")",
                RegexOption.IGNORE_CASE,
            )
    }
}
