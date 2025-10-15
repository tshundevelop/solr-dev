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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class HybridSearch {
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; // EmbedSearchと合わせる

    public static void main(String[] args) {
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            keyword = "第5回の芥川賞を受賞した人は誰ですか。";
        }

        String apiKey = "";
        try {
            // ... (apiKey取得ロジックは変更なし。DotEnvLoaderが別途必要) ...
            apiKey = System.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null) {
                System.err.println("APIキーが見つかりません。DotEnvLoaderが実行されているか確認してください。");
                apiKey = "DUMMY_API_KEY"; 
            }
        } catch (Exception e) {
            System.err.println("エラー: APIキーのロード中に問題が発生しました: " + e.getMessage());
            return;
        }

        keyword = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"});
        System.out.println("Keyword after word split: " + keyword);

        try {
            SolrDocumentList results = getHybrideSearchResult(
                "JaQuAD_dev_all",
                keyword,
                "context_vec_from_openai",
                apiKey,
                10,
                EMBEDDING_MODEL // EmbedSearchと合わせる
            );

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
        String keyword,
        String field,
        String apiKey,
        Integer topk,
        String modelName
    ) throws Exception {
        String solrUrl = "http://solr:8983/solr/" + coreName;

        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            // --- 1. EmbedSearch の正式キャッシュロジックを利用して埋め込み取得 ---
            float[] queryVector = EmbedSearch.getOrCreateEmbedding(keyword, field, apiKey, modelName);
            String vectorString = EmbedSearch.floatArrayToJson(queryVector);

            // --- 2. キーワードqq構築 (Keyword.java のロジック参考) ---
            String qq = buildKeywordQuery(keyword, "context");

            // --- 3. ハイブリッドSolrクエリ実行 ---
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", String.format("{!knn f=%s topK=%d}", field, topk) + vectorString);
            params.set("sort", "exists(query(qq)) desc");
            params.set("sort", "score desc");
            params.set("qq", qq);
            params.set("fl", "id,score,title,context");
            // スコア融合のためのsortやcustom query parser利用が可能であればここで設定

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
        return String.join(" OR ", fieldQualified);
    }
}
