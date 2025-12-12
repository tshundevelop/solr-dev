import java.io.*;
import java.util.*;

/**
 * TF-IDF分析結果CSVから上位単語を取得するクラス
 */
public class TFIDFWordRanking {
    private Map<String, List<String>> documentTopWords;
    private boolean isLoaded = false;
    
    public TFIDFWordRanking() {
        this.documentTopWords = new HashMap<>();
    }
    
    /**
     * CSVファイルを一度だけ読み込む
     */
    public void ensureLoadedOnce() {
        if (isLoaded) return;
        
        loadFromCSV("data/jaquad_tfidf_ranking_mecab.csv");
        isLoaded = true;
        System.out.println("TF-IDFランキングデータを読み込みました。文書数: " + documentTopWords.size());
    }
    
    /**
     * CSVファイルからTF-IDFランキングを読み込む
     * CSVフォーマット: document_id,title,rank,word,tfidf_score
     */
    private void loadFromCSV(String csvPath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String line = reader.readLine(); // ヘッダー行をスキップ
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    String docId = parts[0].trim();
                    String word = parts[3].trim();
                    
                    // 文書IDごとに単語リストを構築
                    documentTopWords.computeIfAbsent(docId, k -> new ArrayList<>()).add(word);
                }
            }
            
            System.out.println("TF-IDFランキングCSV読み込み完了: " + csvPath);
            
        } catch (IOException e) {
            System.err.println("TF-IDFランキングCSVの読み込みに失敗しました: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 指定された文書IDの上位N個の重要単語を取得
     * @param documentId 文書ID
     * @param topN 上位何件取得するか
     * @return 上位単語のリスト（TF-IDFスコア順）
     */
    public List<String> getTopWords(String documentId, int topN) {
        if (!isLoaded) {
            ensureLoadedOnce();
        }
        
        List<String> words = documentTopWords.get(documentId);
        if (words == null || words.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 上位N個を返す（CSVは既にランク順でソートされている）
        return words.subList(0, Math.min(topN, words.size()));
    }
    
    /**
     * 文書IDからタイトルや質問テキストを推測して単語を取得
     * 文書IDが直接見つからない場合の代替手段
     */
    public List<String> getTopWordsByPattern(String pattern, int topN) {
        if (!isLoaded) {
            ensureLoadedOnce();
        }
        
        // パターンマッチングで類似する文書IDを検索
        for (Map.Entry<String, List<String>> entry : documentTopWords.entrySet()) {
            if (entry.getKey().contains(pattern)) {
                List<String> words = entry.getValue();
                return words.subList(0, Math.min(topN, words.size()));
            }
        }
        
        return new ArrayList<>();
    }
    
    /**
     * デバッグ用：読み込まれたデータの概要を表示
     */
    public void printStats() {
        if (!isLoaded) {
            ensureLoadedOnce();
        }
        
        System.out.println("=== TF-IDFランキング統計 ===");
        System.out.println("総文書数: " + documentTopWords.size());
        
        if (!documentTopWords.isEmpty()) {
            String firstKey = documentTopWords.keySet().iterator().next();
            List<String> firstWords = documentTopWords.get(firstKey);
            System.out.println("サンプル文書ID: " + firstKey);
            System.out.println("サンプル上位単語: " + firstWords.subList(0, Math.min(5, firstWords.size())));
        }
    }
}