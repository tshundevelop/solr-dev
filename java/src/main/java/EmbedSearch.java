import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート

import java.util.List;
import java.util.ArrayList;
import java.util.Random;

public class EmbedSearch {

    public static void main(String[] args) {
        // Solr URL: Docker Composeのサービス名に合わせて調整
        String solrUrl = "http://solr:8983/solr/test";

        // SolrClientをtry-with-resourcesで初期化
        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {

            // 1024次元のランダムな浮動小数点数ベクトルを生成
            // float[] queryVector = generateRandomFloatArray(1024);
            double[] rawDoubleVector = {0.1, 0.2, 0.3, 0.4, 0.5};
            System.out.println(rawDoubleVector.length);
            // double[] から float[] への変換とキャスト
            float[] queryVector = new float[rawDoubleVector.length];
            for (int i = 0; i < rawDoubleVector.length; i++) {
                queryVector[i] = (float) rawDoubleVector[i]; // ここで明示的なキャスト
            }

            // float配列をJSON文字列に変換
            String vectorString = floatArrayToJson(queryVector);

            // SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            // {!knn}パーサーをfq（フィルタークエリ）として設定
            // 'text_vec' はSolrスキーマで定義したベクトルフィールド名に合わせる
            params.set("q", "{!knn f=vector topK=5}" + vectorString);
            params.set("fl", "id,score"); // 必要なフィールドを指定

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST); // ここでPOSTメソッドを指定！

            // クエリを実行
            // SolrClient.request()メソッドにQueryRequestオブジェクトを渡す
            QueryResponse response = queryRequest.process(solr);

            // 結果を取得し、表示
            SolrDocumentList docs = response.getResults();
            System.out.println("Found " + docs.getNumFound() + " documents:");
            for (SolrDocument doc : docs) {
                System.out.println("ID: " + doc.getFieldValue("id") + ", Score: " + doc.getFieldValue("score"));
                // 必要に応じて他のフィールドも表示
                // System.out.println("Doc: " + doc);
            }

        } catch (Exception e) {
            System.err.println("Solrとの通信中にエラーが発生しました:");
            e.printStackTrace();
        }
    }

    private static float[] generateRandomFloatArray(int size) {
        Random random = new Random();
        float[] array = new float[size];
        for (int i = 0; i < size; i++) {
            array[i] = random.nextFloat();
        }
        return array;
    }

    private static String floatArrayToJson(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
