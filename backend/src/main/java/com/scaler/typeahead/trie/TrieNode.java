package com.scaler.typeahead.trie;

import java.util.HashMap;
import java.util.Map;

/**
 * A node in the prefix tree.
 *
 * <p>{@code children} maps the next character to the child node. {@code isWord}
 * marks the end of a complete query, and {@code count} is that query's
 * popularity (only meaningful when {@code isWord} is true).
 *
 * <p>We use a {@link HashMap} for children rather than a fixed array because the
 * query alphabet is open (letters, digits, spaces, unicode); an array indexed by
 * char would waste memory and break on non-ASCII input.
 */
public class TrieNode {
    final Map<Character, TrieNode> children = new HashMap<>();
    boolean isWord;
    long count;
    String word; // the full query stored at this terminal node (null otherwise)
}
