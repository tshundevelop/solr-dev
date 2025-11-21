import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;

/**
 * 事前計算された質問ごとの単語ランキング(JSON)を読み込み、
 * 質問に対応する上位トークンを返すユーティリティ。
 * ファイルは以下の候補から探索する:
 *  - data/JaQuAD_dev_all_word_sim.json
 *  - ../data/JaQuAD_dev_all_word_sim.json
 *  - /app/data/JaQuAD_dev_all_word_sim.json
 *  - ./JaQuAD_dev_all_word_sim.json
 */
public class QuestionTokenRanking {
    private static final String FILE_NAME = "jaquad_merged_word_sim.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // question -> List<TokenInfo>
    private final Map<String, List<TokenInfo>> index = new HashMap<>();
    private boolean loaded = false;
    private boolean loadTried = false;

    public static class TokenInfo {
        public final String word;
        public final String pos;
        public final double similarity;
        public TokenInfo(String w, String p, double s) { this.word = w; this.pos = p; this.similarity = s; }
    }

    public synchronized void ensureLoadedOnce() {
        if (loaded || loadTried) return;
        loadTried = true;
        System.out.println("[QuestionTokenRanking] データファイルを探しています: " + FILE_NAME);
        try {
            File f = resolveExistingFile(new String[]{"data", "../data", "/app/data", "."}, FILE_NAME);
            if (f == null) {
                System.err.println("[QuestionTokenRanking] エラー: ファイルが見つかりません: " + FILE_NAME);
                System.err.println("[QuestionTokenRanking] 以下のパスを確認しました: data/, ../data/, /app/data/, ./");
                return;
            }
            System.out.println("[QuestionTokenRanking] ファイル発見: " + f.getAbsolutePath());
            List<Map<String, Object>> docs = MAPPER.readValue(
                    f,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
            System.out.println("[QuestionTokenRanking] JSON読み込み完了: " + docs.size() + " 件のドキュメント");
            for (Map<String, Object> doc : docs) {
                Object qObj = doc.get("question");
                if (qObj == null) continue;
                String q = String.valueOf(qObj);
                Object tokensObj = doc.get("questionTokens");
                if (!(tokensObj instanceof List)) continue;
                List<?> rawList = (List<?>) tokensObj;
                List<TokenInfo> list = new ArrayList<>();
                for (Object o : rawList) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    String w = m.get("word") == null ? null : String.valueOf(m.get("word"));
                    String p = m.get("pos") == null ? null : String.valueOf(m.get("pos"));
                    double s = 0.0;
                    Object so = m.get("similarity");
                    if (so instanceof Number) s = ((Number) so).doubleValue();
                    if (w != null && p != null) {
                        list.add(new TokenInfo(w, p, s));
                    }
                }
                // 既に降順で入っているはずだが安全のためソート
                list.sort((a, b) -> Double.compare(b.similarity, a.similarity));
                index.put(q, list);
            }
            loaded = true;
            System.out.println("[QuestionTokenRanking] Loaded index: " + index.size() + " questions from " + f.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[QuestionTokenRanking] 読み込みに失敗: " + e.getMessage());
        }
    }

    public List<String> getTopTokens(String question, int n, String[] allowPos) {
        ensureLoadedOnce();
        if (!loaded) {
            System.err.println("[QuestionTokenRanking] データが読み込まれていません。");
            return Collections.emptyList();
        }
        List<TokenInfo> list = index.get(question);
        if (list == null || list.isEmpty()) {
            System.err.println("[QuestionTokenRanking] 警告: 質問 \"" + question + "\" のランキングデータが見つかりません。");
            System.err.println("[QuestionTokenRanking] インデックスには " + index.size() + " 件の質問が登録されています。");
            return Collections.emptyList();
        }
        Set<String> posFilter = allowPos == null ? null : new HashSet<>(Arrays.asList(allowPos));
        List<String> out = new ArrayList<>();
        for (TokenInfo t : list) {
            if (posFilter != null && !posFilter.contains(t.pos)) continue;
            out.add(t.word);
            if (out.size() >= n) break;
        }
        return out;
    }

    private static File resolveExistingFile(String[] dirs, String fileName) {
        for (String d : dirs) {
            File f = new File(d, fileName);
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }
}
