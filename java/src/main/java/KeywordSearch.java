import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;

public class KeywordSearch {
    
    public static void main(String[] args) {
        // 引数があればそれを使う
        String keyword;
        String coreName;
        try {
            keyword = args[1];
            coreName = args[0];
        } catch (Exception e) {
            System.out.println("Not found. Kyeword and coreName. Stopping...");
            return;
        }

        String[] keywordList = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"}, 3);

        // is_chunk = false のドキュメントを検索
        Object[] resultsNonChunkObj = getKeywordSearchResultWithChunkFilter(
            coreName,
            keywordList,
            "id,original_doc_id,title,context,score",
            "context",
            "OR",
            false
        );

        System.out.println("\n=== Results for is_chunk=false ===");
        SolrDocumentList resultsNonChunk = (SolrDocumentList) resultsNonChunkObj[0];
        resultsNonChunk = Main.sliceSolrDocumentList(resultsNonChunk, 10);
        if (resultsNonChunk.size() == 0) {
            System.out.println("No results found for is_chunk=false.");
        } else{
            for (SolrDocument result : resultsNonChunk) {
                System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                System.out.println("title: " + result.getFieldValue("title"));
            }
        }

        // is_chunk = true のドキュメントを検索
        Object[] resultsChunkObj = getKeywordSearchResultWithChunkFilter(
            coreName,
            keywordList,
            "id,original_doc_id,title,context,score",
            "context",
            "OR",
            true
        );

        System.out.println("\n=== Results for is_chunk=true ===");
        SolrDocumentList resultsChunk = (SolrDocumentList) resultsChunkObj[0];
        resultsChunk = Main.sliceSolrDocumentList(resultsChunk, 10);
        if (resultsChunk.size() == 0) {
            System.out.println("No results found for is_chunk=true.");
        } else {
            for (SolrDocument result : resultsChunk) {
                System.out.println("Chunk ID: " + result.getFieldValue("id") + ", Original Doc ID: " + result.getFieldValue("original_doc_id") + ", Score: " + result.getFieldValue("score"));
                System.out.println("title: " + result.getFieldValue("title"));
            }
        }
    }

    public static Object[] getKeywordSearchResultWithChunkFilter(
        String coreName,
        String[] keywordList,
        String field,
        String targetField,
        String fieldSearchMethodType,
        boolean isChunk
    ) {
        String solrUrl = "http://solr:8983/solr/" + coreName;
        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {

            ModifiableSolrParams params = new ModifiableSolrParams();

            // is_chunkフィルタを追加
            String filterQuery = "is_chunk:" + isChunk;

            params.set("q.op", fieldSearchMethodType);
            params.set("q", targetField + ":" + String.join(",", keywordList));
            params.set("fq", filterQuery); // フィルタクエリでis_chunkを指定
            params.set("fl", field);
            params.set("rows", 10000);

            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            QueryResponse response = queryRequest.process(solr);

            SolrDocumentList docs = response.getResults();

            return new Object[]{docs, params};
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
