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
        
        // 実行する検索タイプのリスト
        // String[] searchTypes = {"keyword", "embedding", "hybrid"};
        String[] searchTypes = {"embedding", "hybrid"};
        
        for (String searchType : searchTypes) {
            try {
                System.out.println("\n======================================");
                System.out.println("🔍 " + searchType.toUpperCase() + " 検索評価を開始します");
                System.out.println("======================================");
                
                // 検索タイプを設定
                config.setType(searchType);
                config.setResultFolderName(searchType + "_tfidf_evaluation");
                
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
            
            // TF-IDFランキングの初期化（設定値が0より大きい場合）
            if (config.getRankChoiceWordNumFromTop() > 0) {
                System.out.println("TF-IDFランキング機能が有効です。データを読み込んでいます...");
                TFIDF_RANKING.ensureLoadedOnce();
                if (searchType.equals("keyword")) { // 最初の実行時のみ統計表示
                    TFIDF_RANKING.printStats();
                }
            } else {
                System.out.println("従来の分かち書き機能を使用します。");
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

            // 並列処理用のスレッドプール
            int threadPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
            System.out.println("Using thread pool with " + threadPoolSize + " threads for parallel search");
            
            try {
                // バッチサイズ（例: 20件ずつ処理）
                int batchSize = 20;
                int totalDocs = docs.size();
                
                for (int batchStart = 0; batchStart < totalDocs; batchStart += batchSize) {
                    int batchEnd = Math.min(batchStart + batchSize, totalDocs);
                    System.out.println("\n=== Processing batch [" + (batchStart+1) + "-" + batchEnd + "] ===");
                    
                    // Phase 1: 検索タスクを並列投入
                    List<Future<LinkedHashMap<String, Object>>> futures = new ArrayList<>();
                    
                    for (int i = batchStart; i < batchEnd; i++) {
                        final SolrDocument doc = docs.get(i);
                        final int index = i;
                        final int finalTotalDocs = totalDocs;
                        
                        Future<LinkedHashMap<String, Object>> future = executorService.submit(new Callable<LinkedHashMap<String, Object>>() {
                            @Override
                            public LinkedHashMap<String, Object> call() throws Exception {
                                final String question = (String) doc.getFirstValue("question");
                                final String docId = (String) doc.getFirstValue("original_doc_id");
                                final String title = (String) doc.getFirstValue("title");

                                // クエリトークンの決定: TF-IDFランキング優先設定が有効ならTF-IDFから取得、無効なら従来の分かち書き
                                final String[] splittedQuestionList;
                                if (config.getRankChoiceWordNumFromTop() > 0) {
                                    int n = config.getRankChoiceWordNumFromTop();
                                    List<String> topWords = TFIDF_RANKING.getTopWords(docId, n);
                                    if (topWords == null || topWords.isEmpty()) {
                                        // フォールバック: 従来の分かち書き
                                        System.out.println("警告: 文書ID \"" + docId + "\" のTF-IDFランキングデータが見つかりません。従来の分かち書きを使用します。");
                                        splittedQuestionList = WordSplitter.getSplittedWords(question, config.getPartOfSpeech(), config.getChoiceWordNumFromTop());
                                    } else {
                                        System.out.println("[" + (index+1) + "/" + finalTotalDocs + "] TF-IDFから上位" + topWords.size() + "語を取得: " + String.join(", ", topWords));
                                        splittedQuestionList = topWords.toArray(new String[0]);
                                    }
                                } else {
                                    splittedQuestionList = WordSplitter.getSplittedWords(question, config.getPartOfSpeech(), config.getChoiceWordNumFromTop());
                                }
                                final String[] paraphraseQuestionList = OpenAIUseLLM.paraphraseTopN(splittedQuestionList, config.getParaphraseWordNumFromTop());
                                
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

                                // 結果を保存
                                LinkedHashMap<String, Object> resultMap = new LinkedHashMap<String, Object>() {{
                                    put("correctId", docId);
                                    put("title", title);
                                    put("question", question);
                                    put("params", finalSearchParams);
                                    put("splittedQuestion", splittedQuestionList);
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
                    for (Future<LinkedHashMap<String, Object>> future : futures) {
                        LinkedHashMap<String, Object> resultMap = future.get();
                        evaluationResults.add(resultMap);
                    }
                    
                    System.out.println("  ✓ Processed documents: " + evaluationResults.size() + "/" + totalDocs);
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
}