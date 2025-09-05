import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート

import java.util.List;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class HybridSearch {
    public static void main(String[] args) {
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Using default keyword.");
            return;
        }

        SolrDocumentList results = getHybrideSearchResult(keyword);

        try {
            FileWriter file = new FileWriter("hybrid.txt");
            BufferedWriter bw = new BufferedWriter(file);
            PrintWriter pw = new PrintWriter(bw);
            for (SolrDocument result : results) {
                pw.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                pw.println("title: " + result.getFieldValue("title"));
                pw.println("context: " + result.getFieldValue("context").toString().replace("\n\n", ""));
                pw.println();
            }
            pw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // for (SolrDocument result : results) {
        //     System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
        //     System.out.println("title: " + result.getFieldValue("title"));
        // }
    }

    private static SolrDocumentList getHybrideSearchResult(String keyword) {
        String solrUrl = "http://solr:8983/solr/JaQuAD_dev_all";

        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            List<Double> embedding = EmbeddingClient.getEmbeddingFromPython(keyword);
            if (embedding != null) {
                System.out.println("Embedding size: " + embedding.size());
                // 必要に応じてembeddingを使った処理を記述
            }

            double[] rawDoubleVector = embedding.stream().mapToDouble(Double::doubleValue).toArray();
            // double[] から float[] への変換とキャスト
            float[] queryVector = new float[rawDoubleVector.length];
            for (int i = 0; i < rawDoubleVector.length; i++) {
                queryVector[i] = (float) rawDoubleVector[i]; // ここで明示的なキャスト
            }

            // float配列をJSON文字列に変換
            String vectorString = EmbedSearch.floatArrayToJson(queryVector);

            // SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            // 'text_vec' はSolrスキーマで定義したベクトルフィールド名に合わせる
            String[] keywordList = keyword.split(" ");
			if (keywordList.length > 1) {
				keyword = String.join(" AND ", keywordList);
			}
            params.set("q", "{!knn f=context_vec topK=20}" + vectorString);
            params.set("sort", "exists(query(qq)) desc");
            params.set("sort", "score desc");
            // params.set("qq", "context:\"" + keyword + "\"");
            params.set("qq", "context:" + keyword); // キーワード検索のクエリを設定
            params.set("fl", "id,score,title,context");

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            // クエリを実行
            // SolrClient.request()メソッドにQueryRequestオブジェクトを渡す
            QueryResponse response = queryRequest.process(solr);

            // 結果を取得し、表示
            SolrDocumentList docs = response.getResults();
            System.out.println("Found " + docs.getNumFound() + " documents:");
            return docs;
        } catch (Exception e) {
            System.err.println("Solrとの通信中にエラーが発生しました:");
            e.printStackTrace();
            return null;
        }
    }
}
