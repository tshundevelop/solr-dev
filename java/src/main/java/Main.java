import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

public class Main {
    private static Config config;
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    private static final TFIDFWordRanking TFIDF_RANKING = new TFIDFWordRanking();
    
    public static void main(String[] args) {
        config = new Config();
        
        // 実行する検索タイプのリスト（Configから取得）
        String[] searchTypes = config.getSearchTypes();
        
        for (String searchType : searchTypes) {
            try {
                System.out.println("\n======================================");
                System.out.println("🔍 " + searchType.toUpperCase() + " 検索評価を開始します");
                System.out.println("======================================");
                
                // 検索タイプを設定
                config.setType(searchType);
                
                // 各検索タイプの評価を実行
                boolean success = runEvaluationForType(searchType);
                
                if (success) {
                    System.out.println("✅ " + searchType.toUpperCase() + " 検索評価が正常に完了しました");
                } else {
                    System.err.println("❌ " + searchType.toUpperCase() + " 検索評価でエラーが発生しました");
                }
                
                // 次の評価の前に少し待機
                Thread.sleep(2000);
                
            } catch (Exception e) {
                System.err.println("❌ " + searchType.toUpperCase() + " 検索評価でエラー: " + e.getMessage());
                e.printStackTrace();
                // エラーが発生しても次の検索タイプに継続
                continue;
            }
        }
        
        System.out.println("\n🎉 全ての検索タイプの評価が完了しました！");
    }
    
    /**
     * 指定された検索タイプで評価を実行
     * @param searchType 検索タイプ（keyword, embedding, hybrid）
     * @return 成功したかどうか
     */
    private static boolean runEvaluationForType(String searchType) {
        try {
            System.out.println("\n📊 " + searchType + " 検索評価を実行中...");
            
            // JSONから正解データを読み込み
            System.out.println("正解データをJSONから読み込んでいます: " + config.getGroundTruthJsonPath());
            ObjectMapper mapper = new ObjectMapper();
            
            List<Map<String, Object>> dataList;
            try {
                // パターン1: {"data": [...]} 形式を試す
                Map<String, Object> jsonData = mapper.readValue(
                    new File(config.getGroundTruthJsonPath()),
                    new TypeReference<Map<String, Object>>() {}
                );
                dataList = (List<Map<String, Object>>) jsonData.get("data");
            } catch (Exception e) {
                // パターン2: [...] 配列形式を試す
                dataList = mapper.readValue(
                    new File(config.getGroundTruthJsonPath()),
                    new TypeReference<List<Map<String, Object>>>() {}
                );
            }
            System.out.println("✅ " + dataList.size() + "件のデータを読み込みました");
            
            // クエリ生成方法の表示
            if (config.isUseOriginalQuery()) {
                System.out.println("🔍 クエリ生成: 元の文章をそのまま使用");
            } else {
                System.out.println("🔍 クエリ生成: 分かち書き + 品詞フィルタ (" + 
                    String.join(", ", config.getQueryPartOfSpeech()) + ")");
            }

            // 時間計測開始
            long startTime = System.currentTimeMillis();

            List<LinkedHashMap<String, Object>> evaluationResults = new ArrayList<>();

            DotEnvLoader.load(PROPERTY_FILE, API_KEY_ENV_VAR);
            String apiKey = System.getProperty(API_KEY_ENV_VAR);

            // 並列処理用のスレッドプール
            int threadPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
            System.out.println("Using thread pool with " + threadPoolSize + " threads for parallel search");
            
            try {
                // バッチサイズ（例: 20件ずつ処理）
                int batchSize = 10;
                int totalDocs = Math.min(dataList.size(), config.getNumRows());
                
                for (int batchStart = 0; batchStart < totalDocs; batchStart += batchSize) {
                    int batchEnd = Math.min(batchStart + batchSize, totalDocs);
                    System.out.println("\n=== Processing batch [" + (batchStart+1) + "-" + batchEnd + "] ===");
                    
                    // Phase 1: 検索タスクを並列投入
                    List<Future<LinkedHashMap<String, Object>>> futures = new ArrayList<>();
                    
                    for (int i = batchStart; i < batchEnd; i++) {
                        final Map<String, Object> dataItem = dataList.get(i);
                        final int index = i;
                        final int finalTotalDocs = totalDocs;
                        
                        Future<LinkedHashMap<String, Object>> future = executorService.submit(new Callable<LinkedHashMap<String, Object>>() {
                            @Override
                            public LinkedHashMap<String, Object> call() throws Exception {
                                final String question = (String) dataItem.get("question");
                                final String rawDocId = (String) dataItem.get("id");
                                final String title = (String) dataItem.get("title");

                                // ID形式を変換: de-001-00-000 → de-001
                                final String docId = convertDocId(rawDocId);
                                
                                // クエリ生成: 設定に応じて元の文章 or 分かち書き
                                final String[] queryTokens;
                                if (config.isUseOriginalQuery()) {
                                    // 元の文章をそのまま使用
                                    queryTokens = new String[]{question};
                                } else {
                                    // 分かち書き + 品詞フィルタ
                                    queryTokens = WordSplitter.getSplittedWords(
                                        question, 
                                        config.getQueryPartOfSpeech(), 
                                        config.getChoiceWordNumFromTop()
                                    );
                                }
                                final String[] paraphraseQuestionList = OpenAIUseLLM.paraphraseTopN(queryTokens, config.getParaphraseWordNumFromTop());
                                
                                // 検索実行
                                SolrDocumentList searchResults;
                                String searchParams;
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
                                    throw new Exception("Unknown evaluation type: " + config.getType());
                                }

                                // 結果から上位K件を取得
                                final SolrDocumentList slicedSearchResults = Main.sliceSolrDocumentList(searchResults, config.getTopk());

                                // Evaluation.javaで評価
                                final EvaluationResult evalResult = Evaluation.evaluate(slicedSearchResults, question, docId);

                                // final変数として再定義
                                final SolrDocumentList finalSlicedSearchResults = slicedSearchResults;
                                final String finalSearchParams = searchParams;
                                final String[] finalQueryTokens = queryTokens;

                                // 結果を保存
                                LinkedHashMap<String, Object> resultMap = new LinkedHashMap<String, Object>() {{
                                    put("index", index + 1);
                                    put("correctId", docId);
                                    put("rawId", rawDocId);
                                    put("title", title);
                                    put("question", question);
                                    put("params", finalSearchParams);
                                    put("splittedQuestion", finalQueryTokens);
                                    put("paraphraseQuestion", paraphraseQuestionList);
                                    put("numFound", finalSlicedSearchResults.getNumFound());
                                    put("coverage", evalResult.getCoverage());
                                    put("mrr", evalResult.getMrr());
                                    put("searchResults", finalSlicedSearchResults);
                                }};
                                
                                return resultMap;
                            }
                        });
                        
                        futures.add(future);
                    }
                    
                    System.out.println("  → Submitted " + futures.size() + " search tasks");
                    
                    // Phase 2: 結果を収集（順序保持）
                    System.out.println("\n📊 結果取得中...");
                    for (int i = 0; i < futures.size(); i++) {
                        LinkedHashMap<String, Object> resultMap = futures.get(i).get();
                        evaluationResults.add(resultMap);
                        
                        // 結果表示
                        int idx = (int) resultMap.get("index");
                        String question = (String) resultMap.get("question");
                        String docId = (String) resultMap.get("correctId");
                        String rawId = (String) resultMap.get("rawId");
                        String[] tokens = (String[]) resultMap.get("splittedQuestion");
                        double coverage = (double) resultMap.get("coverage");
                        double mrr = (double) resultMap.get("mrr");
                        
                        String queryPreview = config.isUseOriginalQuery() 
                            ? question.substring(0, Math.min(40, question.length())) + "..."
                            : String.join(", ", tokens);
                        
                        System.out.println(String.format(
                            "  [%3d/%3d] %s → %s | Cov: %.2f | MRR: %.2f | %s",
                            idx, totalDocs, rawId, docId, coverage, mrr, queryPreview
                        ));
                    }
                    
                    System.out.println("  ✅ Processed documents: " + evaluationResults.size() + "/" + totalDocs);
                }
            } finally {
                executorService.shutdown();
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
                System.out.println("📄 結果を保存しました: " + resultsPath);
            } catch (Exception e) {
                System.err.println("Failed to save results.json: " + e.getMessage());
                e.printStackTrace();
                return false;
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
                System.out.println("📊 ステータスを保存しました: " + statusPath);
            } catch (Exception e) {
                System.err.println("Failed to save status.json: " + e.getMessage());
                e.printStackTrace();
                return false;
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
                System.out.println("📋 サマリーを保存しました: " + summaryPath);
            } catch (Exception e) {
                System.err.println("Failed to save summary.json: " + e.getMessage());
                e.printStackTrace();
                return false;
            }

            System.out.println("✅ " + searchType + " 検索評価が正常に完了しました（処理時間: " + duration + "ms）");
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ " + searchType + " 検索評価でエラー: " + e.getMessage());
            e.printStackTrace();
            return false;
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

    /**
     * ID形式を変換: de-001-00-000 → de-001
     * @param rawId 元のID（例: de-001-00-000）
     * @return 変換後のID（例: de-001）
     */
    private static String convertDocId(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return rawId;
        }
        
        // パターン: prefix-XXX-YY-ZZZ → prefix-XXX
        String[] parts = rawId.split("-");
        if (parts.length >= 2) {
            // 最初の2つの部分を結合
            return parts[0] + "-" + parts[1];
        }
        
        // 変換できない場合はそのまま返す
        return rawId;
    }
}