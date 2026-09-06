//! Stable wire codes for upstream's verbal feedback.
//!
//! Both mappings are exhaustive `match`es over plain upstream enums, so a new
//! variant in a future `zxcvbn` release is a compile error here rather than a
//! silently dropped warning. The numeric codes are the contract: if upstream
//! ever reorders its declarations, the arms move and the codes stay.

use zxcvbn::feedback::{Suggestion, Warning};

/// Wire value of "no warning applies".
pub const WARNING_NONE: i32 = -1;

/// Returns the stable wire code of a warning.
#[must_use]
pub const fn warning_code(warning: Warning) -> i32 {
    match warning {
        Warning::StraightRowsOfKeysAreEasyToGuess => 0,
        Warning::ShortKeyboardPatternsAreEasyToGuess => 1,
        Warning::RepeatsLikeAaaAreEasyToGuess => 2,
        Warning::RepeatsLikeAbcAbcAreOnlySlightlyHarderToGuess => 3,
        Warning::ThisIsATop10Password => 4,
        Warning::ThisIsATop100Password => 5,
        Warning::ThisIsACommonPassword => 6,
        Warning::ThisIsSimilarToACommonlyUsedPassword => 7,
        Warning::SequencesLikeAbcAreEasyToGuess => 8,
        Warning::RecentYearsAreEasyToGuess => 9,
        Warning::AWordByItselfIsEasyToGuess => 10,
        Warning::DatesAreOftenEasyToGuess => 11,
        Warning::NamesAndSurnamesByThemselvesAreEasyToGuess => 12,
        Warning::CommonNamesAndSurnamesAreEasyToGuess => 13,
    }
}

/// Returns the stable single-bit mask of a suggestion.
#[must_use]
pub const fn suggestion_bit(suggestion: Suggestion) -> u32 {
    match suggestion {
        Suggestion::UseAFewWordsAvoidCommonPhrases => 1 << 0,
        Suggestion::NoNeedForSymbolsDigitsOrUppercaseLetters => 1 << 1,
        Suggestion::AddAnotherWordOrTwo => 1 << 2,
        Suggestion::CapitalizationDoesntHelpVeryMuch => 1 << 3,
        Suggestion::AllUppercaseIsAlmostAsEasyToGuessAsAllLowercase => 1 << 4,
        Suggestion::ReversedWordsArentMuchHarderToGuess => 1 << 5,
        Suggestion::PredictableSubstitutionsDontHelpVeryMuch => 1 << 6,
        Suggestion::UseALongerKeyboardPatternWithMoreTurns => 1 << 7,
        Suggestion::AvoidRepeatedWordsAndCharacters => 1 << 8,
        Suggestion::AvoidSequences => 1 << 9,
        Suggestion::AvoidRecentYears => 1 << 10,
        Suggestion::AvoidYearsThatAreAssociatedWithYou => 1 << 11,
        Suggestion::AvoidDatesAndYearsThatAreAssociatedWithYou => 1 << 12,
    }
}

/// Mask of every suggestion bit defined by ABI v1.
pub const SUGGESTION_MASK_ALL: u32 = 0x1fff;

#[cfg(test)]
pub(crate) const ALL_WARNINGS: [Warning; 14] = [
    Warning::StraightRowsOfKeysAreEasyToGuess,
    Warning::ShortKeyboardPatternsAreEasyToGuess,
    Warning::RepeatsLikeAaaAreEasyToGuess,
    Warning::RepeatsLikeAbcAbcAreOnlySlightlyHarderToGuess,
    Warning::ThisIsATop10Password,
    Warning::ThisIsATop100Password,
    Warning::ThisIsACommonPassword,
    Warning::ThisIsSimilarToACommonlyUsedPassword,
    Warning::SequencesLikeAbcAreEasyToGuess,
    Warning::RecentYearsAreEasyToGuess,
    Warning::AWordByItselfIsEasyToGuess,
    Warning::DatesAreOftenEasyToGuess,
    Warning::NamesAndSurnamesByThemselvesAreEasyToGuess,
    Warning::CommonNamesAndSurnamesAreEasyToGuess,
];

#[cfg(test)]
pub(crate) const ALL_SUGGESTIONS: [Suggestion; 13] = [
    Suggestion::UseAFewWordsAvoidCommonPhrases,
    Suggestion::NoNeedForSymbolsDigitsOrUppercaseLetters,
    Suggestion::AddAnotherWordOrTwo,
    Suggestion::CapitalizationDoesntHelpVeryMuch,
    Suggestion::AllUppercaseIsAlmostAsEasyToGuessAsAllLowercase,
    Suggestion::ReversedWordsArentMuchHarderToGuess,
    Suggestion::PredictableSubstitutionsDontHelpVeryMuch,
    Suggestion::UseALongerKeyboardPatternWithMoreTurns,
    Suggestion::AvoidRepeatedWordsAndCharacters,
    Suggestion::AvoidSequences,
    Suggestion::AvoidRecentYears,
    Suggestion::AvoidYearsThatAreAssociatedWithYou,
    Suggestion::AvoidDatesAndYearsThatAreAssociatedWithYou,
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_warning_has_a_unique_code_inside_the_declared_range() {
        let codes: Vec<i32> = ALL_WARNINGS.iter().copied().map(warning_code).collect();
        assert_eq!(codes, (0..14).collect::<Vec<i32>>());
        assert!(codes.iter().all(|code| *code != WARNING_NONE));
    }

    #[test]
    fn every_suggestion_has_a_distinct_bit_covering_the_v1_mask() {
        let mut mask = 0_u32;
        for suggestion in ALL_SUGGESTIONS {
            let bit = suggestion_bit(suggestion);
            assert_eq!(bit.count_ones(), 1, "{suggestion:?} must be a single bit");
            assert_eq!(mask & bit, 0, "{suggestion:?} reuses an assigned bit");
            mask |= bit;
        }
        assert_eq!(mask, SUGGESTION_MASK_ALL);
    }
}
