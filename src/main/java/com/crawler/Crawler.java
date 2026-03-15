package com.crawler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
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

	private final StopStem stopStem; 
	private final InvertedIndex invertedIndex;
	
	private final String userAgent = "Mozilla/5.0 (compatible; AcademicCrawler/1.0)";

	private final int timeout = 10000;
    private int pagesCrawled;

	public Crawler(StopStem stopStem, InvertedIndex invertedIndex) {
        this.stopStem = stopStem;
        this.invertedIndex = invertedIndex;

        pagesCrawled = 0;
    }
    
    public void crawl(String url, int maxPages) {
        // Main web crawler loop 
        // Does a BFS search starting from the current url

        visited.add(url);
        pagesCrawled++;
        
        if (pagesCrawled < maxPages) {
            try {
                logger.info("Crawling ({}/{}): {}", pagesCrawled, maxPages, url);
                
                // Try to request a valid connection and document from the page
                Connection con = getConnection(url);
                Connection.Response res = con.execute();
                
                if (res.statusCode() == 200){
                    // If the connection is valid, we retrieve its information
                    Document doc = res.parse();
                    if (doc != null) {
                        processPage(url, doc);

                        // Recursion on URLs
                        Elements links = doc.select("a[href]");
        
                        for (Element link : links) {
                            String nextlink = link.absUrl("href");
                            
                            if (isValidUrl(nextlink) && !visited.contains(nextlink)) {
                                logger.debug("Found new link: {}", nextlink);
                                crawl(nextlink,maxPages); // Recurse
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error crawling at {}: {}",  url, e.getMessage());
            }
        }
    }

    private Connection getConnection(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeout)
                .followRedirects(true);
    }
    
    private void processPage(String url, Document document) {
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
        invertedIndex.addDocument(url, title, titleStems, bodyStems);
        
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
}