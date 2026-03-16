package com.crawler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.crawler.IRUtilities.Porter;

public class StopStem {
    // Stopword removal and stemming
    private static final Logger logger = LoggerFactory.getLogger(StopStem.class);
    
    private final Set<String> stopWords = new HashSet<>();
    private final Porter stemmer;
    
    public StopStem() {
        this.stemmer = new Porter();
        loadStopWords();
    }
    
    private void loadStopWords() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("stopwords.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty()) {
                    stopWords.add(word);
                }
            }
            logger.info("Loaded {} stop words", stopWords.size());
            
        } catch (IOException | NullPointerException e) {
            logger.error("Failed to load stop words file", e);
            addDefaultStopWords();
        }
    }
    
    private void addDefaultStopWords() {
        // Fallback if the fail can't be loaded, a smaller set of words
        String[] defaults = {"a", "an", "and", "are", "as", "at", "be", "by", "for", 
                            "from", "has", "he", "in", "is", "it", "its", "of", "on", 
                            "that", "the", "to", "was", "were", "will", "with"};
        for (String word : defaults) {
            stopWords.add(word);
        }
        logger.info("Added {} default stop words", stopWords.size());
    }
    
    public List<StemPosition> process(String text){
        // Process the text and return a list of stems with positions
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Split into words, remove punctuation, and convert to lowercase
        String[] words = text.toLowerCase()
                .replaceAll("[^a-zA-Z\\s]", " ")
                .split("\\s+");
        
        List<StemPosition> result = new ArrayList<>();
        int position = 0;
        
        for (String word : words) {
            if (!word.isEmpty() && !isStopWord(word)) {
                String stemmed = stemmer.stripAffixes(word);
                if(!stemmed.isEmpty()){
                    result.add(new StemPosition(stemmed, position));
                }
            }
            position++;
        }
        
        return result;
    }

    public String processWord(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        word = word.toLowerCase().replaceAll("[^a-zA-Z]", "");
        if (word.isEmpty() || isStopWord(word)) {
            return "";
        }
        return stemmer.stripAffixes(word);
    }
    
    private boolean isStopWord(String word) {
        return stopWords.contains(word) || word.length() < 2;
    }
    
    public Set<String> getStopWords() {
        return new HashSet<>(stopWords);
    }

    public static class StemPosition {
        public final String stem;
        public final int position;
        
        public StemPosition(String stem, int position) {
            this.stem = stem;
            this.position = position;
        }
    }
}