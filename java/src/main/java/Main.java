import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

public class Main {
    private static Config config;
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    
    public static void main(String[] args) {
        config = new Config();

        try {
            // Solrの設定
            String sourceCoreUrl = "http://solr:8983/solr/JaQuAD_dev_all";
            SolrClient sourceClient = new HttpSolrClient.Builder(sourceCoreUrl).build();

            // 時間計測開始
            long startTime = System.currentTimeMillis();

            // 全ドキュメント取得
            SolrQuery query = new SolrQuery("*:*");
            query.setRows(config.getNumRows());
            QueryResponse response = sourceClient.query(query);
            SolrDocumentList docs = response.getResults();

            List<LinkedHashMap<String, Object>> evaluationResults = new ArrayList<>();

            DotEnvLoader.load(PROPERTY_FILE, API_KEY_ENV_VAR);
            String apiKey = System.getProperty(API_KEY_ENV_VAR);

            // 各ドキュメントに対して処理
            for (SolrDocument doc : docs) {
                String question = (String) doc.getFirstValue("question");
                String docId = (String) doc.getFirstValue("id");

                // Sample.javaの検索メソッドを呼び出し
                String splittedQuestion = WordSplitter.getSplittedWords(question, new String[]{"名詞", "動詞", "形容詞"});
                SolrDocumentList searchResults;
                if (config.getType().equals("keyword")) {
                    searchResults = Keyword.getKeywordSearchResult(
                        config.getCoreName(),
                        splittedQuestion,
                        String.join(",", config.getTargetFields()),
                        config.getKeywordTargetField(),
                        config.getTopk()
                    );
                } else if (config.getType().equals("embedding")) {
                    searchResults = EmbedSearch.getEmbeddingSearchResult(
                        config.getCoreName(),
                        splittedQuestion,
                        config.getEmbeddingTargetField(),
                        apiKey,
                        config.getTopk(),
                        config.getModelName()
                    );
                } else if (config.getType().equals("hybrid")) {
                    searchResults = HybridSearch.getHybrideSearchResult(
                        config.getCoreName(),
                        splittedQuestion,
                        config.getEmbeddingTargetField(),
                        apiKey,
                        config.getTopk(),
                        config.getModelName()
                    );
                } else {
                    System.out.println("Unknown evaluation type: " + config.getType());
                    return;
                }

                // Evaluation.javaで評価
                EvaluationResult evalResult = Evaluation.evaluate(searchResults, question, docId);

                // 結果を保存
                LinkedHashMap<String, Object> resultMap = new LinkedHashMap<String, Object>() {{
                    put("correctId", docId);
                    put("question", question);
                    put("splittedQuestion", splittedQuestion);
                    put("numFound", searchResults.getNumFound());
                    put("coverage", evalResult.getCoverage());
                    put("mrr", evalResult.getMrr());
                    put("lrap", evalResult.getLrap());
                    put("averageMrrAndLrap", evalResult.getAverageMrrAndLrap());
                    put("searchResults", searchResults);
                }};
                evaluationResults.add(resultMap);

                System.out.println("Processed documents: " + evaluationResults.size() + "/" + docs.size());
            }

            // 時間計測終了
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // ディレクトリ作成
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String dirPath = "Result/" + config.getType() + "/" + timestamp;
            new File(dirPath).mkdirs();

            // Jackson ObjectMapperの初期化
            ObjectMapper objectMapper = new ObjectMapper();

            // 結果のJSON保存
            String resultsPath = dirPath + "/results.json";
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(resultsPath), evaluationResults);
            } catch (Exception e) {
                System.err.println("Failed to save results.json: " + e.getMessage());
                e.printStackTrace();
            }

            // 正誤状態IDのJSON保存
            String statusPath = dirPath + "/status.json";
            LinkedHashMap<String, List<String>> statusMap = new LinkedHashMap<String, List<String>>() {{
                put("correct", new ArrayList<>());
                put("incorrect", new ArrayList<>());
            }};
            for (LinkedHashMap<String, Object> evaluationResult : evaluationResults) {
                String correctId = (String) evaluationResult.get("correctId");
                double coverage = ((Number) evaluationResult.get("coverage")).doubleValue();

                if (coverage == 1.0) {
                    statusMap.get("correct").add(correctId);
                } else {
                    statusMap.get("incorrect").add(correctId);
                }
            }
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(statusPath), statusMap);
            } catch (Exception e) {
                System.err.println("Failed to save status.json: " + e.getMessage());
                e.printStackTrace();
            }

            // サマリーの保存
            String summaryPath = dirPath + "/summary.json";
            LinkedHashMap<String, Object> configMap = new LinkedHashMap<String, Object>() {{
                put("solrCore", config.getCoreName());
                put("evaluationType", config.getType());
                put("topk", config.getTopk());
                put("numberOfDocuments", config.getNumRows());
                put("mainTargetField", config.getKeywordTargetField());
                put("targetFields", config.getTargetFields());
                put("partOfSpeech", config.getPartOfSpeech());
            }};
            LinkedHashMap<String, Object> resultsMap = new LinkedHashMap<String, Object>() {{
                put("totalDocumentsProcessed", evaluationResults.size());
                put("averageCoverage", String.format("%.4f", Evaluation.getAverageCoverage()));
                put("averageMrr", String.format("%.4f", Evaluation.getAverageMrr()));
                put("averageLrap", String.format("%.4f", Evaluation.getAverageLrap()));
                put("averageMrrAndLrap", String.format("%.4f", Evaluation.getAverageMrrAndLrap()));
            }};
            LinkedHashMap<String, Object> summaryMap = new LinkedHashMap<String, Object>() {{
                put("data", timestamp);
                put("durationMs", duration);
                put("configuration", configMap);
                put("results", resultsMap);
            }};
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(summaryPath), summaryMap);
            } catch (Exception e) {
                System.err.println("Failed to save summary.json: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}