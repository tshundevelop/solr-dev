import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BuildQuestionWordSimilarity {
    private static final List<String> DEFAULT_INPUT_FILES = Arrays.asList(
        "jaquad/processed/jaquad_production_792.json",
        "jaquad/processed/jaquad_validation_50.json"
        // "other_file.json"  // 必要に応じて追加
    );
    private static final String OUTPUT_FILE = "data/jaquad_merged_word_sim.json";  // 出力ファイルパス
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

    public static void main(String[] args) {
        try {
            // 設定
            Config config = new Config();
            String[] pos = config.getPartOfSpeech();
            int limit = 0;
            if (limit <= 0) limit = 1000;

            // OpenAI APIキー読込
            Properties property = new Properties();
            property.load(new FileInputStream(PROPERTY_FILE));
            String apiKey = property.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                System.err.println("APIキーが見つかりません。'" + PROPERTY_FILE + "' に '" + API_KEY_ENV_VAR + "' を設定してください。");
                return;
            }

            // 入力ファイルリスト: コマンドライン引数 or デフォルト
            List<String> inputFileNames = args.length > 0 ? Arrays.asList(args) : DEFAULT_INPUT_FILES;
            
            System.out.println("=== Processing " + inputFileNames.size() + " file(s) ===");
            
            // 全ファイルの結果をマージ
            List<Map<String, Object>> allResults = new ArrayList<>();
            ConcurrentMap<String, List<Double>> embedCache = new ConcurrentHashMap<>();
            
            for (String inputFileName : inputFileNames) {
                System.out.println("\n--- Processing: " + inputFileName + " ---");
                List<Map<String, Object>> results = processFile(inputFileName, pos, limit, apiKey, embedCache);
                allResults.addAll(results);
                System.out.println("✓ Processed " + results.size() + " records from " + inputFileName);
            }
            
            // 統合結果を保存
            File outputFile = resolveOutputFile(OUTPUT_FILE);
            if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile, allResults);
            System.out.println("\n=== All files processed successfully ===");
            System.out.println("✓ Saved merged results: " + outputFile.getAbsolutePath() + " (" + allResults.size() + " records)");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static List<Map<String, Object>> processFile(
            String inputFileName, 
            String[] pos, 
            int limit, 
            String apiKey,
            ConcurrentMap<String, List<Double>> embedCache) throws Exception {
        // 入力パス解決（docker実行時もローカル実行時も対応）
        File inputFile = resolveExistingFile(new String[] {"data", "../data", "/app/data", "."}, inputFileName);
        if (inputFile == null) {
            System.err.println("入力ファイルが見つかりません: " + inputFileName + "\n候補ディレクトリ: data, ../data, /app/data, .");
            return Collections.emptyList();
        }
        
        System.out.println("Found input file: " + inputFile.getAbsolutePath());

        // 入力読込
        List<Map<String, Object>> docs = MAPPER.readValue(
            inputFile, new TypeReference<List<Map<String, Object>>>() {}
        );

        List<Map<String, Object>> out = new ArrayList<>();

            int idx = 0;
            for (Map<String, Object> doc : docs) {
                idx++;
                String question = asString(doc.get("question"));
                if (question == null || question.trim().isEmpty()) {
                    continue;
                }

                // 分かち書き（品詞付き）
                List<String[]> wordsWithPos = WordSplitter.getWordsWithPos(question, pos, limit);

                // 埋め込み
                List<Double> qVec = getOrCreateEmbedding(embedCache, question, apiKey);
                if (qVec == null || qVec.isEmpty()) {
                    System.err.println("質問の埋め込み生成に失敗: index=" + idx);
                    continue;
                }

                List<Map<String, Object>> tokenInfos = new ArrayList<>();
                for (String[] wp : wordsWithPos) {
                    String w = wp[0];
                    String p = wp[1];
                    List<Double> wVec = getOrCreateEmbedding(embedCache, w, apiKey);
                    if (wVec == null || wVec.isEmpty()) continue;
                    double sim = cosine(qVec, wVec);
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("word", w);
                    t.put("pos", p);
                    t.put("similarity", sim);
                    tokenInfos.add(t);
                }

                // 類似度降順でソート
                tokenInfos.sort((a, b) -> Double.compare((double)b.get("similarity"), (double)a.get("similarity")));

                // 出力オブジェクト作成（元フィールド + 追加情報）
                Map<String, Object> od = new LinkedHashMap<>(doc);
                od.put("questionTokens", tokenInfos);
                out.add(od);

                System.out.println("Processed: " + idx + "/" + docs.size());
            }

            return out;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0.0 || nb == 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static List<Double> getOrCreateEmbedding(ConcurrentMap<String, List<Double>> cache, String text, String apiKey) {
        return cache.computeIfAbsent(text, t -> {
            try {
                return OpenAIEmbeddingClient.getEmbeddingFromOpenAI(t, apiKey);
            } catch (Exception e) {
                System.err.println("OpenAI埋め込み生成に失敗: " + e.getMessage());
                return null;
            }
        });
    }

    private static File resolveExistingFile(String[] dirs, String fileName) {
        for (String d : dirs) {
            File f = new File(d, fileName);
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }
    
    private static File resolveOutputFile(String outputPath) {
        // 出力ファイルパスを解決（存在チェックなし、書き込み用）
        File f = new File(outputPath);
        if (!f.isAbsolute()) {
            // 相対パスの場合、複数の候補から探す
            for (String baseDir : new String[]{".", "..", "/app"}) {
                File candidate = new File(baseDir, outputPath);
                File parent = candidate.getParentFile();
                if (parent != null && (parent.exists() || parent.mkdirs())) {
                    return candidate;
                }
            }
        }
        return f;
    }
}
