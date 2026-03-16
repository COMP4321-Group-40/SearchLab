package com.crawler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Inverted index for storing and searching crawled documents.
public class InvertedIndex {
    
    // Body index: term -> (docUrl -> list of positions)
    private final Map<String, Map<String, List<Integer>>> bodyIndex = new ConcurrentHashMap<>();
    // Title index: term -> (docUrl -> list of positions)
    private final Map<String, Map<String, List<Integer>>> titleIndex = new ConcurrentHashMap<>();
    
    // Document metadata
    private final Map<String, DocumentInfo> documents = new ConcurrentHashMap<>();


    // Page relationships
    private final AtomicInteger nextPageId = new AtomicInteger(1);
    private final Map<String, Integer> urlToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToUrl = new ConcurrentHashMap<>();
    
    // Last modified times (epoch milliseconds)
    private final Map<Integer, Long> lastModified = new ConcurrentHashMap<>();
    
    // Parent-child relationships: parentId -> Set of childIds
    private final Map<Integer, Set<Integer>> parentToChildren = new ConcurrentHashMap<>();
    // Child-parent relationships: childId -> Set of parentIds
    private final Map<Integer, Set<Integer>> childToParents = new ConcurrentHashMap<>();

    private final Map<String, Integer> docMaxTfBody = new ConcurrentHashMap<>();
    private final Map<String, Integer> docMaxTfTitle = new ConcurrentHashMap<>();
    
    private int totalDocuments = 0;

    public int getPageId(String url) {
        return urlToId.computeIfAbsent(url, k -> {
            int id = nextPageId.getAndIncrement();
            idToUrl.put(id, url);
            return id;
        });
    }

    public String getUrl(int pageId) {
        return idToUrl.get(pageId);
    }

    public boolean containsUrl(String url) {
        return documents.containsKey(url);
    }

    public Long getLastModified(String url) {
        Integer id = urlToId.get(url);
        return id != null ? lastModified.get(id) : null;
    }

    public void setLastModified(String url, long timestamp) {
        Integer id = urlToId.get(url);
        if (id != null) {
            lastModified.put(id, timestamp);
        }
    }

    // ===== Link Graph =====

    public void addLinkRelation(String parentUrl, String childUrl) {
        int parentId = getPageId(parentUrl);
        int childId = getPageId(childUrl);
        
        parentToChildren.computeIfAbsent(parentId, k -> ConcurrentHashMap.newKeySet()).add(childId);
        childToParents.computeIfAbsent(childId, k -> ConcurrentHashMap.newKeySet()).add(parentId);
    }

    public Set<Integer> getChildren(int pageId) {
        return parentToChildren.getOrDefault(pageId, Collections.emptySet());
    }
    
    public Set<Integer> getParents(int pageId) {
        return childToParents.getOrDefault(pageId, Collections.emptySet());
    }

    public Set<Integer> getAllPageIds() {
        return idToUrl.keySet();
    }

    // ===== Indexing =====
    
    public void addDocument(String url, String title, List<StopStem.StemPosition> titleStems, List<StopStem.StemPosition> bodyStems, long lastModifiedTime) {
        int pageId = getPageId(url);
        lastModified.put(pageId, lastModifiedTime);
        
        indexStems(bodyIndex, url, bodyStems);
        indexStems(titleIndex, url, titleStems);

        documents.put(url, new DocumentInfo(url, title, bodyStems.size(), titleStems.size()));

        Map<String, Integer> bodyFreq = new HashMap<>();
        for (StopStem.StemPosition sp : bodyStems) {
            bodyFreq.merge(sp.stem, 1, Integer::sum);
        }
        int maxBodyTf = bodyFreq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        docMaxTfBody.put(url, maxBodyTf);
        
        // Compute max term frequencies for title
        Map<String, Integer> titleFreq = new HashMap<>();
        for (StopStem.StemPosition sp : titleStems) {
            titleFreq.merge(sp.stem, 1, Integer::sum);
        }
        int maxTitleTf = titleFreq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        docMaxTfTitle.put(url, maxTitleTf);

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
    
    private int computeMaxTf(List<StopStem.StemPosition> stems) {
        Map<String, Integer> freq = new HashMap<>();
        for (StopStem.StemPosition sp : stems) {
            freq.merge(sp.stem, 1, Integer::sum);
        }
        return freq.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    // ===== Statistics =====

    public int getWordCount() {
        return bodyIndex.size(); // unique body stems
    }
    
    public int getTotalOccurrences() {
        return documents.values().stream().mapToInt(DocumentInfo::getBodyLength).sum();
    }

    public Map<String, Integer> getTopWords(int n) {
        Map<String, Integer> termFreq = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Integer>>> entry : bodyIndex.entrySet()) {
            int total = entry.getValue().values().stream().mapToInt(List::size).sum();
            termFreq.put(entry.getKey(), total);
        }
        return termFreq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    // ===== Getters =====
    protected Map<String, Map<String, List<Integer>>> getBodyIndex() { return bodyIndex; }
    protected Map<String, Map<String, List<Integer>>> getTitleIndex() { return titleIndex; }
    protected Map<String, DocumentInfo> getDocuments() { return documents; }
    protected Map<String, Integer> getDocMaxTfBody() { return docMaxTfBody; }
    protected Map<String, Integer> getDocMaxTfTitle() { return docMaxTfTitle; }
    protected int getTotalDocuments() { return totalDocuments; }
    

    protected static class DocumentInfo {
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
}