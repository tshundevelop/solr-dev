import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class Sample {
	public static void main(String[] args) {
		// 引数があればそれを使う
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Escape process.");
            return;
        }

        keyword = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"});
        System.out.println("Keyword after word split: " + keyword);

		SolrDocumentList results = getKeywordSearchResult("JaQuAD_dev_all", keyword, "id,title,context");

		for (SolrDocument result : results) {
            System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
            System.out.println("title: " + result.getFieldValue("title"));
        }
	}

	public static SolrDocumentList getKeywordSearchResult(String coreName, String keyword, String field) {
		String solrUrl = "http://solr:8983/solr/" + coreName; // docker-composeのsolrサービス名に合わせる
		try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {

			// SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            // 'text_vec' はSolrスキーマで定義したベクトルフィールド名に合わせる
            System.out.println("keyword: " + keyword);
			String[] keywordList = keyword.split(" ");
			if (keywordList.length > 1) {
				keyword = String.join("AND", keywordList);
			}
            // params.set("q", "context:\"" + keyword + "\""); // フレーズ検索
			params.set("q", "context:" + keyword); // 通常のキーワード検索
            params.set("fl", field); // 必要なフィールドを指定
            params.set("rows", 100); // 取得件数を指定

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            // クエリを実行
            QueryResponse response = queryRequest.process(solr);

			SolrDocumentList docs = response.getResults();
			// System.out.println("Found " + docs.getNumFound() + " documents");

			return docs;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}
