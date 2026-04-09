package com.artemchep.keyguard.android.autofill.v2.util

import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import java.util.Locale

// ------------------------------------------------------------------ //
//  Blob-building helpers
// ------------------------------------------------------------------ //

/**
 * Canonical lowercase blob aggregating every user-visible and
 * developer-set text signal on a field: label, name, id,
 * placeholder, viewHint, contentDescription, and text.
 */
fun fieldBlob(field: FieldNode): String =
    buildString {
        append(field.label.orEmpty())
        append(' ')
        append(field.name.orEmpty())
        append(' ')
        append(field.attributes["id"].orEmpty())
        append(' ')
        append(field.attributes["placeholder"].orEmpty())
        append(' ')
        append(field.viewHint.orEmpty())
        append(' ')
        append(field.contentDescription.orEmpty())
        append(' ')
        append(field.text.orEmpty())
    }.lowercase(Locale.ENGLISH)

/**
 * Lowercase blob containing only the developer-set name and id
 * attributes. For native fields, includes [FieldNode.idEntry]
 * (resource entry name). Useful for checks that should ignore
 * user-visible labels (e.g. detecting developer intent via
 * attribute naming).
 */
fun nameIdBlob(field: FieldNode): String =
    buildString {
        append(field.name.orEmpty())
        append(' ')
        append(field.attributes["id"].orEmpty())
        append(' ')
        append(field.idEntry.orEmpty())
    }.lowercase(Locale.ENGLISH)

/**
 * Lowercase blob containing the `autocomplete` HTML attribute
 * and any Android autofill hints declared on the field.
 */
fun autocompleteBlob(field: FieldNode): String =
    buildString {
        append(field.attributes["autocomplete"].orEmpty())
        append(' ')
        field.autofillHints.forEach {
            append(it)
            append(' ')
        }
    }.lowercase(Locale.ENGLISH)

/** Single-term specialization — avoids vararg array allocation. */
fun containsAny(
    text: String,
    term1: String,
): Boolean = text.contains(term1)

/** Two-term specialization — avoids vararg array allocation. */
fun containsAny(
    text: String,
    term1: String,
    term2: String,
): Boolean = text.contains(term1) || text.contains(term2)

/** Three-term specialization — avoids vararg array allocation. */
fun containsAny(
    text: String,
    term1: String,
    term2: String,
    term3: String,
): Boolean = text.contains(term1) || text.contains(term2) || text.contains(term3)

/**
 * Returns `true` if [text] contains any of the given [terms] as
 * a substring.
 */
fun containsAny(
    text: String,
    vararg terms: String,
): Boolean = terms.any { term -> text.contains(term) }

/**
 * [List] overload that avoids the `*list.toTypedArray()` spread-operator
 * allocation on every call site.
 */
fun containsAny(
    text: String,
    terms: List<String>,
): Boolean = terms.any { term -> text.contains(term) }

// ================================================================== //
//  Keyword constants — single sources of truth
//
//  Covers the top 21 languages by internet usage:
//    English, Chinese (Simplified + Traditional), Spanish, Arabic,
//    Portuguese, Russian, Japanese, German, French, Korean, Turkish,
//    Italian, Vietnamese, Dutch, Polish, Thai, Indonesian/Malay,
//    Hindi, Czech, Swedish, Ukrainian.
//
//  Each list includes common abbreviations and shortenings used in
//  real-world HTML attributes, labels, and placeholders.
// ================================================================== //

// ------------------------------------------------------------------ //
//  PASSWORD
// ------------------------------------------------------------------ //

/**
 * Password-related keywords for substring matching.
 * Includes common abbreviations and localized terms for 21 languages.
 */
val PASSWORD_KEYWORDS: List<String> =
    listOf(
        // English
        "password",
        "passcode",
        "passwd",
        "pass",
        "pwd",
        "psw",
        "pin",
        // Chinese (Simplified + Traditional)
        "密码",
        "密碼",
        "口令",
        // Spanish (prefix of contraseña)
        "contrase",
        "clave",
        // Arabic
        "كلمة المرور",
        "كلمة السر",
        "كلمه المرور",
        "رمز المرور",
        // Portuguese
        "senha",
        // Russian / Ukrainian
        "пароль",
        "парол",
        // Japanese
        "パスワード",
        "暗証番号",
        // German
        "passwort",
        "kennwort",
        // French
        "motdepasse",
        "mot de passe",
        // Korean
        "비밀번호",
        "암호",
        // Turkish
        "şifre",
        "sifre",
        "parola",
        // Vietnamese
        "mật khẩu",
        "mat khau",
        // Dutch
        "wachtwoord",
        // Polish (prefix of hasło)
        "hasło",
        "haslo",
        // Thai
        "รหัสผ่าน",
        // Indonesian / Malay
        "kata sandi",
        "sandi",
        "kata laluan",
        // Hindi
        "पासवर्ड",
        // Czech
        "heslo",
        // Swedish
        "lösenord",
        "losenord",
    )

/**
 * Word-boundary regex matching password-related tokens.
 * Handles diacritics via `.` wildcards (e.g. contraseña, hasło).
 * For CJK / Cyrillic / Arabic / Thai / Devanagari, word boundaries
 * are not useful so those scripts are matched as plain substrings
 * via [PASSWORD_KEYWORDS] instead. This regex targets Latin-script
 * tokens that appear in HTML name/id attributes.
 */
val PASSWORD_TOKEN_REGEX: Regex =
    Regex(
        "(^|[^a-z0-9])(password|passcode|passwd|pass|pwd|psw|pin" +
                "|passwort|kennwort" + // German
                "|senha" + // Portuguese
                "|contrase.a|clave" + // Spanish (contraseña)
                "|mot.de.passe|motdepasse" + // French
                "|wachtwoord" + // Dutch
                "|has.o|haslo|heslo" + // Polish (hasło) / Czech (heslo)
                "|l.senord|losenord" + // Swedish (lösenord)
                "|sifre|.ifre|parola" + // Turkish (şifre)
                "|m.t.kh.u|mat.khau" + // Vietnamese (mật khẩu)
                "|sandi|kata.sandi|kata.laluan" + // Indonesian / Malay
                ")([^a-z0-9]|$)",
    )

// ------------------------------------------------------------------ //
//  PHONE
// ------------------------------------------------------------------ //

/**
 * Phone-related keywords for substring matching.
 * Covers 21 languages.
 */
val PHONE_KEYWORDS: List<String> =
    listOf(
        // English
        "phone",
        "mobile",
        "telephone",
        "cell phone",
        "cellphone",
        "sms",
        "whatsapp",
        // Chinese (Simplified + Traditional)
        "电话",
        "手机",
        "電話",
        "手機",
        // Spanish / Italian
        "teléfono",
        "telefono",
        // Spanish / Portuguese
        "celular",
        "móvil",
        "movil",
        // Arabic
        "هاتف",
        "جوال",
        "موبايل",
        "رقم الهاتف",
        // Portuguese
        "telefone",
        // Russian / Ukrainian
        "телефон",
        "мобильный",
        "мобильн",
        // Japanese
        "電話番号",
        "携帯",
        "携帯電話",
        // German / Turkish / Polish / Czech / Swedish
        "telefonnummer",
        "telefon",
        // German / Swedish
        "mobilnummer",
        "handynummer",
        "handy",
        // French
        "téléphone",
        "portable",
        // Korean
        "전화",
        "전화번호",
        "휴대폰",
        "핸드폰",
        // Turkish
        "cep telefonu",
        // Italian
        "cellulare",
        // Vietnamese
        "điện thoại",
        "dien thoai",
        "số điện thoại",
        // Dutch
        "telefoon",
        "telefoonnummer",
        "mobiel",
        // Polish
        "numer telefonu",
        "komórka",
        "komorka",
        // Thai
        "โทรศัพท์",
        "มือถือ",
        "เบอร์โทร",
        // Indonesian / Malay
        "telepon",
        "ponsel",
        "nombor telefon",
        "no. hp",
        "no hp",
        "hp",
        // Hindi
        "फ़ोन",
        "फोन",
        "मोबाइल",
        // Czech / Swedish
        "mobil",
        // Ukrainian
        "мобільний",
    )

// ------------------------------------------------------------------ //
//  USERNAME
// ------------------------------------------------------------------ //

/**
 * Username / account-identifier keywords for substring matching.
 * Used when detecting fields that ask for a login identifier
 * (distinct from email or phone).
 * Covers 21 languages.
 */
val USERNAME_KEYWORDS: List<String> =
    listOf(
        // English
        "username",
        "user name",
        "user id",
        "userid",
        // "member" removed — too broad, triggers FPs on membership sites
        // (e.g. "Member since", "Membership type"). "member id" is kept.
        "member id",
        "account",
        "customer",
        "client",
        "cpf",
        "profile",
        // Chinese (Simplified + Traditional)
        "用户名",
        "用戶名",
        "账号",
        "帳號",
        // Spanish
        "usuario",
        "nombre de usuario",
        "cuenta",
        // Arabic
        "اسم المستخدم",
        "حساب",
        "معرف المستخدم",
        // Portuguese
        "usuário",
        "nome de usuário",
        // Russian
        "логин",
        "пользователь",
        "имя пользователя",
        "учетная запись",
        "учётная запись",
        // Japanese
        "ユーザー名",
        "ユーザ名",
        "アカウント",
        "ユーザーid",
        // German / Swedish
        "anmeldename",
        "benutzername",
        "nutzername",
        "konto",
        // French
        "identifiant",
        "nom d'utilisateur",
        "compte",
        // Korean
        "사용자",
        "아이디",
        "계정",
        "사용자명",
        // Turkish
        "kullanıcı adı",
        "kullanici adi",
        "kullanıcı",
        "kullanici",
        "hesap",
        // Italian
        "codice utente",
        "codiceutente",
        "utente",
        "nome utente",
        // Vietnamese
        "tên đăng nhập",
        "ten dang nhap",
        "tài khoản",
        "tai khoan",
        // Dutch
        "gebruikersnaam",
        "gebruiker",
        // Polish
        "nazwa użytkownika",
        "użytkownik",
        "uzytkownik",
        // Thai
        "ชื่อผู้ใช้",
        "บัญชี",
        // Indonesian / Malay
        "nama pengguna",
        "akun",
        "akaun",
        // Hindi
        "उपयोगकर्ता",
        "उपयोगकर्ता नाम",
        "खाता",
        // Czech
        "uživatel",
        "uživatelské jméno",
        "uzivatel",
        "účet",
        "ucet",
        // Swedish
        "användarnamn",
        "anvandarnamn",
        // Ukrainian
        "логін",
        "користувач",
        "обліковий запис",
    )

// ------------------------------------------------------------------ //
//  EMAIL
// ------------------------------------------------------------------ //

/**
 * Email-related keywords for substring matching.
 * Covers 21 languages.
 */
val EMAIL_KEYWORDS: List<String> =
    listOf(
        // English / Portuguese
        "email",
        "e-mail",
        // Chinese (Simplified + Traditional)
        "邮箱",
        "电子邮箱",
        "電子郵箱",
        "邮件",
        "電郵",
        // Spanish
        "correo",
        "correo electrónico",
        // Arabic
        "البريد الإلكتروني",
        "بريد إلكتروني",
        "ايميل",
        // Russian
        "почта",
        "эл. почта",
        "эл.почта",
        "электронная почта",
        // Japanese
        "メール",
        "メールアドレス",
        "eメール",
        // German
        "e-mail-adresse",
        // French
        "courriel",
        "adresse e-mail",
        // Korean
        "이메일",
        // Turkish
        "e-posta",
        "eposta",
        // Italian
        "posta elettronica",
        // Vietnamese
        "thư điện tử",
        "thu dien tu",
        // Dutch
        "e-mailadres",
        // Polish
        "adres e-mail",
        // Thai
        "อีเมล",
        // Indonesian / Malay
        "surel",
        "alamat email",
        // Hindi
        "ईमेल",
        // Czech
        "e-mailová adresa",
        "emailová adresa",
        // Swedish
        "e-postadress",
        "e-post",
        "epost",
        // Ukrainian
        "пошта",
        "ел. пошта",
        "ел.пошта",
        "електронна пошта",
    )

// ------------------------------------------------------------------ //
//  SEARCH
// ------------------------------------------------------------------ //

/**
 * Search-related keywords for substring matching.
 * Covers 21 languages.
 */
val SEARCH_KEYWORDS: List<String> =
    listOf(
        // English
        "search",
        "query",
        "keyword",
        "looking for",
        // Chinese (Simplified + Traditional)
        "搜索",
        "搜尋",
        "查找",
        "查询",
        // Spanish
        "buscar",
        "búsqueda",
        "busqueda",
        // Arabic
        "بحث",
        "ابحث",
        // Portuguese
        "pesquisar",
        "busca",
        "pesquisa",
        // Russian
        "поиск",
        "искать",
        "найти",
        // Japanese
        "検索",
        "探す",
        // German
        "suche",
        "suchen",
        // French
        "recherche",
        "rechercher",
        "chercher",
        // Korean
        "검색",
        "찾기",
        // Turkish
        "ara",
        "arama",
        // Italian
        "cerca",
        "ricerca",
        // Vietnamese
        "tìm kiếm",
        "tim kiem",
        // Dutch
        "zoeken",
        "zoek",
        // Polish
        "szukaj",
        "wyszukaj",
        "szukanie",
        // Thai
        "ค้นหา",
        // Indonesian / Malay
        "cari",
        "pencarian",
        // Hindi
        "खोज",
        "खोजें",
        // Czech
        "hledat",
        "vyhledat",
        // Swedish
        "sök",
        "söka",
        // Ukrainian
        "пошук",
        "шукати",
        "знайти",
    )

// ------------------------------------------------------------------ //
//  COMMENT / MESSAGE
// ------------------------------------------------------------------ //

/**
 * Comment / message / feedback keywords for substring matching.
 * Covers 21 languages.
 */
val COMMENT_KEYWORDS: List<String> =
    listOf(
        // English / French
        "comment",
        "message",
        "feedback",
        "review",
        "reply",
        // Chinese (Simplified + Traditional)
        "评论",
        "留言",
        "消息",
        "反馈",
        "評論",
        "訊息",
        // Spanish
        "comentario",
        "mensaje",
        "respuesta",
        // Arabic
        "تعليق",
        "رسالة",
        "ملاحظات",
        // Portuguese
        "comentário",
        "comentario",
        "mensagem",
        // Russian
        "комментарий",
        "сообщение",
        "отзыв",
        // Japanese
        "コメント",
        "メッセージ",
        // German
        "kommentar",
        "nachricht",
        "bewertung",
        // French
        "commentaire",
        "avis",
        // Korean
        "댓글",
        "메시지",
        "후기",
        // Turkish
        "yorum",
        "mesaj",
        // Italian
        "commento",
        "messaggio",
        "recensione",
        // Vietnamese
        "bình luận",
        "binh luan",
        "tin nhắn",
        "tin nhan",
        // Dutch
        "opmerking",
        "bericht",
        // Polish
        "komentarz",
        "wiadomość",
        "wiadomosc",
        // Thai
        "ความคิดเห็น",
        "ข้อความ",
        // Indonesian / Malay
        "komentar",
        "pesan",
        // Hindi
        "टिप्पणी",
        "संदेश",
        // Czech
        "komentář",
        "komentar",
        "zpráva",
        "zprava",
        // Swedish
        "kommentar",
        "meddelande",
        // Ukrainian
        "коментар",
        "повідомлення",
        "відгук",
    )

// ------------------------------------------------------------------ //
//  OTP / VERIFICATION CODE
// ------------------------------------------------------------------ //

/**
 * OTP / verification-code keywords for substring matching.
 * Covers 21 languages.
 */
val OTP_KEYWORDS: List<String> =
    listOf(
        // English
        "otp",
        "one-time code",
        "one-time password",
        "one time code",
        "verification code",
        "verify code",
        "auth code",
        "security code",
        "totp",
        "2fa",
        "mfa",
        // Chinese (Simplified + Traditional)
        "验证码",
        "驗證碼",
        "动态密码",
        "動態密碼",
        // Spanish
        "código de verificación",
        "codigo de verificacion",
        "código de seguridad",
        // Arabic
        "رمز التحقق",
        "رمز التأكيد",
        // Portuguese
        "código de verificação",
        "codigo de verificacao",
        // Russian
        "код подтверждения",
        "одноразовый код",
        "код верификации",
        "проверочный код",
        // Japanese
        "認証コード",
        "確認コード",
        "ワンタイムパスワード",
        // German
        "bestätigungscode",
        "bestatigungscode",
        "verifizierungscode",
        "einmalpasswort",
        // French
        "code de vérification",
        "code de verification",
        // Korean
        "인증번호",
        "인증 코드",
        "확인 코드",
        // Turkish
        "doğrulama kodu",
        "dogrulama kodu",
        "onay kodu",
        // Italian
        "codice di verifica",
        "codice otp",
        // Vietnamese
        "mã xác minh",
        "ma xac minh",
        "mã xác nhận",
        "ma xac nhan",
        // Dutch
        "verificatiecode",
        "eenmalig wachtwoord",
        // Polish
        "kod weryfikacyjny",
        "kod jednorazowy",
        // Thai
        "รหัสยืนยัน",
        "รหัส otp",
        // Indonesian / Malay
        "kode verifikasi",
        "kode otp",
        // Hindi
        "सत्यापन कोड",
        "ओटीपी",
        // Czech
        "ověřovací kód",
        "overovaci kod",
        "jednorázový kód",
        // Swedish
        "verifieringskod",
        "engångskod",
        "engangskod",
        // Ukrainian
        "код підтвердження",
        "одноразовий код",
        "код верифікації",
    )

// ------------------------------------------------------------------ //
//  LOGIN BUTTON / ACTION
// ------------------------------------------------------------------ //

/**
 * Keywords found on login buttons and form actions.
 * Covers 21 languages.
 */
val LOGIN_BUTTON_KEYWORDS: List<String> =
    listOf(
        // English
        "login",
        "log in",
        "sign in",
        "signin",
        "log-in",
        "sign-in",
        // Chinese (Simplified + Traditional)
        "登录",
        "登錄",
        "登入",
        // Spanish / Portuguese
        "iniciar sesión",
        "iniciar sesion",
        "entrar",
        "acceder",
        // Arabic
        "تسجيل الدخول",
        "دخول",
        // Portuguese
        "fazer login",
        "acessar",
        // Russian
        "войти",
        "вход",
        // Japanese
        "ログイン",
        "サインイン",
        // German
        "anmelden",
        "einloggen",
        // French
        "se connecter",
        "connexion",
        // Korean
        "로그인",
        "로그 인",
        // Turkish
        "giriş",
        "giris",
        "giriş yap",
        "oturum aç",
        "oturum ac",
        // Italian
        "accedi",
        "accesso",
        // Vietnamese
        "đăng nhập",
        "dang nhap",
        // Dutch
        "inloggen",
        "aanmelden",
        // Polish
        "zaloguj",
        "zaloguj się",
        "zaloguj sie",
        // Thai
        "เข้าสู่ระบบ",
        // Indonesian / Malay
        "masuk",
        "log masuk",
        // Hindi
        "लॉग इन",
        "लॉगिन",
        "साइन इन",
        // Czech
        "přihlásit",
        "prihlasit",
        "přihlášení",
        // Swedish
        "logga in",
        // Ukrainian
        "увійти",
        "вхід",
    )

// ------------------------------------------------------------------ //
//  SIGNUP BUTTON / ACTION
// ------------------------------------------------------------------ //

/**
 * Keywords found on sign-up / registration buttons and form actions.
 * Covers 21 languages.
 */
val SIGNUP_BUTTON_KEYWORDS: List<String> =
    listOf(
        // English
        "sign up",
        "signup",
        "sign-up",
        "register",
        "create account",
        "create an account",
        "join",
        // Chinese (Simplified + Traditional)
        "注册",
        "註冊",
        "创建账号",
        "創建帳號",
        // Spanish / Portuguese
        "registrarse",
        "registrar",
        "crear cuenta",
        // Arabic
        "إنشاء حساب",
        "تسجيل",
        "سجل",
        // Portuguese
        "cadastrar",
        "criar conta",
        "cadastre-se",
        // Russian
        "зарегистрироваться",
        "регистрация",
        "создать аккаунт",
        // Japanese
        "登録",
        "アカウント作成",
        "新規登録",
        "サインアップ",
        // German
        "registrieren",
        "konto erstellen",
        "anmeldung",
        // French
        "s'inscrire",
        "inscription",
        "créer un compte",
        "creer un compte",
        // Korean
        "회원가입",
        "회원 가입",
        "가입",
        // Turkish
        "kayıt ol",
        "kayit ol",
        "kaydol",
        "hesap oluştur",
        "hesap olustur",
        // Italian
        "registrati",
        "crea account",
        "iscriviti",
        // Vietnamese
        "đăng ký",
        "dang ky",
        "tạo tài khoản",
        // Dutch
        "registreren",
        "account aanmaken",
        // Polish
        "zarejestruj",
        "zarejestruj się",
        "zarejestruj sie",
        "rejestracja",
        // Thai
        "สมัครสมาชิก",
        "ลงทะเบียน",
        // Indonesian / Malay
        "daftar",
        "buat akun",
        "buat akaun",
        // Hindi
        "पंजीकरण",
        "खाता बनाएं",
        "साइन अप",
        // Czech
        "registrovat",
        "vytvořit účet",
        "vytvorit ucet",
        // Swedish
        "registrera",
        "skapa konto",
        // Ukrainian
        "зареєструватися",
        "реєстрація",
        "створити обліковий запис",
    )

// ------------------------------------------------------------------ //
//  RESET / FORGOT PASSWORD
// ------------------------------------------------------------------ //

/**
 * Keywords found on password-reset / recovery UI elements.
 * Covers 21 languages.
 */
val RESET_KEYWORDS: List<String> =
    listOf(
        // English
        "forgot",
        "reset",
        "recover",
        "lost password",
        "trouble signing",
        // Chinese (Simplified + Traditional)
        "忘记密码",
        "忘記密碼",
        "重置密码",
        "重設密碼",
        "找回密码",
        // Spanish
        "olvidé",
        "olvide",
        "olvidé mi contraseña",
        "recuperar",
        "restablecer",
        // Arabic
        "نسيت كلمة المرور",
        "استعادة",
        "إعادة تعيين",
        // Portuguese
        "esqueci",
        "esqueci minha senha",
        "recuperar senha",
        "redefinir",
        // Russian
        "забыл пароль",
        "забыли пароль",
        "восстановить",
        "сбросить пароль",
        // Japanese
        "パスワードを忘れた",
        "パスワードリセット",
        "パスワード再設定",
        // German
        "passwort vergessen",
        "zurücksetzen",
        "zurucksetzen",
        // French
        "mot de passe oublié",
        "mot de passe oublie",
        "réinitialiser",
        "reinitialiser",
        // Korean
        "비밀번호 찾기",
        "비밀번호를 잊으셨나요",
        "비밀번호 재설정",
        // Turkish
        "şifremi unuttum",
        "sifremi unuttum",
        "şifre sıfırla",
        // Italian
        "password dimenticata",
        "reimpostare",
        "recupera password",
        // Vietnamese
        "quên mật khẩu",
        "quen mat khau",
        "đặt lại mật khẩu",
        // Dutch
        "wachtwoord vergeten",
        "herstel",
        // Polish
        "zapomniałem hasła",
        "zapomnialem hasla",
        "resetuj hasło",
        "resetuj haslo",
        // Thai
        "ลืมรหัสผ่าน",
        // Indonesian / Malay
        "lupa sandi",
        "lupa kata sandi",
        "atur ulang",
        // Hindi
        "पासवर्ड भूल गए",
        // Czech
        "zapomenuté heslo",
        "zapomenute heslo",
        "obnovit heslo",
        // Swedish
        "glömt lösenord",
        "glomt losenord",
        "återställ",
        "aterstall",
        // Ukrainian
        "забув пароль",
        "забули пароль",
        "відновити",
        "скинути пароль",
    )

// ------------------------------------------------------------------ //
//  NAME (PERSON)
// ------------------------------------------------------------------ //

/**
 * Person-name-related keywords for substring matching.
 * Covers 21 languages. Includes first/last/full name variants.
 */
val NAME_KEYWORDS: List<String> =
    listOf(
        // English / German
        "first name",
        "last name",
        "full name",
        "given name",
        "family name",
        "surname",
        "name",
        // Chinese (Simplified + Traditional) / Japanese
        "姓名",
        "名字",
        "名前",
        "姓",
        // Spanish
        "nombre",
        "apellido",
        "nombre completo",
        // Arabic
        "الاسم",
        "الاسم الأول",
        "اسم العائلة",
        "الاسم الكامل",
        // Portuguese / Italian
        "nome",
        "sobrenome",
        "nome completo",
        // Russian
        "имя",
        "фамилия",
        "полное имя",
        "фио",
        // Japanese
        "氏名",
        "名",
        // German
        "vorname",
        "nachname",
        "vollständiger name",
        // French
        "prénom",
        "prenom",
        "nom",
        "nom complet",
        "nom de famille",
        // Korean
        "이름",
        "성명",
        "성",
        // Turkish
        "ad",
        "soyad",
        "isim",
        "ad soyad",
        // Italian
        "cognome",
        // Vietnamese
        "họ tên",
        "ho ten",
        "tên",
        "họ",
        // Dutch
        "voornaam",
        "achternaam",
        "naam",
        // Polish
        "imię",
        "imie",
        "nazwisko",
        // Thai
        "ชื่อ",
        "นามสกุล",
        "ชื่อ-นามสกุล",
        // Indonesian / Malay
        "nama",
        "nama lengkap",
        "nama depan",
        // Hindi
        "नाम",
        "पूरा नाम",
        // Czech
        "jméno",
        "jmeno",
        "příjmení",
        "prijmeni",
        // Swedish
        "förnamn",
        "fornamn",
        "efternamn",
        // Ukrainian
        "ім'я",
        "прізвище",
        "повне ім'я",
    )

/**
 * Given/first name keywords — subset of [NAME_KEYWORDS].
 */
val GIVEN_NAME_KEYWORDS: List<String> =
    listOf(
        "first name",
        "given name",
        "prénom",
        "prenom",
        "vorname",
        "nombre",
        "voornaam",
        "imię",
        "imie",
        "名",
        "名前",
        "이름",
        "ad",
        "isim",
        "tên",
        "ชื่อ",
        "名字",
        "الاسم الأول",
        "имя",
        "ім'я",
        "förnamn",
        "fornamn",
        "jméno",
        "jmeno",
        "nama depan",
    )

/**
 * Family/last name keywords — subset of [NAME_KEYWORDS].
 */
val FAMILY_NAME_KEYWORDS: List<String> =
    listOf(
        "last name",
        "family name",
        "surname",
        "nom de famille",
        "nom",
        "nachname",
        "apellido",
        "cognome",
        "achternaam",
        "nazwisko",
        "sobrenome",
        "soyad",
        "姓",
        "성",
        "اسم العائلة",
        "фамилия",
        "прізвище",
        "นามสกุล",
        "họ",
        "efternamn",
        "příjmení",
        "prijmeni",
    )

// ------------------------------------------------------------------ //
//  ADDRESS
// ------------------------------------------------------------------ //

/**
 * Street / mailing address keywords for substring matching.
 * Covers 21 languages.
 */
val ADDRESS_KEYWORDS: List<String> =
    listOf(
        // English
        "street address",
        "address line",
        "shipping address",
        "billing address",
        "mailing address",
        "address",
        // Chinese (Simplified + Traditional)
        "地址",
        "详细地址",
        "街道",
        // Spanish
        "dirección",
        "direccion",
        // Arabic
        "العنوان",
        "عنوان",
        // Portuguese
        "endereço",
        "endereco",
        // Russian
        "адрес",
        // Japanese
        "住所",
        // German / French
        "adresse",
        "straße",
        "strasse",
        // Korean
        "주소",
        // Turkish / Dutch / Polish
        "adres",
        // Italian
        "indirizzo",
        // Vietnamese
        "địa chỉ",
        "dia chi",
        // Thai
        "ที่อยู่",
        // Indonesian / Malay
        "alamat",
        // Hindi
        "पता",
        // Czech
        "adresa",
        // Swedish
        "adress",
        // Ukrainian
        "адреса",
    )

/**
 * City / locality keywords for substring matching.
 */
val CITY_KEYWORDS: List<String> =
    listOf(
        "city",
        "locality",
        "ciudad",
        "ville",
        "stadt",
        "città",
        "citta",
        "stad",
        "miasto",
        "город",
        "місто",
        "都市",
        "市",
        "도시",
        "城市",
        "şehir",
        "sehir",
        "thành phố",
        "thanh pho",
        "kota",
        "เมือง",
        "město",
        "mesto",
        "المدينة",
        "शहर",
    )

/**
 * State / province / region keywords for substring matching.
 */
val REGION_KEYWORDS: List<String> =
    listOf(
        "state",
        "province",
        "region",
        "estado",
        "provincia",
        "região",
        "regiao",
        "область",
        "край",
        "県",
        "도",
        "省",
        "il",
        "tỉnh",
        "จังหวัด",
    )

/**
 * Country keywords for substring matching.
 */
val COUNTRY_KEYWORDS: List<String> =
    listOf(
        "country",
        "país",
        "pais",
        "pays",
        "land",
        "paese",
        "страна",
        "країна",
        "国",
        "나라",
        "ülke",
        "ulke",
        "quốc gia",
        "negara",
        "ประเทศ",
        "البلد",
        "देश",
    )

/**
 * Postal / ZIP code keywords for substring matching.
 */
val POSTAL_CODE_KEYWORDS: List<String> =
    listOf(
        "postal code",
        "zip code",
        "zipcode",
        "zip",
        "postcode",
        "código postal",
        "codigo postal",
        "code postal",
        "postleitzahl",
        "plz",
        "cap",
        "cep",
        "почтовый индекс",
        "поштовий індекс",
        "郵便番号",
        "우편번호",
        "邮编",
        "posta kodu",
        "mã bưu chính",
        "ma buu chinh",
        "kode pos",
        "รหัสไปรษณีย์",
        "psč",
        "psc",
        "postnummer",
    )

// ------------------------------------------------------------------ //
//  CREDIT CARD
// ------------------------------------------------------------------ //

/**
 * Credit/debit card number keywords for substring matching.
 */
val CREDIT_CARD_NUMBER_KEYWORDS: List<String> =
    listOf(
        "card number",
        "credit card",
        "debit card",
        "card no",
        "número de tarjeta",
        "numero de tarjeta",
        "numéro de carte",
        "numero de carte",
        "kartennummer",
        "numero carta",
        "卡号",
        "カード番号",
        "카드번호",
        "номер карты",
        "номер картки",
        "رقم البطاقة",
        "số thẻ",
        "so the",
        "nomor kartu",
        "หมายเลขบัตร",
        "číslo karty",
        "cislo karty",
        "kortnummer",
    )

/**
 * Card security code (CVV/CVC) keywords for substring matching.
 */
val CARD_SECURITY_CODE_KEYWORDS: List<String> =
    listOf(
        "cvv",
        "cvc",
        "security code",
        "card verification",
        "código de seguridad",
        "codigo de seguridad",
        "code de sécurité",
        "code de securite",
        "sicherheitscode",
        "codice di sicurezza",
        "安全码",
        "セキュリティコード",
        "보안코드",
        "код безопасности",
    )

/**
 * Card expiration keywords for substring matching.
 */
val CARD_EXPIRY_KEYWORDS: List<String> =
    listOf(
        "expiration",
        "expiry",
        "exp date",
        "expdate",
        "exp.",
        "valid thru",
        "valid until",
        "fecha de vencimiento",
        "date d'expiration",
        "gültig bis",
        "gultig bis",
        "ablaufdatum",
        "scadenza",
        "有效期",
        "有効期限",
        "만료일",
        "срок действия",
        "son kullanma",
        "ngày hết hạn",
        "berlaku hingga",
        "วันหมดอายุ",
    )

// ------------------------------------------------------------------ //
//  Regex constants — developer-facing name/id attribute matching
// ------------------------------------------------------------------ //

/**
 * Word-boundary regex for explicit email patterns in name/id
 * attributes (e.g. `name="email"`, `id="emailAddress"`).
 * Extended with common non-ASCII email field names used in
 * localized HTML.
 */
val EXPLICIT_EMAIL_NAME_ID_REGEX: Regex =
    Regex(
        "(^|[^a-z0-9])(email|e-mail|emailaddress|email_address" +
                "|correo|courriel|e.posta|eposta" + // Spanish / French / Turkish
                ")([^a-z0-9]|$)",
    )

/**
 * Regex matching name/id attributes that contain login-related
 * keywords (e.g. `id="loginField"`, `name="signinUser"`).
 * Extended with transliterated login tokens from multiple languages.
 */
val NAME_ID_LOGIN_REGEX: Regex =
    Regex(
        "(login|logon|signin|user|usr|account|member" +
                "|usuario" + // Spanish / Portuguese
                "|connexion|identifiant" + // French
                "|anmelden|einloggen" + // German
                "|inloggen" + // Dutch
                "|zaloguj" + // Polish
                "|giris|giri." + // Turkish (giriş)
                "|masuk" + // Indonesian / Malay
                "|prihlasit|p.ihl.sit" + // Czech (přihlásit)
                "|logga" + // Swedish
                ")",
    )

/**
 * Regex matching name/id attributes that contain a login-related
 * identifier token with word boundaries.
 */
val LOGIN_IDENTIFIER_REGEX: Regex =
    Regex(
        "(^|[^a-z0-9])(" +
                "[a-z0-9_-]*login[a-z0-9_-]*" +
                "|[a-z0-9_-]*signin[a-z0-9_-]*" +
                "|[a-z0-9_-]*logon[a-z0-9_-]*" +
                "|[a-z0-9_-]*loggin[a-z0-9_-]*" + // common misspelling
                "|[a-z0-9_-]*connexion[a-z0-9_-]*" + // French
                "|[a-z0-9_-]*iniciar[a-z0-9_-]*" + // Spanish
                "|[a-z0-9_-]*masuk[a-z0-9_-]*" + // Indonesian
                "|[a-z0-9_-]*giris[a-z0-9_-]*" + // Turkish
                "|[a-z0-9_-]*einlog[a-z0-9_-]*" + // German
                "|[a-z0-9_-]*inlog[a-z0-9_-]*" + // Dutch
                "|[a-z0-9_-]*zaloguj[a-z0-9_-]*" + // Polish
                "|[a-z0-9_-]*prihlasit[a-z0-9_-]*" + // Czech
                ")([^a-z0-9]|$)",
    )

/**
 * Regex matching name/id attributes with a user-prefixed identifier
 * token, excluding fields that also mention email/phone/mobile.
 */
val USER_IDENTIFIER_REGEX: Regex =
    Regex("(^|[^a-z0-9])(user[a-z0-9_-]{1,}|usr[a-z0-9_-]{1,})([^a-z0-9]|$)")

// ------------------------------------------------------------------ //
//  Tel false-positive suppression
// ------------------------------------------------------------------ //

/**
 * Terms that indicate a `type=tel` field is NOT a phone-number
 * credential (e.g. ZIP code, SSN, credit-card CVV, freight calc).
 * Covers 21 languages.
 */
val TEL_FALSE_POSITIVE_KEYWORDS: List<String> =
    listOf(
        // English
        "zip",
        "postal",
        "ssn",
        "social security",
        "card",
        "cvv",
        "cvc",
        "expir",
        "quantity",
        "qty",
        "price",
        "amount",
        "total",
        // Portuguese
        "cep",
        "frete",
        "calcular",
        // Spanish
        "código postal",
        "codigo postal",
        "cantidad",
        "precio",
        // French
        "code postal",
        "quantité",
        "quantite",
        "prix",
        "montant",
        // German
        "postleitzahl",
        "plz",
        "menge",
        "preis",
        "betrag",
        // Russian
        "почтовый индекс",
        "количество",
        "цена",
        "сумма",
        // Chinese / Japanese
        "邮编",
        "数量",
        "价格",
        "金额",
        // Japanese
        "郵便番号",
        "価格",
        // Korean
        "우편번호",
        "수량",
        "가격",
    )

// ------------------------------------------------------------------ //
//  STRONG USERNAME (subset of USERNAME_KEYWORDS)
// ------------------------------------------------------------------ //

/**
 * Strong username keywords — explicit "username"/"userid" terms
 * across 21 languages. Subset of [USERNAME_KEYWORDS] excluding
 * weak/generic terms like "account", "customer", "client" that
 * cause false positives when used as override evidence.
 */
val STRONG_USERNAME_KEYWORDS: List<String> =
    listOf(
        // English
        "username",
        "user name",
        "user id",
        "userid",
        // Chinese
        "用户名",
        "用戶名",
        // Spanish / Portuguese
        "nombre de usuario",
        "nome de usuário",
        "usuario",
        "usuário",
        // Arabic
        "اسم المستخدم",
        // Russian
        "имя пользователя",
        "логин",
        // Japanese
        "ユーザー名",
        "ユーザ名",
        // German
        "benutzername",
        "anmeldename",
        "nutzername",
        // French
        "nom d'utilisateur",
        "identifiant",
        // Korean
        "사용자명",
        "아이디",
        // Turkish
        "kullanıcı adı",
        "kullanici adi",
        // Italian
        "nome utente",
        "codice utente",
        // Vietnamese
        "tên đăng nhập",
        "ten dang nhap",
        // Dutch
        "gebruikersnaam",
        // Polish
        "nazwa użytkownika",
        // Thai
        "ชื่อผู้ใช้",
        // Indonesian / Malay
        "nama pengguna",
        // Hindi
        "उपयोगकर्ता नाम",
        // Czech
        "uživatelské jméno",
        // Swedish
        "användarnamn",
        "anvandarnamn",
        // Ukrainian
        "логін",
        "користувач",
    )
