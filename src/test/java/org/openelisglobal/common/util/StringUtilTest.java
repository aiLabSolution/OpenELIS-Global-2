package org.openelisglobal.common.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StringUtilTest {

    private static final String VALID_INTEGER = "123";

    @Test
    public void isNullorNill_shouldReturnTrueForNull() {
        assertTrue(StringUtil.isNullorNill(null));
    }

    @Test
    public void isNullorNill_shouldReturnTrueForEmptyString() {
        assertTrue(StringUtil.isNullorNill(""));
    }

    @Test
    public void isNullorNill_shouldReturnTrueForNullString() {
        assertTrue(StringUtil.isNullorNill("null"));
    }

    @Test
    public void isNullorNill_shouldReturnFalseForValidString() {
        assertFalse(StringUtil.isNullorNill("test"));
    }

    @Test
    public void isInteger_shouldReturnTrueForValidInteger() {
        assertTrue(StringUtil.isInteger(VALID_INTEGER));
        assertTrue(StringUtil.isInteger("-456"));
    }

    @Test
    public void isInteger_shouldReturnFalseForInvalidValue() {
        assertFalse(StringUtil.isInteger("12.34"));
        assertFalse(StringUtil.isInteger("abc"));
    }

    @Test
    public void isNumeric_shouldReturnTrueForValidNumbers() {
        assertTrue(StringUtil.isNumeric(VALID_INTEGER));
        assertTrue(StringUtil.isNumeric("3.14"));
        assertTrue(StringUtil.isNumeric("-45.67"));
    }

    @Test
    public void isNumeric_shouldReturnFalseForInvalidValue() {
        assertFalse(StringUtil.isNumeric("abc"));
        assertFalse(StringUtil.isNumeric(null));
    }

    @Test
    public void blankIfNull_shouldReturnEmptyStringForNull() {
        assertEquals("", StringUtil.blankIfNull(null));
    }

    @Test
    public void blankIfNull_shouldReturnValueForNonNull() {
        assertEquals("test", StringUtil.blankIfNull("test"));
    }

    @Test
    public void safeEquals_shouldReturnTrueForBothNull() {
        assertTrue(StringUtil.safeEquals(null, null));
    }

    @Test
    public void safeEquals_shouldReturnTrueForEqualStrings() {
        assertTrue(StringUtil.safeEquals("hello", "hello"));
    }

    @Test
    public void safeEquals_shouldReturnFalseForDifferentStrings() {
        assertFalse(StringUtil.safeEquals("hello", "world"));
    }

    @Test
    public void containsOnly_shouldReturnTrueForMatchingChars() {
        assertTrue(StringUtil.containsOnly("aaaa", 'a'));
    }

    @Test
    public void containsOnly_shouldReturnFalseForMixedChars() {
        assertFalse(StringUtil.containsOnly("aaba", 'a'));
    }

    @Test
    public void containsOnly_shouldReturnFalseForNull() {
        assertFalse(StringUtil.containsOnly(null, 'a'));
    }

    @Test
    public void ellipsisString_shouldTruncateLongText() {
        assertEquals("Hello...", StringUtil.ellipsisString("Hello World", 5));
    }

    @Test
    public void ellipsisString_shouldReturnShortTextUnchanged() {
        assertEquals("Hi", StringUtil.ellipsisString("Hi", 10));
    }

    @Test
    public void capitalize_shouldCapitalizeFirstLetter() {
        assertEquals("Hello", StringUtil.capitalize("hello"));
    }

    @Test
    public void toArray_shouldSplitByComma() {
        String[] result = StringUtil.toArray("a, b, c");
        assertArrayEquals(new String[] { "a", "b", "c" }, result);
    }

    @Test
    public void toArray_shouldReturnEmptyArrayForNull() {
        assertArrayEquals(new String[0], StringUtil.toArray(null));
    }

    @Test
    public void replaceCharAtIndex_shouldReplaceCharacter() {
        assertEquals("hallo", StringUtil.replaceCharAtIndex("hello", 'a', 1));
    }

    @Test
    public void replaceCharAtIndex_shouldReturnUnchangedForInvalidIndex() {
        assertEquals("hello", StringUtil.replaceCharAtIndex("hello", 'a', -1));
    }

    @Test
    public void repeat_shouldRepeatString() {
        assertEquals("ababab", StringUtil.repeat("ab", 3));
    }

    @Test
    public void countInstances_shouldCountOccurrences() {
        assertEquals(3, StringUtil.countInstances("hello world", 'l'));
    }

    // doubleWithSignificantDigits: -1 means "raw value, no rounding" (LIS-188).
    // The int overload previously lacked the -1 guard and threw
    // UnknownFormatConversionException on the format string "%1$.-1f".
    @Test
    public void doubleWithSignificantDigits_int_minusOne_shouldReturnRawValue() {
        assertEquals("45.7", StringUtil.doubleWithSignificantDigits(45.7, -1));
    }

    @Test
    public void doubleWithSignificantDigits_int_zero_shouldRoundToWholeNumber() {
        assertEquals("46", StringUtil.doubleWithSignificantDigits(45.7, 0));
    }

    @Test
    public void doubleWithSignificantDigits_int_two_shouldFormatToTwoDecimals() {
        assertEquals("45.70", StringUtil.doubleWithSignificantDigits(45.7, 2));
    }

    @Test
    public void doubleWithSignificantDigits_string_minusOne_shouldReturnRawValue() {
        assertEquals("45.7", StringUtil.doubleWithSignificantDigits(45.7, "-1"));
    }

    @Test
    public void doubleWithSignificantDigits_string_two_shouldFormatToTwoDecimals() {
        assertEquals("45.70", StringUtil.doubleWithSignificantDigits(45.7, "2"));
    }

    // LIS-252: off-scale qualified results (<0.008, >1000, <=0.01, >=500, ≤/≥)

    @Test
    public void getActualNumericValue_shouldStripEveryComparatorToTheMagnitude() {
        assertEquals("0.008", StringUtil.getActualNumericValue("<0.008"));
        assertEquals("1000", StringUtil.getActualNumericValue(">1000"));
        // "<=" and ">=" used to leave a leading "=" and return "NaN" -> BigDecimal
        // crash
        assertEquals("0.01", StringUtil.getActualNumericValue("<=0.01"));
        assertEquals("500", StringUtil.getActualNumericValue(">=500"));
        assertEquals("0.01", StringUtil.getActualNumericValue("≤0.01"));
        assertEquals("500", StringUtil.getActualNumericValue("≥500"));
    }

    @Test
    public void getActualNumericValue_shouldLeaveOrdinaryNumericsUnchanged() {
        assertEquals("2.31", StringUtil.getActualNumericValue("2.31"));
        assertEquals("-5", StringUtil.getActualNumericValue("-5"));
    }

    @Test
    public void getActualNumericValue_shouldReturnNaNForGenuineText() {
        assertEquals("NaN", StringUtil.getActualNumericValue("Detected"));
        assertEquals("NaN", StringUtil.getActualNumericValue("<"));
    }

    @Test
    public void getLeadingComparator_shouldNormalizeToAsciiFhirCode() {
        assertEquals("<", StringUtil.getLeadingComparator("<0.008"));
        assertEquals(">", StringUtil.getLeadingComparator(">1000"));
        assertEquals("<=", StringUtil.getLeadingComparator("<=0.01"));
        assertEquals(">=", StringUtil.getLeadingComparator(">=500"));
        assertEquals("<=", StringUtil.getLeadingComparator("≤0.01"));
        assertEquals(">=", StringUtil.getLeadingComparator("≥500"));
        assertEquals(null, StringUtil.getLeadingComparator("2.31"));
        assertEquals(null, StringUtil.getLeadingComparator(null));
    }

    @Test
    public void stripLeadingComparator_shouldRemoveOnlyTheLeadingComparator() {
        assertEquals("0.008", StringUtil.stripLeadingComparator("<0.008"));
        assertEquals("0.01", StringUtil.stripLeadingComparator("<=0.01"));
        assertEquals("500", StringUtil.stripLeadingComparator("≥500"));
        assertEquals("2.31", StringUtil.stripLeadingComparator("2.31"));
    }
}
