// The MIT License (MIT)
//
// Copyright (c) 2015 Ben Drucker
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.DrawableResource

// Thanks to
// https://github.com/bendrucker/creditcards-types/
// for providing these beautiful regex patterns.
//
// Currently based on
// https://github.com/bendrucker/creditcards-types/commit/d3d1d11ea33afd97f56f3862c84adae4000ca032
//
// Images of cards
// https://github.com/aaronfagan/svg-credit-card-payment-icons

data class CreditCardType(
    val name: String,
    val icon: DrawableResource? = null,
    val digits: IntRange = 16..16,
    val csc: IntRange = 3..3,
    /**
     * `true` if the credit card number uses the Luhn algorithm,
     * `false` otherwise.
     *
     * See: https://en.wikipedia.org/wiki/Luhn_algorithm
     */
    val luhn: Boolean = true,
    /** A regular expression for validating a full card number */
    val pattern: Regex,
    /** A regular expression for guessing the card type from a partial number */
    val eagerPattern: Regex,
    /**
     * A regular expression for separating the card number into groups. Can be
     * used for formatting purposes.
     */
    val groupPattern: Regex? = null,
)

val creditCardVisa = CreditCardType(
    name = "Visa",
    icon = Res.drawable.ic_card_visa,
    digits = 13..19,
    pattern = "^4\\d{12}(\\d{3}|\\d{6})?\$".toRegex(),
    eagerPattern = "^4".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?(\\d{1,3})?".toRegex(),
)

val creditCardAmericanExpress = CreditCardType(
    name = "American Express",
    icon = Res.drawable.ic_card_amex,
    digits = 15..15,
    csc = 4..4,
    pattern = "^3[47]\\d{13}\$".toRegex(),
    eagerPattern = "^3[47]".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,6})?(\\d{1,5})?".toRegex(),
)

val creditCardDankort = CreditCardType(
    name = "Dankort",
    pattern = "^5019\\d{12}\$".toRegex(),
    eagerPattern = "^5019".toRegex(),
)

val creditCardDinersClub = CreditCardType(
    name = "Diners Club",
    icon = Res.drawable.ic_card_diners,
    digits = 14..19,
    pattern = "^3(0[0-5]|[68]\\d)\\d{11,16}\$".toRegex(),
    eagerPattern = "^3(0|[68])".toRegex(),
    groupPattern = "(\\d{1,4})?(\\d{1,6})?(\\d{1,9})?".toRegex(),
)

val creditCardDiscover = CreditCardType(
    name = "Discover",
    icon = Res.drawable.ic_card_discover,
    pattern = "^6(011(0[0-9]|[2-4]\\d|74|7[7-9]|8[6-9]|9[0-9])|4[4-9]\\d{3}|5\\d{4})\\d{10}\$".toRegex(),
    eagerPattern = "^6(011(0[0-9]|[2-4]|74|7[7-9]|8[6-9]|9[0-9])|4[4-9]|5)".toRegex(),
)

val creditCardElo = CreditCardType(
    name = "Elo",
    icon = Res.drawable.ic_card_elo,
    pattern = "^(4[035]|5[0]|6[235])(6[7263]|9[90]|1[2416]|7[736]|8[9]|0[04579]|5[0])([0-9])([0-9])\\d{10}\$".toRegex(),
    eagerPattern = "^(4[035]|5[0]|6[235])(6[7263]|9[90]|1[2416]|7[736]|8[9]|0[04579]|5[0])([0-9])([0-9])".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?(\\d{1,3})?".toRegex(),
)

val creditCardForbrugsforeningen = CreditCardType(
    name = "Forbrugsforeningen",
    pattern = "^600722\\d{10}\$".toRegex(),
    eagerPattern = "^600".toRegex(),
)

val creditCardJCB = CreditCardType(
    name = "JCB",
    icon = Res.drawable.ic_card_jcb,
    pattern = "^35\\d{14}\$".toRegex(),
    eagerPattern = "^35".toRegex(),
)

val creditCardMada = CreditCardType(
    name = "Mada",
    digits = 16..16,
    pattern = "^(4(0(0861|1757|3024|6136|6996|7(197|395)|9201)|1(2565|0621|0685|7633|9593)|2(0132|1141|281(7|8|9)|689700|8(331|67(1|2|3)))|3(1361|2328|4107|9954)|4(0(533|647|795)|5564|6(393|404|672))|5(5(036|708)|7865|7997|8456)|6(2220|854(0|1|2|3))|7(4491)|8(301(0|1|2)|4783|609(4|5|6)|931(7|8|9))|93428)|5(0(4300|6968|8160)|13213|2(0058|1076|4(130|514)|9(415|741))|3(0(060|906)|1(095|196)|2013|5(825|989)|6023|7767|9931)|4(3(085|357)|9760)|5(4180|7606|8563|8848)|8(5265|8(8(4(5|6|7|8|9)|5(0|1))|98(2|3))|9(005|206)))|6(0(4906|5141)|36120)|9682(0(1|2|3|4|5|6|7|8|9)|1(0|1)))\\d{10}\$".toRegex(),
    eagerPattern = "^(4(0(0861|1757|3024|6136|6996|7(197|395)|9201)|1(2565|0621|0685|7633|9593)|2(0132|1141|281(7|8|9)|689700|8(331|67(1|2|3)))|3(1361|2328|4107|9954)|4(0(533|647|795)|5564|6(393|404|672))|5(5(036|708)|7865|7997|8456)|6(2220|854(0|1|2|3))|7(4491)|8(301(0|1|2)|4783|609(4|5|6)|931(7|8|9))|93428)|5(0(4300|6968|8160)|13213|2(0058|1076|4(130|514)|9(415|741))|3(0(060|906)|1(095|196)|2013|5(825|989)|6023|7767|9931)|4(3(085|357)|9760)|5(4180|7606|8563|8848)|8(5265|8(8(4(5|6|7|8|9)|5(0|1))|98(2|3))|9(005|206)))|6(0(4906|5141)|36120)|9682(0(1|2|3|4|5|6|7|8|9)|1(0|1)))".toRegex(),
)

val creditCardMaestro = CreditCardType(
    name = "Maestro",
    icon = Res.drawable.ic_card_maestro,
    digits = 12..19,
    pattern = "^(5018|5020|5038|5893|6304|6759|6761|6762|6763)\\d{8,15}\$".toRegex(),
    eagerPattern = "^(5(018|0[23]|[68])|6[37]|60111|60115|60117([56]|7[56])|60118[0-5]|64[0-3]|66)".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?(\\d{1,3})?".toRegex(),
)

val creditCardMastercard = CreditCardType(
    name = "Mastercard",
    icon = Res.drawable.ic_card_mastercard,
    pattern = "^(5[1-5][0-9]{2}|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720)\\d{12}\$".toRegex(),
    eagerPattern = "^(2[3-7]|22[2-9]|5[1-5])".toRegex(),
)

val creditCardMeeza = CreditCardType(
    name = "Meeza",
    digits = 16..16,
    pattern = "^5078(03|08|09|10)\\d{10}\$".toRegex(),
    eagerPattern = "^5078(03|08|09|10)".toRegex(),
)

val creditCardMir = CreditCardType(
    name = "Mir",
    icon = Res.drawable.ic_card_mir,
    pattern = "^220[0-4]\\d{12}\$".toRegex(),
    eagerPattern = "^220[0-4]".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?(\\d{1,3})?".toRegex(),
)

val creditCardTroy = CreditCardType(
    name = "Troy",
    pattern = "^9792\\d{12}\$".toRegex(),
    eagerPattern = "^9792".toRegex(),
)

val creditCardUATP = CreditCardType(
    name = "UATP",
    digits = 15..15,
    pattern = "^1\\d{14}\$".toRegex(),
    eagerPattern = "^1".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,5})?(\\d{1,6})?".toRegex(),
)

val creditCardUnionPay = CreditCardType(
    name = "UnionPay",
    icon = Res.drawable.ic_card_unionpay,
    luhn = false,
    pattern = "^62[0-5]\\d{13,16}\$".toRegex(),
    eagerPattern = "^62".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?(\\d{1,3})?".toRegex(),
)

val creditCardRupay = CreditCardType(
    name = "RuPay",
    icon = Res.drawable.ic_card_rupay,
    pattern = "^(60\\d|65\\d|81\\d|82\\d|508|353|356)\\d{13}\$".toRegex(),
    eagerPattern = "^(60|65|81|82|508|353|356)".toRegex(),
    groupPattern = "(\\d{1,4})(\\d{1,4})?(\\d{1,4})?(\\d{1,4})?".toRegex(),
)

// Sorted by popularity
val creditCards = persistentListOf(
    creditCardVisa,
    creditCardMastercard,
    creditCardAmericanExpress,
    creditCardDinersClub,
    creditCardDiscover,
    creditCardJCB,
    creditCardUnionPay,
    creditCardMaestro,
    creditCardForbrugsforeningen,
    creditCardDankort,
    creditCardTroy,
    creditCardElo,
    creditCardMir,
    creditCardUATP,
    creditCardMada,
    creditCardMeeza,
    creditCardRupay,
)

fun List<CreditCardType>.firstOrNullByBrand(
    brand: String,
) = this
    .firstOrNull {
        it.name.equals(brand, ignoreCase = true)
    }

fun List<CreditCardType>.firstOrNullByNumber(
    number: String,
) = kotlin.run {
    val digitOnlyNumber = number.replace(
        regex = "\\D".toRegex(),
        replacement = "",
    )
    this
        .firstOrNull {
            it.pattern.matches(digitOnlyNumber)
        }
}
