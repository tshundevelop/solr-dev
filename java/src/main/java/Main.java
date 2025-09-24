import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileWriter;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class Main {
    private static Config config;
    
    public static void main(String[] args) {
        config = new Config();

        try {
            // Solrの設定
            String sourceCoreUrl = "http://solr:8983/solr/JaQuAD_dev_all";
            SolrClient sourceClient = new HttpSolrClient.Builder(sourceCoreUrl).build();

            // 全ドキュメント取得
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(10);
            QueryResponse response = sourceClient.query(query);
            SolrDocumentList docs = response.getResults();

            List<Map<String, Object>> evaluationResults = new ArrayList<>();
            double totalScore = 0.0;
            int totalDocs = 0;

            // 各ドキュメントに対して処理
            for (SolrDocument doc : docs) {
                String question = (String) doc.getFirstValue("question");
                String docId = (String) doc.getFirstValue("id");

                // Sample.javaの検索メソッドを呼び出し
                String splittedQuestion = WordSplitter.getSplittedWords(question, new String[]{"名詞", "動詞", "形容詞"});
                SolrDocumentList searchResults;
                if (config.getType().equals("keyword")) {
                    searchResults = Sample.getKeywordSearchResult("JaQuAD_dev_all", splittedQuestion, "id,title,context");
                } else if (config.getType().equals("embedding")) {
                    searchResults = EmbedSearch.getEmbeddingSearchResult("JaQuAD_dev_all", splittedQuestion);
                } else {
                    System.out.println("Unknown evaluation type: " + config.getType());
                    return;
                }
                System.out.println("Found " + searchResults.getNumFound() + " documents:");

                // Evaluation.javaで評価
                EvaluationResult evalResult = Evaluation.evaluate(searchResults, question, docId);

                // 結果を保存
                Map<String, Object> result = new HashMap<>();
                result.put("correctId", docId);
                result.put("question", question);
                result.put("splittedQuestion", splittedQuestion);
                result.put("searchResults", searchResults);
                result.put("numFound", searchResults.getNumFound());
                result.put("score", evalResult.getScore());
                evaluationResults.add(result);

                totalScore += evalResult.getScore();
                totalDocs++;
            }

            // ディレクトリ作成
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String dirPath = "Result/" + config.getType() + "/" + timestamp;
            new File(dirPath).mkdirs();

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            // 結果のJSON保存
            String resultsPath = dirPath + "/results.json";
            try (FileWriter writer = new FileWriter(resultsPath)) {
                gson.toJson(evaluationResults, writer);
            }

            // サマリーの保存
            String summaryPath = dirPath + "/summary.txt";
            try (FileWriter writer = new FileWriter(summaryPath)) {
                writer.write("=== Evaluation Summary ===\n");
                writer.write("Date: " + timestamp + "\n");
                writer.write("\n=== Configuration ===\n");
                writer.write("Solr Core: " + config.getCoreName() + "\n");
                writer.write("Evaluation Type: " + config.getType() + "\n");
                writer.write("Number of Documents: " + config.getNumRows() + "\n");
                writer.write("Target Fields: " + String.join(", ", config.getTargetFields()) + "\n");
                writer.write("Part of Speech: " + String.join(", ", config.getPartOfSpeech()) + "\n");
                writer.write("\n=== Results ===\n");
                writer.write("Total Documents Processed: " + totalDocs + "\n");
                writer.write("Average Score: " + String.format("%.4f", totalScore / totalDocs) + "\n");
            }

            System.out.println("Results saved to: " + resultsPath);
            System.out.println("Summary saved to: " + summaryPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}