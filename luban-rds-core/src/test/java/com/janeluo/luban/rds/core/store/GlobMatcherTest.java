package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {
    @Test
    void starMatchesAnySequence() {
        assertTrue(GlobMatcher.match("user:1", "user:*"));
        assertTrue(GlobMatcher.match("", "*"));
        assertFalse(GlobMatcher.match("x", ""));
    }

    @Test
    void questionMatchesSingleChar() {
        assertTrue(GlobMatcher.match("user:1", "user:?"));
        assertFalse(GlobMatcher.match("user:12", "user:?"));
    }

    @Test
    void regexMetaCharsAreLiteral() {
        assertTrue(GlobMatcher.match("a.b", "a.b"));
        assertFalse(GlobMatcher.match("axb", "a.b"));
        assertFalse(GlobMatcher.match("a[b]", "a[b]"));
        assertTrue(GlobMatcher.match("a[b]", "a\\[b\\]"));
        assertTrue(GlobMatcher.match("a(b)", "a(b)"));
        assertTrue(GlobMatcher.match("a^b", "a^b"));
        assertTrue(GlobMatcher.match("a$b", "a$b"));
        assertTrue(GlobMatcher.match("a+b", "a+b"));
        assertTrue(GlobMatcher.match("a|b", "a|b"));
        assertTrue(GlobMatcher.match("a\\b", "a\\\\b"));
    }

    @Test
    void charClassMatches() {
        assertTrue(GlobMatcher.match("k1", "k[12]"));
        assertTrue(GlobMatcher.match("k2", "k[12]"));
        assertFalse(GlobMatcher.match("k3", "k[12]"));
    }

    @Test
    void charClassRangeAndNegation() {
        assertTrue(GlobMatcher.match("ka", "k[a-z]"));
        assertFalse(GlobMatcher.match("kA", "k[a-z]"));
        assertTrue(GlobMatcher.match("kA", "k[^a-z]"));
    }

    @Test
    void emptyCharClass() {
        assertFalse(GlobMatcher.match("x", "[]"));
        assertTrue(GlobMatcher.match("x", "[^]"));
    }

    @Test
    void rangeMatchesInterior() {
        assertTrue(GlobMatcher.match("km", "k[a-z]"));
        assertFalse(GlobMatcher.match("k-", "k[a-z]"));
    }

    @Test
    void reversedRangeSwapsBoundaries() {
        assertTrue(GlobMatcher.match("k", "[z-a]"));
        assertFalse(GlobMatcher.match("1", "[z-a]"));
    }

    @Test
    void wildcardsMatchLineTerminators() {
        assertTrue(GlobMatcher.match("a\nb", "a?b"));
        assertTrue(GlobMatcher.match("a\nb", "a*b"));
    }

    @Test
    void unclosedBracketIsLiteral() {
        assertTrue(GlobMatcher.match("abc[", "abc["));
        assertFalse(GlobMatcher.match("abcx", "abc["));
    }

    @Test
    void nullOrStarMatchesAll() {
        assertTrue(GlobMatcher.match("anything", null));
        assertTrue(GlobMatcher.match("anything", "*"));
    }
}
