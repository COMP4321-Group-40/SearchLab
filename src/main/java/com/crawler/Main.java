package com.crawler;

import java.util.List;
import java.util.Scanner;

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
            
            // Display results
            displayResults(invertedIndex, crawler);
            
            // Search interface
            runSearchInterface(scanner, invertedIndex);

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
        System.out.println("Unique body stemsd: " + invertedIndex.getWordCount());
        System.out.println("Total word occurrences: " + invertedIndex.getTotalOccurrences());
        
        // Show top 10 most frequent words
        System.out.println("\nTop 10 most frequent words:");
        invertedIndex.getTopWords(10).forEach((word, count) -> 
            System.out.println("  " + word + ": " + count + " occurrences"));
    }
    
    private static void runSearchInterface(Scanner scanner, InvertedIndex invertedIndex) {
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
                
                List<InvertedIndex.SearchResult> results;
                if (isPhrase) {
                    // Phrase search in both title and body
                    results = invertedIndex.searchPhrase(query, true, true);
                    System.out.println("Performing phrase search...");
                } else {
                    // Single term search in both
                    results = invertedIndex.search(query, true, true);
                }
                
                if (results.isEmpty()) {
                    System.out.println("No results found for '" + query + "'");
                } else {
                    System.out.println("Found " + results.size() + " result(s):");
                    for (int i = 0; i < Math.min(5, results.size()); i++) {
                        InvertedIndex.SearchResult result = results.get(i);
                        System.out.println((i + 1) + ". " + result.getUrl() + 
                                         " (score: " + String.format("%.3f", result.getScore()) + ")");
                        System.out.println("   Title: " + result.getTitle());
                    }
                }
            }
        }
    }
}