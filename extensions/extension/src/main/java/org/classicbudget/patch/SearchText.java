package org.classicbudget.patch;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Search keys only: never change stored names, identifiers or exported data. */
public final class SearchText {
    // Join punctuation inside a word (E.ON, E-ON). A standalone '%' remains literal.
    private static final Pattern INNER_PUNCTUATION = Pattern.compile("(?<=[\\p{L}\\p{N}])\\p{P}+(?=[\\p{L}\\p{N}])");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public static String fold(CharSequence value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.toString().replace('\u0142', 'l').replace('\u0141', 'L'), Normalizer.Form.NFD);
        return MARKS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }
    public static boolean matches(CharSequence value, CharSequence query) {
        return new Query(query).matches(value);
    }

    static final class Query {
        private final String[] words, joinedWords;

        Query(CharSequence query) {
            String needle = fold(query).trim();
            words = needle.isEmpty() ? new String[0] : needle.split("\\s+");
            joinedWords = new String[words.length];
            for (int i = 0; i < words.length; i++) {
                joinedWords[i] = INNER_PUNCTUATION.matcher(words[i]).replaceAll("");
            }
        }

        boolean isEmpty() { return words.length == 0; }

        boolean matches(CharSequence value) {
            if (isEmpty()) return true;
            String haystack = fold(value);
            String joinedHaystack = null;
            for (int i = 0; i < words.length; i++) {
                if (haystack.contains(words[i])) continue;
                if (joinedHaystack == null) joinedHaystack = INNER_PUNCTUATION.matcher(haystack).replaceAll("");
                if (!joinedHaystack.contains(joinedWords[i])) return false;
            }
            return true;
        }
    }
}
