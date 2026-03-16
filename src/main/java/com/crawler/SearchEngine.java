package com.crawler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class SearchEngine {
    private final InvertedIndex index;
    private final StopStem stemmer;
    private final Map<String, Double> docNorm = new HashMap<>();
    private boolean normsComputed = false;
    private static final double TITLE_BOOST = 1.5;
    
    public SearchEngine(InvertedIndex index, StopStem stemmer) {
        this.index = index;
        this.stemmer = stemmer;
    }
    
    // Precompute document vector lengths (norms) for cosine similarity.
    public void computeNorms() {
        docNorm.clear();
        int totalDocs = index.getTotalDocuments();
        if (totalDocs == 0) return;
        
        // Precompute IDF for all terms in body and title
        Map<String, Double> idfBody = new HashMap<>();
        Map<String, Double> idfTitle = new HashMap<>();
        for (String term : index.getBodyIndex().keySet()) {
            double df = index.getBodyIndex().get(term).size();
            idfBody.put(term, Math.log((double) totalDocs / df));
        }
        for (String term : index.getTitleIndex().keySet()) {
            double df = index.getTitleIndex().get(term).size();
            idfTitle.put(term, Math.log((double) totalDocs / df));
        }
        
        for (Map.Entry<String, InvertedIndex.DocumentInfo> docEntry : index.getDocuments().entrySet()) {
            String url = docEntry.getKey();
            double sumSq = 0.0;
            int maxBodyTf = index.getDocMaxTfBody().getOrDefault(url, 1);
            int maxTitleTf = index.getDocMaxTfTitle().getOrDefault(url, 1);
            
            // Body terms
            for (Map.Entry<String, Map<String, List<Integer>>> termEntry : index.getBodyIndex().entrySet()) {
                String term = termEntry.getKey();
                Map<String, List<Integer>> postings = termEntry.getValue();
                List<Integer> positions = postings.get(url);
                if (positions != null) {
                    double tf = positions.size();
                    double normTf = tf / maxBodyTf;
                    double idf = idfBody.get(term);
                    double weight = (0.5 + 0.5 * normTf) * idf;
                    sumSq += weight * weight;
                }
            }
            // Title terms with boost
            for (Map.Entry<String, Map<String, List<Integer>>> termEntry : index.getTitleIndex().entrySet()) {
                String term = termEntry.getKey();
                Map<String, List<Integer>> postings = termEntry.getValue();
                List<Integer> positions = postings.get(url);
                if (positions != null) {
                    double tf = positions.size();
                    double normTf = tf / maxTitleTf;
                    double idf = idfTitle.get(term);
                    double weight = (0.5 + 0.5 * normTf) * idf * TITLE_BOOST;
                    sumSq += weight * weight;
                }
            }
            docNorm.put(url, Math.sqrt(sumSq));
        }
        normsComputed = true;
    }
    
    // Public search with query
    public List<SearchResult> search(String query, boolean searchTitle, boolean searchBody) {
        if (!normsComputed) {
            throw new IllegalStateException("Norms not computed. Call computeNorms() after crawling.");
        }
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        
        // Stem query terms
        String[] rawWords = query.toLowerCase().split("\\s+");
        List<String> stemmedTerms = new ArrayList<>();
        for (String word : rawWords) {
            String cleaned = word.replaceAll("[^a-zA-Z]", "");
            if (!cleaned.isEmpty()) {
                String stem = stemmer.processWord(cleaned);
                if (!stem.isEmpty()) {
                    stemmedTerms.add(stem);
                }
            }
        }
        if (stemmedTerms.isEmpty()) return Collections.emptyList();
        
        // Determine search type
        if (stemmedTerms.size() == 1) {
            return searchSingleTerm(stemmedTerms.get(0), searchTitle, searchBody);
        } else {
            return searchPhrase(stemmedTerms, searchTitle, searchBody);
        }
    }
    
    private List<SearchResult> searchSingleTerm(String term, boolean searchTitle, boolean searchBody) {
        Set<String> candidates = new HashSet<>();
        if (searchBody && index.getBodyIndex().containsKey(term)) {
            candidates.addAll(index.getBodyIndex().get(term).keySet());
        }
        if (searchTitle && index.getTitleIndex().containsKey(term)) {
            candidates.addAll(index.getTitleIndex().get(term).keySet());
        }
        if (candidates.isEmpty()) return Collections.emptyList();
        return computeCosineScores(Collections.singletonList(term), candidates);
    }
    
    private List<SearchResult> searchPhrase(List<String> stems, boolean searchTitle, boolean searchBody) {
        Set<String> candidates = new HashSet<>();
        if (searchTitle) {
            candidates.addAll(findPhraseInIndex(index.getTitleIndex(), stems));
        }
        if (searchBody) {
            candidates.addAll(findPhraseInIndex(index.getBodyIndex(), stems));
        }
        if (candidates.isEmpty()) return Collections.emptyList();
        return computeCosineScores(stems, candidates);
    }
    
    private Set<String> findPhraseInIndex(Map<String, Map<String, List<Integer>>> indexMap, List<String> stems) {
        if (stems.isEmpty()) return Collections.emptySet();
        Map<String, List<Integer>> firstPostings = indexMap.get(stems.get(0));
        if (firstPostings == null) return Collections.emptySet();
        
        Set<String> candidates = new HashSet<>(firstPostings.keySet());
        // For each subsequent stem, filter
        for (int i = 1; i < stems.size(); i++) {
            String stem = stems.get(i);
            Map<String, List<Integer>> postings = indexMap.get(stem);
            if (postings == null) return Collections.emptySet();
            
            Set<String> newCandidates = new HashSet<>();
            for (String url : candidates) {
                List<Integer> prevPositions = firstPostings.get(url);
                List<Integer> currPositions = postings.get(url);
                if (currPositions == null) continue;
                // Check if any position p such that p+i exists
                for (int pos : prevPositions) {
                    if (currPositions.contains(pos + i)) {
                        newCandidates.add(url);
                        break;
                    }
                }
            }
            candidates = newCandidates;
            firstPostings = postings; // update for next step
        }
        return candidates;
    }
    
    private List<SearchResult> computeCosineScores(List<String> queryStems, Set<String> candidateDocs) {
        int totalDocs = index.getTotalDocuments();
        
        // Compute IDF for each query stem
        Map<String, Double> idfBody = new HashMap<>();
        Map<String, Double> idfTitle = new HashMap<>();
        for (String term : queryStems) {
            double dfBody = index.getBodyIndex().containsKey(term) ? index.getBodyIndex().get(term).size() : 0;
            idfBody.put(term, dfBody > 0 ? Math.log((double) totalDocs / dfBody) : 0);
            double dfTitle = index.getTitleIndex().containsKey(term) ? index.getTitleIndex().get(term).size() : 0;
            idfTitle.put(term, dfTitle > 0 ? Math.log((double) totalDocs / dfTitle) : 0);
        }
        
        // Compute query vector norm (using body IDF as query weight)
        double queryNormSq = 0;
        for (String term : queryStems) {
            double qw = idfBody.get(term);
            queryNormSq += qw * qw;
        }
        double queryNorm = Math.sqrt(queryNormSq);
        if (queryNorm == 0) queryNorm = 1;
        
        Map<String, Double> scores = new HashMap<>();
        for (String url : candidateDocs) {
            double dot = 0.0;
            int maxBodyTf = index.getDocMaxTfBody().getOrDefault(url, 1);
            int maxTitleTf = index.getDocMaxTfTitle().getOrDefault(url, 1);
            
            for (String term : queryStems) {
                // Body contribution
                if (index.getBodyIndex().containsKey(term)) {
                    Map<String, List<Integer>> postings = index.getBodyIndex().get(term);
                    List<Integer> positions = postings.get(url);
                    if (positions != null) {
                        double tf = positions.size();
                        double normTf = tf / maxBodyTf;
                        double idf = idfBody.get(term);
                        double docWeight = normTf * idf;
                        double qw = idf;
                        dot += docWeight * qw;
                    }
                }
                // Title contribution with boost
                if (index.getTitleIndex().containsKey(term)) {
                    Map<String, List<Integer>> postings = index.getTitleIndex().get(term);
                    List<Integer> positions = postings.get(url);
                    if (positions != null) {
                        double tf = positions.size();
                        double normTf = tf / maxTitleTf;
                        double idf = idfTitle.get(term);
                        double docWeight = normTf * idf * TITLE_BOOST;
                        double qw = idf;
                        dot += docWeight * qw;
                    }
                }
            }
            double docNormVal = docNorm.getOrDefault(url, 1.0);
            double score = dot / (docNormVal * queryNorm);
            if (score > 0) {
                scores.put(url, score);
            }
        }
        
        // Build results
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            String url = entry.getKey();
            String title = index.getDocuments().get(url).getTitle();
            results.add(new SearchResult(url, title, entry.getValue()));
        }
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        // Limit to top 50
        int maxResults = Math.min(50, results.size());
        return results.subList(0, maxResults);
    }
    
    /**
     * Search result data class.
     */
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