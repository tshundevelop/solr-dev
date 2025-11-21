import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート

import java.util.List;
import java.util.ArrayList;

public class KeywordSearch_old {
    private static Config config = new Config();
	public static void main(String[] args) {
		// 引数があればそれを使う
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Using default keyword 'Solr vector search'.");
            keyword = "芥川賞を受賞した人は誰ですか。";
        }

        String[] keywordList = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"}, 2);

		SolrDocumentList results = getKeywordSearchResult(
            "JaQuAD_dev_all",
            keywordList,
            "id,title,context,score",
            "context",
            "AND"
        );

        // 結果から上位K件を取得
        results = Main.sliceSolrDocumentList(results, config.getTopk());

		for (SolrDocument result : results) {
            System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
            System.out.println("title: " + result.getFieldValue("title"));
        }
	}

	public static SolrDocumentList getKeywordSearchResult(
        String coreName,
        String[] keywordList,
        String field,
        String targetField,
        String fieldSearchMethodType
    ) {
		String solrUrl = "http://solr:8983/solr/" + coreName; // docker-composeのsolrサービス名に合わせる
		try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {

			// SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            String keyword = "";
            if (keywordList.length > 1) {
                // 各単語にフィールド名とコロンを前置する
                // 例: "keyword1" -> "context:keyword1"
                List<String> fieldQualifiedKeywords = new ArrayList<>();
                for (int i = 0; i < keywordList.length; i++) {
                    fieldQualifiedKeywords.add(targetField + ":" + keywordList[i]);
                }

                // 3. フィールド指定された単語を ' AND ' で結合
                System.out.println("Field qualified keywords: " + String.join(", ", fieldQualifiedKeywords));
                keyword = String.join(String.format(" %s ", fieldSearchMethodType), fieldQualifiedKeywords);
            } else if (keywordList.length == 1) {
                keyword = targetField + ":" + keywordList[0];
            }
            System.out.println("Final keyword for query: " + keyword);

            params.set("q.op", fieldSearchMethodType); // 検索方法の指定（AND/OR）
            params.set("q", keyword);
            params.set("fl", field); // 必要なフィールドを指定
            params.set("rows", 10000); // 取得件数を指定

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            // クエリを実行
            QueryResponse response = queryRequest.process(solr);

			SolrDocumentList docs = response.getResults();

			return docs;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}
