package com.crawler;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Crawler
{
    private static final Logger logger = LoggerFactory.getLogger(Crawler.class); // Common Logging

	private final Set<String> visited = ConcurrentHashMap.newKeySet(); // Hash Table
    private final Queue<String> urlQueue = new LinkedList<>(); // BFS Queue

	private final StopStem stopStem; 
	private final InvertedIndex invertedIndex;
	
	private final String userAgent = "Mozilla/5.0 (compatible; AcademicCrawler/1.0)";

	private final int timeout = 10000;

	public Crawler(StopStem stopStem, InvertedIndex invertedIndex) {
        this.stopStem = stopStem;
        this.invertedIndex = invertedIndex;
    }
    
    public void crawl(String url, int maxPages) {
        // Main web crawler loop 
        urlQueue.clear();
        visited.clear();
        
        invertedIndex.getPageId((url));
        urlQueue.add(url);
        visited.add(url);

        int pagesCrawled = 0;
        
        while (!urlQueue.isEmpty() && pagesCrawled < maxPages) {
            String currenturl = urlQueue.poll();
            try {
                if(!shouldFetchPage(currenturl)){
                    logger.info("Skipping (not modified): {}", currenturl);
                    continue;
                }

                logger.info("Crawling ({}/{}): {}", pagesCrawled + 1, maxPages, currenturl);
                
                // Try to request a valid connection and document from the page
                Connection con = getConnection(currenturl);
                Connection.Response res = con.execute();
                
                if (res.statusCode() == 200){
                    // If the connection is valid, we retrieve its information
                    FetchResult fetchResult = fetchDocumentHeader(currenturl);
                    if (fetchResult != null) {
                        processPage(currenturl, fetchResult.document, fetchResult.lastModified);

                        Elements links = fetchResult.document.select("a[href]");
        
                        for (Element link : links) {
                            String nextlink = link.absUrl("href");
                            
                            if (isValidUrl(nextlink)) {
                                invertedIndex.addLinkRelation(currenturl, nextlink);

                                if(!visited.contains(nextlink)) {
                                    visited.add(nextlink);
                                    urlQueue.add(nextlink);
                                    logger.debug("Found new link: {}", nextlink);
                                }
                            }
                        }
                        pagesCrawled++;
                    }
                }
            } catch (Exception e) {
                logger.error("Error crawling at {}: {}",  url, e.getMessage());
            }
        }
    }

    private boolean shouldFetchPage(String url) {
        if (!invertedIndex.containsUrl((url))) {
            return true;
        }

        Long lastModifiedStored = invertedIndex.getLastModified(url);
        if (lastModifiedStored == null){
            return true;
        }

        Long lastModifiedServer = getLastModifiedServer(url);

        if (lastModifiedServer == null) {
            return true;
        }

        return lastModifiedServer > lastModifiedStored;
    }

    private Long getLastModifiedServer(String url) {
        try {
            Connection con = getConnection(url);
            Connection.Response res = con.execute();

            if (res.statusCode() != 200) {
                return null;
            }
            
            String lastModifiedHeader = res.header("Last-Modified");
            if (lastModifiedHeader != null) {
                try {
                    return java.time.ZonedDateTime.parse(lastModifiedHeader, 
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli();
                } catch (Exception e) {
                    return null;
                }
            }
        } catch (IOException e) {
            logger.debug("HEAD request failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    private Connection getConnection(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true);
    }

    private FetchResult fetchDocumentHeader(String url) throws IOException {
        Connection con = getConnection(url);
        Connection.Response res = con.execute();

        if (res.statusCode() != 200) {
            return null;
        }
        Document doc = res.parse();

        long lastModified = System.currentTimeMillis();
        String lastModifiedHeader = res.header("Last-Modified");
        if (lastModifiedHeader != null){
                        try {
                lastModified = java.time.ZonedDateTime.parse(lastModifiedHeader, 
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
            } catch (Exception e) {
                logger.warn("Failed to parse Last-Modified for {}: {}", url, lastModifiedHeader);
            }
        }
        return new FetchResult(doc, lastModified);
    }
    
    private void processPage(String url, Document document, long lastModified) {
        String title = document.title();
        if (title.isEmpty()) {
            title = "No title";
        }
        
        // Extract title text and process
        List<StopStem.StemPosition> titleStems = stopStem.process(title);
        
        // Extract body text and process
        String bodyText = document.body().text();
        List<StopStem.StemPosition> bodyStems = stopStem.process(bodyText);
        
        // Add to inverted index
        invertedIndex.addDocument(url, title, titleStems, bodyStems, lastModified);
        
        logger.debug("Indexed: {} - {} words", title, bodyStems.size(), titleStems.size());
    }
    
    private boolean isValidUrl(String url) {
        // Simple match for proper URL format check
        return url != null && 
               !url.isEmpty() && 
               url.startsWith("http") &&
               !url.contains("#") &&
               !url.matches(".*\\.(pdf|jpg|jpeg|png|gif|css|js|ico|xml|json|zip|tar|gz)$") &&
               !url.contains("javascript:") &&
               !url.contains("mailto:");
    }
    
    public Set<String> getCrawledUrls() {
        return Collections.unmodifiableSet(visited);
    }

    private static class FetchResult {
        final Document document;
        final long lastModified;
        
        FetchResult(Document document, long lastModified) {
            this.document = document;
            this.lastModified = lastModified;
        }
    }
}