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

public class EmbedSearch {

    public static void main(String[] args) {
        // 引数があればそれを使う
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Using default keyword.");
            return;
        }

        keyword = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"});
        System.out.println("Keyword after word split: " + keyword);

        SolrDocumentList results = getEmbeddingSearchResult("JaQuAD_dev_all", keyword);

        // try {
        //     FileWriter file = new FileWriter("embeddding.txt");
        //     BufferedWriter bw = new BufferedWriter(file);
        //     PrintWriter pw = new PrintWriter(bw);
        //     for (SolrDocument result : results) {
        //         pw.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
        //         pw.println("title: " + result.getFieldValue("title"));
        //         pw.println("context: " + result.getFieldValue("context").toString().replace("\n\n", ""));
        //         pw.println();
        //     }
        //     pw.close();
        // } catch (IOException e) {
        //     e.printStackTrace();
        // }
        for (SolrDocument result : results) {
            System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
            System.out.println("title: " + result.getFieldValue("title"));
        }
    }

    private static SolrDocumentList getEmbeddingSearchResult(String coreName, String keyword) {
        // Solr URL: Docker Composeのサービス名に合わせて調整
        String solrUrl = "http://solr:8983/solr/" + coreName;

        // SolrClientをtry-with-resourcesで初期化
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
            String vectorString = floatArrayToJson(queryVector);

            // SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            // 'text_vec' はSolrスキーマで定義したベクトルフィールド名に合わせる
            params.set("q", "{!knn f=context_vec topK=20}" + vectorString);
            params.set("fl", "id,score,title,context"); // 必要なフィールドを指定

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            // クエリを実行
            // SolrClient.request()メソッドにQueryRequestオブジェクトを渡す
            QueryResponse response = queryRequest.process(solr);

            // 結果を取得し、表示
            SolrDocumentList docs = response.getResults();
            System.out.println("Found " + docs.getNumFound() + " documents:");
            // for (SolrDocument doc : docs) {
            //     System.out.println("ID: " + doc.getFieldValue("id") + ", Score: " + doc.getFieldValue("score"));
            //     System.out.println("title: " + doc.getFieldValue("title"));
            // }
            return docs;
        } catch (Exception e) {
            System.err.println("Solrとの通信中にエラーが発生しました:");
            e.printStackTrace();
            return null;
        }
    }

    public static String floatArrayToJson(float[] array) {
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
