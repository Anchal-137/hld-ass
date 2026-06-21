package com.scaler.typeahead.trie;

import com.scaler.typeahead.dto.SuggestionDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * In-memory prefix tree (Trie) for typeahead - the first of the two approaches
 * required by Phase 4.
 *
 * <p><b>Insert</b> walks/creates one node per character: O(L) for a query of
 * length L. <b>Search</b> walks to the prefix node in O(P) for a prefix of
 * length P, then collects the completions in that subtree and selects the top K
 * by count using a bounded min-heap.
 *
 * <p><b>Complexity:</b>
 * <ul>
 *   <li>Insert: O(L) time, contributes O(L) new nodes worst case.</li>
 *   <li>Search: O(P + M log K) where M = number of completions under the prefix
 *       and K = result size (10). The {@code M} term is the cost of the subtree
 *       walk; the {@code log K} is the heap.</li>
 *   <li>Space: O(total characters across all queries) nodes.</li>
 * </ul>
 *
 * <p><b>Production optimization (discussed in DESIGN.md):</b> cache the top-K
 * completions at each node during the build so search becomes O(P). We keep the
 * subtree-walk version here because it is simpler to reason about, correct under
 * the immutable build snapshot, and the Redis cache already absorbs repeat reads.
 *
 * <p>This class is not thread-safe for concurrent writes; it is built once from
 * an immutable snapshot and then read concurrently (safe), and atomically
 * swapped on rebuild by {@link TrieService}.
 */
public class Trie {

    private final TrieNode root = new TrieNode();
    private int wordCount;

    /** Insert or set a query to an ABSOLUTE count (used when building from a snapshot). */
    public void insert(String wordNorm, long count) {
        terminal(wordNorm).count = count;
    }

    /** Increment a query's count by {@code delta}, creating it if absent. */
    public void increment(String wordNorm, long delta) {
        TrieNode n = terminal(wordNorm);
        n.count += delta;
    }

    /** Walk to (creating as needed) the terminal node for {@code wordNorm}. */
    private TrieNode terminal(String wordNorm) {
        TrieNode node = root;
        for (int i = 0; i < wordNorm.length(); i++) {
            node = node.children.computeIfAbsent(wordNorm.charAt(i), c -> new TrieNode());
        }
        if (!node.isWord) {
            wordCount++;
        }
        node.isWord = true;
        node.word = wordNorm;
        return node;
    }

    /** Top-{@code k} completions of {@code prefixNorm}, ordered by count desc. */
    public List<SuggestionDto> search(String prefixNorm, int k) {
        TrieNode node = root;
        for (int i = 0; i < prefixNorm.length(); i++) {
            node = node.children.get(prefixNorm.charAt(i));
            if (node == null) {
                return List.of(); // no query starts with this prefix
            }
        }
        // Min-heap of size k: keep the k highest-count completions seen so far.
        PriorityQueue<TrieNode> heap = new PriorityQueue<>(Comparator.comparingLong(n -> n.count));
        collect(node, heap, k);

        List<SuggestionDto> out = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            TrieNode n = heap.poll();
            out.add(new SuggestionDto(n.word, n.count));
        }
        out.sort(Comparator.comparingLong(SuggestionDto::count).reversed());
        return out;
    }

    private void collect(TrieNode node, PriorityQueue<TrieNode> heap, int k) {
        if (node.isWord) {
            if (heap.size() < k) {
                heap.offer(node);
            } else if (node.count > heap.peek().count) {
                heap.poll();
                heap.offer(node);
            }
        }
        for (TrieNode child : node.children.values()) {
            collect(child, heap, k);
        }
    }

    public int wordCount() {
        return wordCount;
    }
}
