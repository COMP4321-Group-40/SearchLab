import java.lang.reflect.Array;
import java.util.Vector;
import java.net.URL;
import java.util.ArrayList;

import org.jsoup.Connection;

public class Crawler
{
	private String url = "http://www.cs.ust.hk/~dlee/4321/";
	ArrayList<String> visited;

	Crawler(String _url)
	{
		url = _url;
		visited = new ArrayList<String>();
	}
	private static void crawl(int level)
	{
		
	}

	private static Document request(String url){
		try{
			Connection con = Jsoup.connect(url);
		}
	}
	
	public static void main (String[] args)
	{
		// crawl(1);
	}
}

	
