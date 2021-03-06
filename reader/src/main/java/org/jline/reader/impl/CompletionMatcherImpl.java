/*
 * Copyright (c) 2002-2020, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.reader.impl;

import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.CompletionMatcher;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CompletionMatcherImpl implements CompletionMatcher {
    protected Predicate<String> exact;
    protected List<Function<Map<String, List<Candidate>>, Map<String, List<Candidate>>>> matchers;
    private Map<String, List<Candidate>> matching;
    private boolean caseInsensitive;

    public CompletionMatcherImpl() {
    }

    protected void reset(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
        exact = s -> false;
        matchers = new ArrayList<>();
        matching = null;
    }

    @Override
    public void compile(Map<LineReader.Option, Boolean> options, boolean prefix, CompletingParsedLine line
            , boolean caseInsensitive, int errors, String originalGroupName) {
        reset(caseInsensitive);
        defaultMatchers(options, prefix, line, caseInsensitive, errors, originalGroupName);
        if (LineReader.Option.COMPLETE_MATCHER_CAMELCASE.isSet(options)) {
            matchers.add(simpleMatcher(candidate -> camelMatch(line.word(), 0, candidate, 0)));
        }
    }

    @Override
    public List<Candidate> matches(List<Candidate> candidates) {
        matching = Collections.emptyMap();
        Map<String, List<Candidate>> sortedCandidates = sort(candidates);
        for (Function<Map<String, List<Candidate>>,
                Map<String, List<Candidate>>> matcher : matchers) {
            matching = matcher.apply(sortedCandidates);
            if (!matching.isEmpty()) {
                break;
            }
        }
        return !matching.isEmpty() ? matching.entrySet().stream().flatMap(e -> e.getValue().stream()).collect(Collectors.toList())
                                   : new ArrayList<>();
    }

    @Override
    public Candidate exactMatch() {
        if (matching == null) {
            throw new IllegalStateException();
        }
        return matching.values().stream().flatMap(Collection::stream)
                .filter(Candidate::complete)
                .filter(c -> exact.test(c.value()))
                .findFirst().orElse(null);
    }

    @Override
    public String getCommonPrefix() {
        if (matching == null) {
            throw new IllegalStateException();
        }
        String commonPrefix = null;
        for (String key : matching.keySet()) {
            commonPrefix = commonPrefix == null ? key : getCommonStart(commonPrefix, key, caseInsensitive);
        }
        return commonPrefix;
    }

    /**
     * Default JLine matchers
     */
    protected void defaultMatchers(Map<LineReader.Option, Boolean> options, boolean prefix, CompletingParsedLine line
            , boolean caseInsensitive, int errors, String originalGroupName) {
        // Find matchers
        // TODO: glob completion
        if (prefix) {
            String wd = line.word();
            String wdi = caseInsensitive ? wd.toLowerCase() : wd;
            String wp = wdi.substring(0, line.wordCursor());
            matchers = new ArrayList<>(Arrays.asList(
                    simpleMatcher(s -> (caseInsensitive ? s.toLowerCase() : s).startsWith(wp)),
                    simpleMatcher(s -> (caseInsensitive ? s.toLowerCase() : s).contains(wp)),
                    typoMatcher(wp, errors, caseInsensitive, originalGroupName)
            ));
            exact = s -> caseInsensitive ? s.equalsIgnoreCase(wp) : s.equals(wp);
        } else if (LineReader.Option.COMPLETE_IN_WORD.isSet(options)) {
            String wd = line.word();
            String wdi = caseInsensitive ? wd.toLowerCase() : wd;
            String wp = wdi.substring(0, line.wordCursor());
            String ws = wdi.substring(line.wordCursor());
            Pattern p1 = Pattern.compile(Pattern.quote(wp) + ".*" + Pattern.quote(ws) + ".*");
            Pattern p2 = Pattern.compile(".*" + Pattern.quote(wp) + ".*" + Pattern.quote(ws) + ".*");
            matchers = new ArrayList<>(Arrays.asList(
                    simpleMatcher(s -> p1.matcher(caseInsensitive ? s.toLowerCase() : s).matches()),
                    simpleMatcher(s -> p2.matcher(caseInsensitive ? s.toLowerCase() : s).matches()),
                    typoMatcher(wdi, errors, caseInsensitive, originalGroupName)
            ));
            exact = s -> caseInsensitive ? s.equalsIgnoreCase(wd) : s.equals(wd);
        } else {
            String wd = line.word();
            String wdi = caseInsensitive ? wd.toLowerCase() : wd;
            if (LineReader.Option.EMPTY_WORD_OPTIONS.isSet(options) || wd.length() > 0) {
                matchers = new ArrayList<>(Arrays.asList(
                        simpleMatcher(s -> (caseInsensitive ? s.toLowerCase() : s).startsWith(wdi)),
                        simpleMatcher(s -> (caseInsensitive ? s.toLowerCase() : s).contains(wdi)),
                        typoMatcher(wdi, errors, caseInsensitive, originalGroupName)
                ));
            } else {
                matchers = new ArrayList<>(Collections.singletonList(simpleMatcher(s -> !s.startsWith("-"))));
            }
            exact = s -> caseInsensitive ? s.equalsIgnoreCase(wd) : s.equals(wd);
        }
    }

    protected Function<Map<String, List<Candidate>>,
            Map<String, List<Candidate>>> simpleMatcher(Predicate<String> predicate) {
        return m -> m.entrySet().stream()
                .filter(e -> predicate.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    protected Function<Map<String, List<Candidate>>,
            Map<String, List<Candidate>>> typoMatcher(String word, int errors, boolean caseInsensitive, String originalGroupName) {
        return m -> {
            Map<String, List<Candidate>> map = m.entrySet().stream()
                    .filter(e -> ReaderUtils.distance(word, caseInsensitive ? e.getKey() : e.getKey().toLowerCase()) < errors)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (map.size() > 1) {
                map.computeIfAbsent(word, w -> new ArrayList<>())
                        .add(new Candidate(word, word, originalGroupName, null, null, null, false));
            }
            return map;
        };
    }

    protected boolean camelMatch(String word, int i, String candidate, int j) {
        if (word.length() <= i) {
            return true;
        } else {
            char c = word.charAt(i);
            if (candidate.length() <= j) {
                return false;
            }
            if (c == candidate.charAt(j)) {
                if (camelMatch(word, i + 1, candidate, j + 1)) {
                    return true;
                }
            }
            for (int j1 = j; j1 < candidate.length(); j1++) {
                if (Character.isUpperCase(candidate.charAt(j1))) {
                    if (Character.toUpperCase(c) == candidate.charAt(j1)) {
                        if (camelMatch(word, i + 1, candidate, j1 + 1)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private Map<String, List<Candidate>> sort(List<Candidate> candidates) {
        // Build a list of sorted candidates
        Map<String, List<Candidate>> sortedCandidates = new HashMap<>();
        for (Candidate candidate : candidates) {
            sortedCandidates
                    .computeIfAbsent(AttributedString.fromAnsi(candidate.value()).toString(), s -> new ArrayList<>())
                    .add(candidate);
        }
        return sortedCandidates;
    }

    private String getCommonStart(String str1, String str2, boolean caseInsensitive) {
        int[] s1 = str1.codePoints().toArray();
        int[] s2 = str2.codePoints().toArray();
        int len = 0;
        while (len < Math.min(s1.length, s2.length)) {
            int ch1 = s1[len];
            int ch2 = s2[len];
            if (ch1 != ch2 && caseInsensitive) {
                ch1 = Character.toUpperCase(ch1);
                ch2 = Character.toUpperCase(ch2);
                if (ch1 != ch2) {
                    ch1 = Character.toLowerCase(ch1);
                    ch2 = Character.toLowerCase(ch2);
                }
            }
            if (ch1 != ch2) {
                break;
            }
            len++;
        }
        return new String(s1, 0, len);
    }

}