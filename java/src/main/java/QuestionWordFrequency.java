import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * JaQuADデータから質問を形態素解析し、タイトル別に名詞の単語頻度を集計
 */
public class QuestionWordFrequency {
    
    private static final String[] INPUT_FILES = {
        "../data/jaquad/jaquad_train_31748.json",
        "../data/jaquad/jaquad_validation_3939.json"
    };
    
    private static final String OUTPUT_DIR = "data/";
    private static final String OUTPUT_FILE = "question_word_frequency.json";
    private static final int TOP_N_PER_TITLE = 20;  // タイトルごとの上位N件
    private static final int TOP_N_GLOBAL = 100;    // 全体の上位N件
    
    public static void main(String[] args) {
        try {
            // タイトル別の単語情報を保存するマップ
            // Map<title, Map<word, WordInfo>>
            Map<String, Map<String, WordInfo>> titleWordFrequency = new LinkedHashMap<>();
            
            ObjectMapper objectMapper = new ObjectMapper();
            Tokenizer tokenizer = new Tokenizer();
            
            int totalQuestions = 0;
            int totalTitles = 0;
            
            // 各ファイルを処理
            for (String filePath : INPUT_FILES) {
                System.out.println("\n=== Processing: " + filePath + " ===");
                
                File file = new File(filePath);
                if (!file.exists()) {
                    System.out.println("⚠ File not found: " + filePath);
                    continue;
                }
                
                JsonNode rootNode = objectMapper.readTree(file);
                if (!rootNode.isArray()) {
                    System.out.println("⚠ Expected JSON array at root");
                    continue;
                }
                
                int fileQuestionCount = 0;
                
                // 各レコードを処理
                for (JsonNode record : rootNode) {
                    String title = record.get("title").asText();
                    String question = record.get("question").asText();
                    
                    // タイトルごとの単語情報マップを取得または作成
                    Map<String, WordInfo> wordInfoMap = titleWordFrequency.computeIfAbsent(
                        title, k -> new HashMap<>()
                    );
                    
                    // 質問を形態素解析して名詞のみ抽出
                    List<Token> tokens = tokenizer.tokenize(question);
                    for (Token token : tokens) {
                        String[] features = token.getAllFeatures().split(",");
                        String pos = features[0]; // 品詞
                        String posDetail = features.length > 1 ? features[1] : "不明"; // 品詞細分類1
                        
                        // 名詞のみを対象
                        if ("名詞".equals(pos)) {
                            String word = token.getSurface();
                            // 不要な記号や空白を除外
                            if (isValidWord(word)) {
                                WordInfo info = wordInfoMap.getOrDefault(word, new WordInfo(word, posDetail));
                                info.incrementFrequency();
                                wordInfoMap.put(word, info);
                            }
                        }
                    }
                    
                    fileQuestionCount++;
                    totalQuestions++;
                }
                
                System.out.println("  Processed questions: " + fileQuestionCount);
            }
            
            totalTitles = titleWordFrequency.size();
            
            // 結果を表示
            System.out.println("\n" + repeat("=", 80));
            System.out.println("=== 集計結果 ===");
            System.out.println(repeat("=", 80));
            System.out.println("総質問数: " + totalQuestions);
            System.out.println("総タイトル数: " + totalTitles);
            System.out.println();
            
            // タイトルごとに頻度の高い順でソートして表示
            int titleCount = 0;
            for (Map.Entry<String, Map<String, WordInfo>> entry : titleWordFrequency.entrySet()) {
                titleCount++;
                String title = entry.getKey();
                Map<String, WordInfo> wordInfoMap = entry.getValue();
                
                System.out.println("\n[" + titleCount + "/" + totalTitles + "] タイトル: " + title);
                System.out.println(repeat("-", 80));
                
                // 頻度順にソート
                List<WordInfo> sortedWords = new ArrayList<>(wordInfoMap.values());
                sortedWords.sort((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()));
                
                // 上位20件を表示
                int displayCount = Math.min(20, sortedWords.size());
                System.out.printf("%-20s %-15s %10s%n", "単語", "品詞細分類", "頻度");
                System.out.println(repeat("-", 50));
                
                for (int i = 0; i < displayCount; i++) {
                    WordInfo info = sortedWords.get(i);
                    System.out.printf("%-20s %-15s %10d%n", info.getWord(), info.getPosDetail(), info.getFrequency());
                }
                
                if (sortedWords.size() > displayCount) {
                    System.out.println("... 他 " + (sortedWords.size() - displayCount) + " 単語");
                }
                
                System.out.println("総単語種類数: " + sortedWords.size());
            }
            
            // 全体の統計情報
            System.out.println("\n" + repeat("=", 80));
            System.out.println("=== 全体統計 ===");
            System.out.println(repeat("=", 80));
            
            // 全タイトルを通して最も頻度の高い名詞を集計
            Map<String, WordInfo> globalWordInfoMap = new HashMap<>();
            for (Map<String, WordInfo> wordInfoMap : titleWordFrequency.values()) {
                for (WordInfo info : wordInfoMap.values()) {
                    WordInfo globalInfo = globalWordInfoMap.getOrDefault(info.getWord(), 
                        new WordInfo(info.getWord(), info.getPosDetail()));
                    globalInfo.addFrequency(info.getFrequency());
                    globalWordInfoMap.put(info.getWord(), globalInfo);
                }
            }
            
            List<WordInfo> sortedGlobalWords = new ArrayList<>(globalWordInfoMap.values());
            sortedGlobalWords.sort((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()));
            
            System.out.println("\n全タイトル通して頻度の高い名詞 Top 30:");
            System.out.printf("%-20s %-15s %10s %15s%n", "単語", "品詞細分類", "総頻度", "出現タイトル数");
            System.out.println(repeat("-", 65));
            
            for (int i = 0; i < Math.min(30, sortedGlobalWords.size()); i++) {
                WordInfo info = sortedGlobalWords.get(i);
                String word = info.getWord();
                
                // この単語が何タイトルに出現しているか
                int titleAppearance = 0;
                for (Map<String, WordInfo> wordInfoMap : titleWordFrequency.values()) {
                    if (wordInfoMap.containsKey(word)) {
                        titleAppearance++;
                    }
                }
                
                System.out.printf("%-20s %-15s %10d %15d%n", 
                    info.getWord(), info.getPosDetail(), info.getFrequency(), titleAppearance);
            }
            
            // JSON形式で結果を保存
            saveToJson(titleWordFrequency, sortedGlobalWords, totalQuestions, totalTitles, objectMapper);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 結果をJSON形式で保存
     */
    private static void saveToJson(
        Map<String, Map<String, WordInfo>> titleWordFrequency,
        List<WordInfo> sortedGlobalWords,
        int totalQuestions,
        int totalTitles,
        ObjectMapper objectMapper
    ) {
        try {
            // 出力ディレクトリを作成
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String dirPath = OUTPUT_DIR + timestamp;
            new File(dirPath).mkdirs();
            
            // JSONオブジェクトを構築
            ObjectNode rootNode = objectMapper.createObjectNode();
            
            // サマリー情報
            ObjectNode summaryNode = objectMapper.createObjectNode();
            summaryNode.put("timestamp", timestamp);
            summaryNode.put("totalQuestions", totalQuestions);
            summaryNode.put("totalTitles", totalTitles);
            // 結果記録は「一般」と「固有名詞」のみとするため、
            // グローバル語彙もこの2種類にフィルタしてからユニーク数を算出
            List<WordInfo> filteredGlobalWords = new ArrayList<>();
            for (WordInfo wi : sortedGlobalWords) {
                String pd = wi.getPosDetail();
                if ("一般".equals(pd) || "固有名詞".equals(pd)) {
                    filteredGlobalWords.add(wi);
                }
            }
            summaryNode.put("totalUniqueWords", filteredGlobalWords.size());
            summaryNode.put("topNPerTitle", TOP_N_PER_TITLE);
            summaryNode.put("topNGlobal", TOP_N_GLOBAL);
            rootNode.set("summary", summaryNode);
            
            // タイトル別の単語頻度（上位N件のみ）
            ArrayNode titleFrequencyArray = objectMapper.createArrayNode();
            ArrayNode lowNounTitles = objectMapper.createArrayNode();  // 固有名詞と一般名詞が両方3以下のタイトル
            
            for (Map.Entry<String, Map<String, WordInfo>> entry : titleWordFrequency.entrySet()) {
                String title = entry.getKey();
                Map<String, WordInfo> wordInfoMap = entry.getValue();
                
                ObjectNode titleNode = objectMapper.createObjectNode();
                titleNode.put("title", title);
                
                // 頻度順にソート
                List<WordInfo> sortedWords = new ArrayList<>(wordInfoMap.values());
                sortedWords.sort((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()));
                // 記録対象（一般/固有名詞）のみにフィルタ
                List<WordInfo> filteredWords = new ArrayList<>();
                for (WordInfo wi : sortedWords) {
                    String pd = wi.getPosDetail();
                    if ("一般".equals(pd) || "固有名詞".equals(pd)) {
                        filteredWords.add(wi);
                    }
                }
                
                // 品詞詳細ごとの個数を集計
                int properNounCount = 0;     // 固有名詞
                int generalNounCount = 0;    // 一般名詞
                int otherNounCount = 0;      // その他（今回は記録対象外のため常に0）

                for (WordInfo info : filteredWords) {
                    String posDetail = info.getPosDetail();
                    if (posDetail.equals("固有名詞")) {
                        properNounCount++;
                    } else if (posDetail.equals("一般")) {
                        generalNounCount++;
                    }
                }
                
                // 品詞詳細の統計情報
                ObjectNode posStatsNode = objectMapper.createObjectNode();
                posStatsNode.put("properNoun", properNounCount);
                posStatsNode.put("generalNoun", generalNounCount);
                posStatsNode.put("otherNoun", otherNounCount);
                titleNode.set("posDetailStats", posStatsNode);
                
                // 固有名詞と一般名詞が両方3以下の場合に記録
                if (properNounCount <= 3 && generalNounCount <= 3) {
                    ObjectNode lowNounTitle = objectMapper.createObjectNode();
                    lowNounTitle.put("title", title);
                    lowNounTitle.put("properNounCount", properNounCount);
                    lowNounTitle.put("generalNounCount", generalNounCount);
                    lowNounTitle.put("otherNounCount", otherNounCount);
                    lowNounTitle.put("totalUniqueWords", sortedWords.size());
                    lowNounTitles.add(lowNounTitle);
                }
                
                // 上位N件のみ保存（一般/固有名詞のみ）
                ArrayNode wordsArray = objectMapper.createArrayNode();
                int limit = Math.min(TOP_N_PER_TITLE, filteredWords.size());
                for (int i = 0; i < limit; i++) {
                    WordInfo info = filteredWords.get(i);
                    ObjectNode wordNode = objectMapper.createObjectNode();
                    wordNode.put("word", info.getWord());
                    wordNode.put("posDetail", info.getPosDetail());
                    wordNode.put("frequency", info.getFrequency());
                    wordsArray.add(wordNode);
                }
                
                titleNode.set("words", wordsArray);
                titleNode.put("uniqueWordCount", filteredWords.size());
                titleFrequencyArray.add(titleNode);
            }
            rootNode.set("titleWordFrequency", titleFrequencyArray);
            
            // 固有名詞と一般名詞が両方3以下のタイトル
            ObjectNode lowNounNode = objectMapper.createObjectNode();
            lowNounNode.put("count", lowNounTitles.size());
            lowNounNode.set("titles", lowNounTitles);
            rootNode.set("lowNounTitles", lowNounNode);
            
            // 全体の単語頻度（上位N件）
            ArrayNode globalWordsArray = objectMapper.createArrayNode();
            int globalLimit = Math.min(TOP_N_GLOBAL, filteredGlobalWords.size());
            for (int i = 0; i < globalLimit; i++) {
                WordInfo info = filteredGlobalWords.get(i);
                String word = info.getWord();
                
                // この単語が何タイトルに出現しているか
                int titleAppearance = 0;
                for (Map<String, WordInfo> wordInfoMap : titleWordFrequency.values()) {
                    if (wordInfoMap.containsKey(word)) {
                        titleAppearance++;
                    }
                }
                
                ObjectNode wordNode = objectMapper.createObjectNode();
                wordNode.put("word", word);
                wordNode.put("posDetail", info.getPosDetail());
                wordNode.put("totalFrequency", info.getFrequency());
                wordNode.put("titleAppearance", titleAppearance);
                globalWordsArray.add(wordNode);
            }
            rootNode.set("globalTopWords", globalWordsArray);
            
            // ファイルに保存
            String outputPath = dirPath + "/" + OUTPUT_FILE;
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), rootNode);
            
            System.out.println("\n" + repeat("=", 80));
            System.out.println("✓ 結果を保存しました: " + outputPath);
            
            // 固有名詞と一般名詞が両方3以下のタイトルを表示
            if (lowNounTitles.size() > 0) {
                System.out.println("\n" + repeat("=", 80));
                System.out.println("⚠ 固有名詞と一般名詞が両方3以下のタイトル: " + lowNounTitles.size() + "件");
                System.out.println(repeat("=", 80));
                System.out.printf("%-50s %8s %8s %8s%n", "タイトル", "固有名詞", "一般名詞", "その他");
                System.out.println(repeat("-", 80));
                
                for (int i = 0; i < lowNounTitles.size(); i++) {
                    ObjectNode item = (ObjectNode) lowNounTitles.get(i);
                    String title = item.get("title").asText();
                    int properCount = item.get("properNounCount").asInt();
                    int generalCount = item.get("generalNounCount").asInt();
                    int otherCount = item.get("otherNounCount").asInt();
                    
                    // タイトルが長い場合は省略
                    String displayTitle = title.length() > 47 ? title.substring(0, 47) + "..." : title;
                    System.out.printf("%-50s %8d %8d %8d%n", displayTitle, properCount, generalCount, otherCount);
                }
            } else {
                System.out.println("\n✓ 固有名詞と一般名詞が両方3以下のタイトルはありません。");
            }
            
            System.out.println(repeat("=", 80));
            
        } catch (Exception e) {
            System.err.println("JSON保存中にエラーが発生しました: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 有効な単語かどうかをチェック
     */
    private static boolean isValidWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            return false;
        }
        
        // 1文字の記号や数字のみの単語を除外
        if (word.length() == 1 && !Character.isLetterOrDigit(word.charAt(0))) {
            return false;
        }
        
        // 除外する単語リスト
        Set<String> excludeWords = new HashSet<>(Arrays.asList(
            "?", "!", "、", "。", "「", "」", "『", "』", "(", ")", "（", "）"
        ));
        
        return !excludeWords.contains(word);
    }
    
    /**
     * 指定したrepeat回数分の文字列を生成（Java 11互換）
     */
    private static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
    
    /**
     * 単語情報を保持するクラス
     */
    static class WordInfo {
        private String word;
        private String posDetail;  // 品詞細分類（固有名詞、一般名詞など）
        private int frequency;
        
        public WordInfo(String word, String posDetail) {
            this.word = word;
            this.posDetail = posDetail;
            this.frequency = 0;
        }
        
        public String getWord() {
            return word;
        }
        
        public String getPosDetail() {
            return posDetail;
        }
        
        public int getFrequency() {
            return frequency;
        }
        
        public void incrementFrequency() {
            this.frequency++;
        }
        
        public void addFrequency(int count) {
            this.frequency += count;
        }
    }
}
