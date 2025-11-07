import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BuildQuestionWordSimilarity {
    private static final String INPUT_FILE_NAME = "JaQuAD_dev_all.json";
    private static final String OUTPUT_FILE_NAME = "JaQuAD_dev_all_word_sim.json";
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

        // 入力パス解決（docker実行時もローカル実行時も対応）
        File inputFile = resolveExistingFile(new String[] {"data", "../data", "/app/data", "."}, INPUT_FILE_NAME);
        if (inputFile == null) {
        System.err.println("入力ファイルが見つかりません: " + INPUT_FILE_NAME + "\n候補ディレクトリ: data, ../data, /app/data, .");
        return;
        }

        // 入力読込
        List<Map<String, Object>> docs = MAPPER.readValue(
            inputFile, new TypeReference<List<Map<String, Object>>>() {}
        );

            List<Map<String, Object>> out = new ArrayList<>();

            // 簡易メモリキャッシュ（同一テキストの再計算回避）
            ConcurrentMap<String, List<Double>> embedCache = new ConcurrentHashMap<>();

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

            // 保存（入力ファイルと同じディレクトリに保存）
            File outputFile = new File(inputFile.getParentFile(), OUTPUT_FILE_NAME);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputFile, out);
            System.out.println("Saved: " + outputFile.getAbsolutePath() + " (" + out.size() + " records)");
        } catch (Exception e) {
            e.printStackTrace();
        }
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
}
