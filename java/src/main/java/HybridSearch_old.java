import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート

import org.apache.solr.client.solrj.util.ClientUtils;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.io.FileInputStream;

public class HybridSearch_old {
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; // EmbedSearchと合わせる
    private static final String PROPERTY_FILE = "api_key.env";

    public static void main(String[] args) {
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            keyword = "芥川賞を受賞した人は誰ですか。";
        }

        String apiKey = "";
        try {
            // 設定ファイルを読み取る処理
			Properties property = new Properties();
			property.load(new FileInputStream(PROPERTY_FILE));
            // ... (apiKey取得ロジックは変更なし。DotEnvLoaderが別途必要) ...
            apiKey = property.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null) {
                System.err.println("APIキーが見つかりません。DotEnvLoaderが実行されているか確認してください。");
                apiKey = "DUMMY_API_KEY"; 
            }
        } catch (Exception e) {
            System.err.println("エラー: APIキーのロード中に問題が発生しました: " + e.getMessage());
            return;
        }

        String[] keywordList = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"}, 2);
        System.out.println("Keyword after word split: " + String.join(", ", keywordList));

        try {
            SolrDocumentList results = getHybrideSearchResult(
                "JaQuAD_dev_all",
                keywordList,
                "context_vec_from_openai",
                apiKey,
                EMBEDDING_MODEL // EmbedSearchと合わせる
            );

            results = Main.sliceSolrDocumentList(results, 10);

            for (SolrDocument result : results) {
                System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                System.out.println("title: " + result.getFieldValue("title"));
            }
        } catch (Exception e) {
            System.err.println("ハイブリッド検索中にエラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static SolrDocumentList getHybrideSearchResult(
        String coreName,
        String[] keywordList,
        String field,
        String apiKey,
        String modelName
    ) throws Exception {
        String solrUrl = "http://solr:8983/solr/" + coreName;

        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            // --- 1. EmbedSearch の正式キャッシュロジックを利用して埋め込み取得 ---
            String keyword = String.join(" ", keywordList);
            float[] queryVector = EmbedSearch_old.getOrCreateEmbedding(keyword, field, apiKey, modelName);
            String vectorString = EmbedSearch_old.floatArrayToJson(queryVector);

            // --- 2. キーワードqq構築 (Keyword.java のロジック参考) ---
            String query = buildKeywordQuery(keyword, "context");

            // --- 3. ハイブリッドSolrクエリ実行 ---
            ModifiableSolrParams params = new ModifiableSolrParams();
            // params.set("q", String.format("{!knn f=%s topK=%d}", field, topk) + vectorString);
            // params.set("sort", "exists(query(qq)) desc");
            // params.set("sort", "score desc");
            // params.set("qq", query);
            // params.set("fl", "id,score,title,context");

            // params.set("q", query);
            // params.set("defType", "edismax");
            // params.set("qf", "context");
            // params.set("bq", String.format("{!knn f=%s topK=10000}%s", field, vectorString));
            // params.set("fl", "id,score,title,context");

            // params.set("q", query);
            // params.set("qf", "context");
            // params.set("rq", "{!rerank reRankQuery=$rqq reRankDocs=10000 reRankWeight=1.0 reRankScale=0-1}");
            // params.set("rqq", String.format("{!knn f=%s topK=10000}%s", field, vectorString));
            // params.set("fl", "id,score,title,context");

            params.set("q", String.format("{!knn f=%s topK=10000}%s", field, vectorString));
            params.set("qf", "context_vec_from_openai");
            params.set("rq", "{!rerank reRankQuery=$rqq reRankDocs=10000 reRankWeight=1.0 reRankScale=0-1}");
            params.set("rqq", query);
            params.set("fl", "id,score,title,context");

            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);
            QueryResponse response = queryRequest.process(solr);
            return response.getResults();
        } catch (Exception e) {
            System.err.println("ハイブリッド検索中にエラー: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static String buildKeywordQuery(String keyword, String field) {
        // 形態素後のキーワードは既にスペース区切り想定
        String escaped = ClientUtils.escapeQueryChars(keyword);
        List<String> escapeChars = Arrays.asList("『", "』", "\\\\", "?』", "?(", "-", "", "/", "~", "!", "@", "#", "$", "%", "^", "&", "*", "+", "=", "|", "\\", ":", ";", "\"", "'", "<", ">", ",", ".", "?", "`", "!』", "(", ")", "「", ")(", ")") ;
        for (String c : escapeChars) {
            if (!c.isEmpty()) {
                escaped = escaped.replace(c, "");
            }
        }
        String[] parts = escaped.split(" ");
        List<String> fieldQualified = new ArrayList<>();
        for (String p : parts) {
            // if (p.isBlank()) continue;
            fieldQualified.add(field + ":" + p);
        }
        if (fieldQualified.isEmpty()) {
            return field + ":" + escaped; // フォールバック
        }
        return String.join(" AND ", fieldQualified);
    }
}
