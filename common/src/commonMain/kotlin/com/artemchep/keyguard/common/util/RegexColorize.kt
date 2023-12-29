package com.artemchep.keyguard.common.util

// A rewrite of
// https://github.com/geongeorge/regex-colorizer/
// using Kotlin.


private val myclass = "regex"
private val regexToken =
    """\[\^?]?(?:[^\\\]]+|\\[\S\s]?)*]?|\\(?:0(?:[0-3][0-7]{0,2}|[4-7][0-7]?)?|[1-9][0-9]*|x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4}|c[A-Za-z]|[\S\s]?)|\((?:\?[:=!]?)?|(?:[?*+]|\{[0-9]+(?:,[0-9]*)?\})\??|[^.?*+^${"$"}{[()|\\]+|."""
private val charClassToken =
    """/[^\\-]+|-|\\(?:[0-3][0-7]{0,2}|[4-7][0-7]?|x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4}|c[A-Za-z]|[\S\s]?)"""
private val charClassParts = """^(\[\^?)(]?(?:[^\\\]]+|\\[\S\s]?)*)(]?)$"""
private val quantifier = """^(?:[?*+]|\{[0-9]+(?:,[0-9]*)?\})\??$"""
private val type_NONE = 0
private val type_RANGE_HYPHEN = 1
private val type_SHORT_CLASS = 2
private val type_ALTERNATOR = 3
private val error_UNCLOSED_CLASS = "Unclosed character class"
private val error_INCOMPLETE_TOKEN = "Incomplete regex token"
private val error_INVALID_RANGE = "Reversed or invalid range"
private val error_INVALID_GROUP_TYPE = "Invalid or unsupported group type"
private val error_UNBALANCED_LEFT_PAREN = "Unclosed grouping"
private val error_UNBALANCED_RIGHT_PAREN = "No matching opening parenthesis"
private val error_INTERVAL_OVERFLOW = "Interval quantifier cannot use value over 65,535"
private val error_INTERVAL_REVERSED = "Interval quantifier range is reversed"
private val error_UNQUANTIFIABLE = "Quantifiers must be preceded by a token that can be repeated"
private val error_IMPROPER_EMPTY_ALTERNATIVE =
    "Empty alternative effectively truncates the regex here"


