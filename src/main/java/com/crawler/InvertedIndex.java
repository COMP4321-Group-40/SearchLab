package com.crawler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Inverted index for storing and searching crawled documents.
public class InvertedIndex {
    
    // Body index: term -> (docUrl -> list of positions)
    private final Map<String, Map<String, List<Integer>>> bodyIndex = new ConcurrentHashMap<>();
    // Title index: term -> (docUrl -> list of positions)
    private final Map<String, Map<String, List<Integer>>> titleIndex = new ConcurrentHashMap<>();
    
    // Document metadata
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();
    
    private int totalDocuments = 0;
    
    public void addDocument(String url, String title, List<StopStem.StemPosition> titleStems, List<StopStem.StemPosition> bodyStems) {
        indexStems(bodyIndex, url, bodyStems);
        indexStems(titleIndex, url, titleStems);

        documents.put(url, new DocumentInfo(url, title, bodyStems.size(), titleStems.size()));

        totalDocuments++;
    }

    private void indexStems(Map<String, Map<String, List<Integer>>> index, String url, List<StopStem.StemPosition> stems) {
        // Group the stems 
        Map<String, List<Integer>> stemPos = new HashMap<>();
        for(StopStem.StemPosition sp : stems) {
            stemPos.computeIfAbsent(sp.stem,  k -> new ArrayList<>()).add(sp.position);
        }

        // Add stems to index
        for (Map.Entry<String, List<Integer>> entry : stemPos.entrySet()) {
            String stem = entry.getKey();
            List<Integer> pos = entry.getValue();
            
            index.computeIfAbsent(stem, k -> new ConcurrentHashMap<>()).put(url, pos);
        }
    }
    
    public List<SearchResult> search(String query, boolean searchTitle, boolean searchBody) {

        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        String[] terms = query.toLowerCase().split("\\s+");
        if (terms.length == 1) {
            return searchSingleTerm(terms[0], searchTitle, searchBody);
        } else {
            // For multiple terms, treat as phrase search
            return searchPhrase(query, searchTitle, searchBody);
        }
    }

        private List<SearchResult> searchSingleTerm(String term, boolean searchTitle, boolean searchBody) {
            Map<String, Double> scores = new HashMap<>();
            
            if (searchTitle) {
                addTermScores(titleIndex, term, scores, true);
            }
            if (searchBody) {
                addTermScores(bodyIndex, term, scores, false);
            }
            
            return scores.entrySet().stream()
                .map(entry -> new SearchResult(
                    entry.getKey(),
                    documents.get(entry.getKey()).getTitle(),
                    entry.getValue()
                ))
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }
    
    private void addTermScores(Map<String, Map<String, List<Integer>>> index, String term, Map<String, Double> scores, boolean isTitle) {
        if (!index.containsKey(term)) return;
        
        Map<String, List<Integer>> postings = index.get(term);
        double idf = calculateIDF(term, index);
        
        for (Map.Entry<String, List<Integer>> entry : postings.entrySet()) {
            String url = entry.getKey();
            double tf = entry.getValue().size(); // term frequency
            double tfIdf = tf * idf;
            
            if (isTitle) {
                tfIdf *= 1.5; // boost title matches
            }
            
            scores.merge(url, tfIdf, Double::sum);
        }
    }
    
    private double calculateIDF(String term, Map<String, Map<String, List<Integer>>> index) {
        double docCount = index.getOrDefault(term, Collections.emptyMap()).size();
        if (docCount == 0) return 0;
        return Math.log((double) totalDocuments / docCount);
    }

    public List<SearchResult> searchPhrase(String phrase, boolean searchTitle, boolean searchBody) {
        // Split phrase into stems
        String[] words = phrase.toLowerCase().split("\\s+");
        String[] stems = Arrays.stream(words)
                .map(w -> w.replaceAll("[^a-zA-Z]", ""))
                .filter(w -> !w.isEmpty())
                .toArray(String[]::new);
        
        if (stems.length == 0) return Collections.emptyList();
        
        Set<String> candidateDocs = new HashSet<>();
        Map<String, Double> scores = new HashMap<>();
        
        if (searchTitle) {
            candidateDocs.addAll(findPhraseInIndex(titleIndex, stems));
            // For scoring, we could use term frequency in title or just count matches
            // We'll give a fixed boost for title phrase matches
            for (String url : candidateDocs) {
                scores.merge(url, 2.0, Double::sum); // boost for title phrase match
            }
        }
        if (searchBody) {
            Set<String> bodyMatches = findPhraseInIndex(bodyIndex, stems);
            candidateDocs.addAll(bodyMatches);
            for (String url : bodyMatches) {
                scores.merge(url, 1.0, Double::sum);
            }
        }
        
        // Build results with scores
        List<SearchResult> results = new ArrayList<>();
        for (String url : candidateDocs) {
            DocumentInfo info = documents.get(url);
            results.add(new SearchResult(url, info.getTitle(), scores.getOrDefault(url, 0.0)));
        }
        
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results;
    }
    
    private Set<String> findPhraseInIndex(Map<String, Map<String, List<Integer>>> index, String[] stems) {
        if (stems.length == 0) return Collections.emptySet();
        
        // Get postings for first stem
        Map<String, List<Integer>> firstPostings = index.get(stems[0]);
        if (firstPostings == null) return Collections.emptySet();
        
        Set<String> candidates = new HashSet<>(firstPostings.keySet());
        
        // For each subsequent stem, filter candidates that have the stem at consecutive positions
        for (int i = 1; i < stems.length; i++) {
            String stem = stems[i];
            Map<String, List<Integer>> postings = index.get(stem);
            if (postings == null) {
                return Collections.emptySet();
            }
            
            Set<String> newCandidates = new HashSet<>();
            for (String url : candidates) {
                List<Integer> prevPositions = firstPostings.get(url); // positions of first stem
                List<Integer> currPositions = postings.get(url);
                if (currPositions == null) continue;
                
                // Check if there exists a position p in prevPositions such that p+i is in currPositions
                for (int pos : prevPositions) {
                    if (currPositions.contains(pos + i)) {
                        newCandidates.add(url);
                        break;
                    }
                }
            }
            candidates = newCandidates;
            firstPostings = postings; 

            Map<String, List<Integer>> currentPositionsMap = new HashMap<>();
            for (String url : candidates) {
                currentPositionsMap.put(url, postings.get(url));
            }

            firstPostings = currentPositionsMap;
        }
        
        return candidates;
    }
    
    public Map<String, Integer> getTopWords(int n) {
        // Compute term frequencies across all documents
        Map<String, Integer> termFreq = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Integer>>> entry : bodyIndex.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(List::size).sum();
            termFreq.put(entry.getKey(), total);
        }
        return termFreq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ));
    }

    public int getWordCount() {
        return bodyIndex.size(); // unique body stems
    }
    
    public int getTotalOccurrences() {
        return documents.values().stream().mapToInt(DocumentInfo::getBodyLength).sum();
    }
    
    private static class DocumentInfo {
        private final String url;
        private final String title;
        private final int bodyLength;
        private final int titleLength;
        
        public DocumentInfo(String url, String title, int bodyLength, int titleLength) {
            this.url = url;
            this.title = title;
            this.bodyLength = bodyLength;
            this.titleLength = titleLength;
        }
        
        public String getTitle() { return title; }
        public int getBodyLength() { return bodyLength; }
        public int getTitleLength() { return titleLength; }
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