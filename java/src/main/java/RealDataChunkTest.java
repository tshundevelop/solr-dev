import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 実データでチャンキングをテスト
 */
public class RealDataChunkTest {
    
    private static final int OVERLAP_SECTIONS = 1;
    private static final int MIN_CHUNK_SIZE = 100;  // 100文字未満のチャンクを除外
    
    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // JaQuADデータを読み込み
        File jsonFile = new File("/app/java/data/jaquad_validation_3939.json");
        JsonNode data = objectMapper.readTree(jsonFile);
        
        if (data == null || !data.isArray() || data.size() == 0) {
            System.out.println("データが見つかりません");
            return;
        }
        
        // 最初のデータを取得
        JsonNode firstItem = data.get(0);
        String context = firstItem.get("context").asText();
        String title = firstItem.get("title").asText();
        
        String separator = "====================================================================================================";
        String dashedLine = "----------------------------------------------------------------------------------------------------";
        
        System.out.println(separator);
        System.out.println("【タイトル】" + title);
        System.out.println(separator);
        System.out.println();
        
        System.out.println("【元のテキスト】(" + context.length() + "文字)");
        System.out.println(dashedLine);
        System.out.println(context);
        System.out.println();
        
        // セクション分割して表示
        System.out.println(separator);
        System.out.println("【セクション分割】");
        System.out.println(separator);
        String[] sections = context.split("。\\s*\\n\\s*\\n");
        List<String> sectionList = new ArrayList<>();
        
        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            if (section.isEmpty()) continue;
            
            if (i < sections.length - 1 && !section.endsWith("。")) {
                section = section + "。";
            }
            sectionList.add(section);
        }
        
        System.out.println("セクション数: " + sectionList.size());
        System.out.println();
        
        for (int i = 0; i < sectionList.size(); i++) {
            System.out.println("--- セクション" + (i + 1) + " (" + sectionList.get(i).length() + "文字) ---");
            System.out.println(sectionList.get(i));
            System.out.println();
        }
        
        // チャンキング（OVERLAP_SECTIONS = 0）
        System.out.println(separator);
        System.out.println("【チャンキング結果】OVERLAP_SECTIONS = 0");
        System.out.println(separator);
        testChunking(sectionList, 0);
        
        // チャンキング（OVERLAP_SECTIONS = 1）
        System.out.println();
        System.out.println(separator);
        System.out.println("【チャンキング結果】OVERLAP_SECTIONS = 1");
        System.out.println(separator);
        testChunking(sectionList, 1);
        
        // チャンキング（OVERLAP_SECTIONS = 2）
        System.out.println();
        System.out.println(separator);
        System.out.println("【チャンキング結果】OVERLAP_SECTIONS = 2");
        System.out.println(separator);
        testChunking(sectionList, 2);
    }
    
    private static void testChunking(List<String> sectionList, int overlapSections) {
        List<String> chunks = createChunks(sectionList, overlapSections);
        
        System.out.println("生成チャンク数: " + chunks.size() + " (MIN_CHUNK_SIZE=" + MIN_CHUNK_SIZE + "文字以上)");
        System.out.println();
        
        for (int i = 0; i < chunks.size(); i++) {
            // このチャンクに含まれるセクション範囲を計算
            int center = i;
            int start = Math.max(0, center - overlapSections);
            int end = Math.min(sectionList.size() - 1, center + overlapSections);
            
            System.out.println("--- チャンク" + (i + 1) + " (" + chunks.get(i).length() + "文字) ---");
            System.out.println("→ セクション範囲: [" + (start + 1) + "..." + (center + 1) + "..." + (end + 1) + "]");
            System.out.println();
            System.out.println(chunks.get(i));
            System.out.println();
        }
    }
    
    private static List<String> createChunks(List<String> sectionList, int overlapSections) {
        List<String> chunks = new ArrayList<>();
        
        for (int i = 0; i < sectionList.size(); i++) {
            StringBuilder chunkBuilder = new StringBuilder();
            
            int start = Math.max(0, i - overlapSections);
            int end = Math.min(sectionList.size() - 1, i + overlapSections);
            
            for (int j = start; j <= end; j++) {
                if (chunkBuilder.length() > 0) {
                    chunkBuilder.append("\n\n");
                }
                chunkBuilder.append(sectionList.get(j));
            }
            
            String chunk = chunkBuilder.toString();
            
            // 最小文字数以上のチャンクのみ追加
            if (chunk.length() >= MIN_CHUNK_SIZE) {
                chunks.add(chunk);
            }
        }
        
        return chunks;
    }
}
