import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import java.util.Properties;
import java.io.FileInputStream;

public class HybridSearch {
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    private static final String EMBEDDING_MODEL = "text-embedding-3-large";
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
            Properties property = new Properties();
            property.load(new FileInputStream(PROPERTY_FILE));
            apiKey = property.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null) {
                System.err.println("APIキーが見つかりません。");
                apiKey = "DUMMY_API_KEY"; 
            }
        } catch (Exception e) {
            System.err.println("エラー: APIキーのロード中に問題が発生しました: " + e.getMessage());
            return;
        }

        String[] keywordList = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"}, 2);
        System.out.println("Keyword after word split: " + String.join(", ", keywordList));

        try {
            // is_chunk = false のドキュメントを検索
            Object[] resultsNonChunkObj = getHybridSearchResultWithChunkFilter(
                "validation2000",
                keywordList,
                "context_vector",
                "context",
                "AND",
                apiKey,
                EMBEDDING_MODEL,
                false
            );

            System.out.println("\n=== Results for is_chunk=false ===");
            SolrDocumentList resultsNonChunk = (SolrDocumentList) resultsNonChunkObj[0];
            resultsNonChunk = Main.sliceSolrDocumentList(resultsNonChunk, 10);
            if (resultsNonChunk != null) {
                for (SolrDocument result : resultsNonChunk) {
                    System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                    System.out.println("title: " + result.getFieldValue("title"));
                }
            }

            // is_chunk = true のドキュメントを検索
            Object[] resultsChunkObj = getHybridSearchResultWithChunkFilter(
                "validation2000",
                keywordList,
                "chunk_vector",
                "context",
                "AND",
                apiKey,
                EMBEDDING_MODEL,
                true
            );

            System.out.println("\n=== Results for is_chunk=true ===");
            SolrDocumentList resultsChunk = (SolrDocumentList) resultsChunkObj[0];
            resultsChunk = Main.sliceSolrDocumentList(resultsChunk, 10);
            if (resultsChunk != null) {
                for (SolrDocument result : resultsChunk) {
                    System.out.println("Chunk ID: " + result.getFieldValue("id") + ", Original Doc ID: " + result.getFieldValue("original_doc_id") + ", Score: " + result.getFieldValue("score"));
                    System.out.println("title: " + result.getFieldValue("title"));
                }
            }
        } catch (Exception e) {
            System.err.println("ハイブリッド検索中にエラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * is_chunkフィールドでフィルタリングしてハイブリッド検索を実行
     * 
     * @param coreName Solrコア名
     * @param keywordList 検索キーワードリスト
     * @param field ベクトルフィールド名
     * @param targetField 検索対象フィールド名
     * @param fieldSearchMethodType フィールド検索メソッドタイプ
     * @param apiKey OpenAI APIキー
     * @param modelName モデル名
     * @param isChunk is_chunkフィルタ値（true/false）
     * @return 検索結果
     */
    public static Object[] getHybridSearchResultWithChunkFilter(
        String coreName,
        String[] keywordList,
        String field,
        String targetField,
        String fieldSearchMethodType,
        String apiKey,
        String modelName,
        boolean isChunk
    ) throws Exception {
        String solrUrl = "http://solr:8983/solr/" + coreName;

        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            // 埋め込みベクトル取得
            String keyword = String.join(" ", keywordList);
            float[] queryVector = EmbedSearch.getOrCreateEmbedding(keyword, field, apiKey, modelName);
            String vectorString = EmbedSearch.floatArrayToJson(queryVector);

            // is_chunkフィルタを追加
            String filterQuery = "is_chunk:" + isChunk;

            // ハイブリッドSolrクエリ実行
            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q.op", fieldSearchMethodType);
            params.set("q", String.format("{!knn f=%s topK=10000}%s", field, vectorString));
            params.set("qf", field);
            params.set("rq", "{!rerank reRankQuery=$rqq reRankDocs=10000 reRankWeight=1.0 reRankScale=0-1}");
            params.set("rqq", targetField + ":" + String.join(",", keywordList));
            params.set("fq", filterQuery); // フィルタクエリでis_chunkを指定
            params.set("fl", "id,original_doc_id,score,title,context");

            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);
            QueryResponse response = queryRequest.process(solr);
            SolrDocumentList docs = response.getResults();
            
            return new Object[]{docs, params};
        } catch (Exception e) {
            System.err.println("ハイブリッド検索中にエラー: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
