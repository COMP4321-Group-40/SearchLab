package com.crawler;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the web crawler application.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        System.out.println("=================================");
        System.out.println("  Web Crawler with Search Index  ");
        System.out.println("=================================");
        
        try {
            // Initialize components
            StopStem stopStem = new StopStem();
            InvertedIndex invertedIndex = new InvertedIndex();
            SearchEngine searchEngine = new SearchEngine(invertedIndex, stopStem);
            Crawler crawler = new Crawler(stopStem, invertedIndex);
            
            // Get starting URL
            Scanner scanner = new Scanner(System.in);
            System.out.print("\nEnter starting URL (or press Enter for default): ");
            String startUrl = scanner.nextLine().trim();
            
            System.out.print("Enter maximum pages to crawl (default: 30): ");
            String maxPagesInput = scanner.nextLine().trim();
            int maxPages = maxPagesInput.isEmpty() ? 30 : Integer.parseInt(maxPagesInput);
            
                
            // Start crawling
            crawler.crawl(startUrl, maxPages);
            searchEngine.computeNorms();
            
            // Display results
            displayResults(invertedIndex, crawler);

            writeSpiderReport(invertedIndex);
            
            // Search interface
            runSearchInterface(scanner, searchEngine);
            
            scanner.close();
            
        } catch (Exception e) {
            logger.error("Application error: ", e);
            System.err.println("An error occurred: " + e.getMessage());
        }
    }
    
    private static void displayResults(InvertedIndex invertedIndex, Crawler crawler) {
        System.out.println("\n=================================");
        System.out.println("           CRAWL SUMMARY         ");
        System.out.println("=================================");
        System.out.println("Pages crawled: " + crawler.getCrawledUrls().size());
        System.out.println("Unique body stems: " + invertedIndex.getWordCount());
        System.out.println("Total word occurrences: " + invertedIndex.getTotalOccurrences());
        
        // Show top 10 most frequent words
        System.out.println("\nTop 10 most frequent words:");
        invertedIndex.getTopWords(10).forEach((word, count) -> 
            System.out.println("  " + word + ": " + count + " occurrences"));
    }
    
    private static void runSearchInterface(Scanner scanner, SearchEngine searchEngine) {
        System.out.println("\n=================================");
        System.out.println("         SEARCH INTERFACE        ");
        System.out.println("=================================");
        System.out.println("Enter words to search (or 'quit' to exit)");
        
        while (true) {
            System.out.print("\nSearch: ");
            String query = scanner.nextLine().trim();
            
            if (query.equalsIgnoreCase("quit") || query.equalsIgnoreCase("exit")) {
                break;
            }
            
            if (!query.isEmpty()) {
                boolean isPhrase = query.contains(" ");
                
                List<SearchEngine.SearchResult> results;
                if (isPhrase) {
                    // Phrase search in both title and body
                    results = searchEngine.search(query, true, true);
                    System.out.println("Performing phrase search...");
                } else {
                    // Single term search in both
                    results = searchEngine.search(query, true, true);
                }
                
                if (results.isEmpty()) {
                    System.out.println("No results found for '" + query + "'");
                } else {
                    System.out.println("Found " + results.size() + " result(s):");
                    for (int i = 0; i < Math.min(50, results.size()); i++) {
                        SearchEngine.SearchResult result = results.get(i);
                        System.out.println((i + 1) + ". " + result.getUrl() + 
                                         " (score: " + String.format("%.3f", result.getScore()) + ")");
                        System.out.println("   Title: " + result.getTitle());
                    }
                }
            }
        }
    }

    private static void writeSpiderReport(InvertedIndex index) {
        String filename = "spider_result.txt";
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(filename))) {
            Set<String> urls = index.getDocuments().keySet();
            for (String url : urls) {
                // Get document info
                InvertedIndex.DocumentInfo docInfo = index.getDocuments().get(url);
                String title = docInfo.getTitle();
                int pageSize = docInfo.getBodyLength();
                String lastMod = index.getFormattedLastModified(url);
                
                // Get top 10 keywords with frequencies
                Map<String, Integer> keywordFreq = computeKeywordFrequencies(index, url);
                List<Map.Entry<String, Integer>> topKeywords = keywordFreq.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .limit(10)
                        .collect(java.util.stream.Collectors.toList());
                
                // Get child links (up to 10)
                List<String> childLinks = index.getChildUrls(url, 10);
                
                // Write to file
                out.println(title);
                out.println(url);
                out.println(lastMod + ", " + pageSize);
                // Keyword line: keyword1 freq1; keyword2 freq2; ...
                if (!topKeywords.isEmpty()) {
                    StringBuilder kwLine = new StringBuilder();
                    for (Map.Entry<String, Integer> entry : topKeywords) {
                        kwLine.append(entry.getKey()).append(" ").append(entry.getValue()).append("; ");
                    }
                    out.println(kwLine.toString().trim());
                } else {
                    out.println();
                }
                // Child links
                if (!childLinks.isEmpty()) {
                    for (String childUrl : childLinks) {
                        out.println(childUrl);
                    }
                }
                out.println(); // blank line between pages
            }
            System.out.println("Report written to " + filename);
        } catch (java.io.IOException e) {
            System.err.println("Error writing report: " + e.getMessage());
        }
    }

    private static Map<String, Integer> computeKeywordFrequencies(InvertedIndex index, String url) {
        Map<String, Integer> freq = new java.util.HashMap<>();
        // Iterate over all terms in body index
        for (Map.Entry<String, Map<String, List<Integer>>> termEntry : index.getBodyIndex().entrySet()) {
            String term = termEntry.getKey();
            Map<String, List<Integer>> postings = termEntry.getValue();
            List<Integer> positions = postings.get(url);
            if (positions != null) {
                freq.put(term, positions.size());
            }
        }

        // Iterate over all terms in title index
        for (Map.Entry<String, Map<String, List<Integer>>> termEntry : index.getTitleIndex().entrySet()) {
            String term = termEntry.getKey();
            Map<String, List<Integer>> postings = termEntry.getValue();
            List<Integer> positions = postings.get(url);
            if (positions != null) {
                freq.merge(term, positions.size(), Integer::sum);
            }
        }
        return freq;
    }
}