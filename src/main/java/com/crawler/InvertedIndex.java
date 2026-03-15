package com.crawler;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Inverted index for storing and searching crawled documents.
public class InvertedIndex {
    
    // Word -> Map of Document URL -> Term Frequency
    private final Map<String, Map<String, Integer>> index = new ConcurrentHashMap<>();
    
    // Document metadata
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    
    private int totalDocuments = 0;
    private int totalWords = 0;
    
    public void addDocument(String url, String title, String content) {
        // Add a document to the index.

        String[] words = content.toLowerCase().split("\\s+");
        
        // Count word frequencies in this document
        Map<String, Integer> termFreq = new HashMap<>();
        for (String word : words) {
            if (!word.isEmpty()) {
                termFreq.merge(word, 1, Integer::sum);
            }
        }
        
        // Update index
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String word = entry.getKey();
            int count = entry.getValue();
            
            index.computeIfAbsent(word, k -> new ConcurrentHashMap<>())
                 .put(url, count);
        }
        
        // Store document info
        documents.put(url, new DocumentInfo(url, title, words.length));
        totalDocuments++;
        totalWords += words.length;
    }
    
    public List<SearchResult> search(String query) {
        // Search for documents

        String[] queryTerms = query.toLowerCase().split("\\s+");
        
        // Calculate scores for each document
        Map<String, Double> scores = new HashMap<>();
        
        for (String term : queryTerms) {
            if (index.containsKey(term)) {
                Map<String, Integer> postings = index.get(term);
                double idf = calculateIDF(term);
                
                for (Map.Entry<String, Integer> entry : postings.entrySet()) {
                    String url = entry.getKey();
                    double tf = entry.getValue();
                    double tfIdf = tf * idf;
                    
                    scores.merge(url, tfIdf, Double::sum);
                }
            }
        }
        
        // Convert to sorted results
        return scores.entrySet().stream()
                .map(entry -> new SearchResult(
                    entry.getKey(), 
                    documents.get(entry.getKey()).getTitle(),
                    entry.getValue()
                ))
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }
    
    private double calculateIDF(String term) {
        double docCount = index.getOrDefault(term, Collections.emptyMap()).size();
        if (docCount == 0) return 0;
        return Math.log((double) totalDocuments / docCount);
    }
    
    public int getWordCount() {
        return index.size();
    }
    
    public int getTotalOccurrences() {
        return totalWords;
    }
    
    public Map<String, Integer> getTopWords(int n) {
        // Get top N most frequent words in all documents
        return index.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().values().stream().mapToInt(Integer::intValue).sum()
                ))
                .entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ));
    }
    
    private static class DocumentInfo {
        private final String url;
        private final String title;
        private final int wordCount;
        
        public DocumentInfo(String url, String title, int wordCount) {
            this.url = url;
            this.title = title;
            this.wordCount = wordCount;
        }
        
        public String getTitle() { return title; }
        public int getWordCount() { return wordCount; }
    }
    
    public static class SearchResult {
        private final String url;
        private final String title;
        private final double score;
        
        public SearchResult(String url, String title, double score) {
            this.url = url;
            this.title = title;
            this.score = score;
        }
        
        public String getUrl() { return url; }
        public String getTitle() { return title; }
        public double getScore() { return score; }
    }
}