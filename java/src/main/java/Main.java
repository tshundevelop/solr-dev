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
    private static final QuestionTokenRanking RANKING = new QuestionTokenRanking();
    
    public static void main(String[] args) {
        config = new Config();

        try {
            // QuestionTokenRankingの初期化（ランキング機能を使用する場合のみ）
            if (config.getRankChoiceWordNumFromTop() > 0) {
                System.out.println("ランキング機能が有効です。データを読み込んでいます...");
                RANKING.ensureLoadedOnce();
            }
            
            // Solrの設定
            String sourceCoreUrl = "http://solr:8983/solr/" + config.getCoreName();
            SolrClient sourceClient = new HttpSolrClient.Builder(sourceCoreUrl).build();

            // 時間計測開始
            long startTime = System.currentTimeMillis();

            // questionフィールドが存在するドキュメントのみ取得
            SolrQuery query = new SolrQuery("question:* AND is_chunk:false");
            query.setRows(config.getNumRows());
            QueryResponse response = sourceClient.query(query);
            SolrDocumentList docs = response.getResults();

            List<LinkedHashMap<String, Object>> evaluationResults = new ArrayList<>();

            DotEnvLoader.load(PROPERTY_FILE, API_KEY_ENV_VAR);
            String apiKey = System.getProperty(API_KEY_ENV_VAR);

            // 各ドキュメントに対して処理
            for (SolrDocument doc : docs) {
                String question = (String) doc.getFirstValue("question");
                String docId = (String) doc.getFirstValue("original_doc_id");
                String title = (String) doc.getFirstValue("title");

                // クエリトークンの決定: ランキング優先設定が有効ならランキングから取得、無効なら従来の分かち書き
                String[] splittedQuestionList;
                if (config.getRankChoiceWordNumFromTop() > 0) {
                    int n = config.getRankChoiceWordNumFromTop();
                    List<String> topTokens = RANKING.getTopTokens(question, n, config.getPartOfSpeech());
                    if (topTokens == null || topTokens.isEmpty()) {
                        // フォールバック: 従来の分かち書き
                        System.out.println("警告: 質問 \"" + question + "\" のランキングデータが見つかりません。従来の分かち書きを使用します。");
                        splittedQuestionList = WordSplitter.getSplittedWords(question, config.getPartOfSpeech(), config.getChoiceWordNumFromTop());
                    } else {
                        System.out.println("ランキングから上位" + topTokens.size() + "トークンを取得: " + String.join(", ", topTokens));
                        splittedQuestionList = topTokens.toArray(new String[0]);
                    }
                } else {
                    splittedQuestionList = WordSplitter.getSplittedWords(question, config.getPartOfSpeech(), config.getChoiceWordNumFromTop());
                }
                String[] paraphraseQuestionList = OpenAIUseLLM.paraphraseTopN(splittedQuestionList, config.getParaphraseWordNumFromTop());
                SolrDocumentList searchResults;
                String searchParams;  // final変数として宣言
                if (config.getType().equals("keyword")) {
                    Object[] resultObj = KeywordSearch.getKeywordSearchResultWithChunkFilter(
                        config.getCoreName(),
                        paraphraseQuestionList,
                        String.join(",", config.getTargetFields()),
                        config.getKeywordTargetField(),
                        config.getFieldSearchMethodType(),
                        config.isChunk()
                    );
                    searchResults = (SolrDocumentList) resultObj[0];
                    searchParams = resultObj[1].toString();
                } else if (config.getType().equals("embedding")) {
                    System.out.println(config.getEmbeddingTargetField());
                    Object[] resultObj = EmbedSearch.getEmbeddingSearchResultWithChunkFilter(
                        config.getCoreName(),
                        paraphraseQuestionList,
                        config.getEmbeddingTargetField(),
                        String.join(",", config.getTargetFields()),
                        apiKey,
                        config.getModelName(),
                        config.isChunk()
                    );
                    searchResults = (SolrDocumentList) resultObj[0];
                    searchParams = resultObj[1].toString();
                } else if (config.getType().equals("hybrid")) {
                    Object[] resultObj = HybridSearch.getHybridSearchResultWithChunkFilter(
                        config.getCoreName(),
                        paraphraseQuestionList,
                        config.getEmbeddingTargetField(),
                        config.getKeywordTargetField(),
                        config.getFieldSearchMethodType(),
                        apiKey,
                        config.getModelName(),
                        config.isChunk()
                    );
                    searchResults = (SolrDocumentList) resultObj[0];
                    searchParams = resultObj[1].toString();
                } else {
                    System.out.println("Unknown evaluation type: " + config.getType());
                    return;
                }

                // 結果から上位K件を取得
                SolrDocumentList slicedSearchResults = Main.sliceSolrDocumentList(searchResults, config.getTopk());

                // Evaluation.javaで評価
                EvaluationResult evalResult = Evaluation.evaluate(slicedSearchResults, question, docId);

                // 結果を保存
                LinkedHashMap<String, Object> resultMap = new LinkedHashMap<String, Object>() {{
                    put("correctId", docId);
                    put("title", title);
                    put("question", question);
                    put("params", searchParams);
                    put("splittedQuestion", splittedQuestionList);
                    put("paraphraseQuestion", paraphraseQuestionList);
                    put("numFound", slicedSearchResults.getNumFound());
                    put("coverage", evalResult.getCoverage());
                    put("mrr", evalResult.getMrr());
                    put("searchResults", slicedSearchResults);
                }};
                evaluationResults.add(resultMap);

                System.out.println("Processed documents: " + evaluationResults.size() + "/" + docs.size());
            }

            // 時間計測終了
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            // ディレクトリ作成
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String dirPath = "Result/" + config.getType() + "/" + config.getResultFolderName() + "/" + timestamp;
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
                put("choiceWordNumFromTop", config.getChoiceWordNumFromTop());
                put("rankChoiceWordNumFromTop", config.getRankChoiceWordNumFromTop());
                put("paraphraseWordNumFromTop", config.getParaphraseWordNumFromTop());
                put("fieldSearchMethodType", config.getFieldSearchMethodType());
                put("isChunk", config.isChunk());
                put("resultFolderName", config.getResultFolderName());
            }};
            LinkedHashMap<String, Object> resultsMap = new LinkedHashMap<String, Object>() {{
                put("totalDocumentsProcessed", evaluationResults.size());
                put("averageCoverage", String.format("%.4f", Evaluation.getAverageCoverage()));
                put("averageMrr", String.format("%.4f", Evaluation.getAverageMrr()));
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

    public static SolrDocumentList sliceSolrDocumentList(SolrDocumentList docs, int topk) {
        SolrDocumentList slicedList = new SolrDocumentList();
        slicedList.setNumFound(Math.min(docs.getNumFound(), topk));
        slicedList.setStart(docs.getStart());
        for (int i = 0; i < Math.min(topk, docs.size()); i++) {
            slicedList.add(docs.get(i));
        }
        return slicedList;
    }
}