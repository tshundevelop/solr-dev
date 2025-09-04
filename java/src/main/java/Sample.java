import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

public class Sample {
	public static void main(String[] args) {
		String keyword = "東大寺の仏像";
		getKeywordSearchResult(keyword);
	}

	private static void getKeywordSearchResult(String keyword) {
		String solrUrl = "http://solr:8983/solr/jaquad_dev_all"; // docker-composeのsolrサービス名に合わせる
		try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
			SolrQuery query = new SolrQuery("context:" + keyword); // クエリを設定
			query.setFields("title"); // 取得するフィールドを指定
			QueryResponse response = solr.query(query);
			SolrDocumentList docs = response.getResults();
			System.out.println("Found " + docs.getNumFound() + " documents");
			for (SolrDocument doc : docs) {
				System.out.println(doc);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
