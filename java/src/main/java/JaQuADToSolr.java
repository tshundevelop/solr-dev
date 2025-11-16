import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * JaQuADデータをSolr用に変換し、OpenAI APIでベクトル化してSolrに投入する
 */
public class JaQuADToSolr {
    
    private static final String INPUT_FILE = "data/jaquad/processed/jaquad_validation_50.json";
    private static final String OUTPUT_DIR = "data/embedding";
    private static final String SOLR_URL = "http://solr:8983/solr";
    private static final String CORE_NAME = "test";  // 任意のコア名を指定
    private static final int CHUNK_SIZE = 2000;  // チャンキングする文字数（0の場合はチャンキングしない）
    
    private final ObjectMapper objectMapper;
    private final String apiKey;
    
    public JaQuADToSolr(String apiKey) {
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
    }
    
    public static void main(String[] args) throws Exception {
        // api_key.envからAPIキーを読み込み
        try {
            DotEnvLoader.load("api_key.env", "OPENAI_API_KEY");
        } catch (IOException e) {
            System.err.println("Error loading api_key.env: " + e.getMessage());
        }
        
        String apiKey = System.getProperty("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: OPENAI_API_KEY not found");
            System.exit(1);
        }
        
        JaQuADToSolr converter = new JaQuADToSolr(apiKey);
        
        // 1. データ読み込みと変換
        System.out.println("\n=== Step 1: Data Conversion ===");
        System.out.println("Loading JaQuAD data from: " + INPUT_FILE);
        List<ObjectNode> solrDocs = converter.convertJaQuADToSolrFormat();
        System.out.println("Converted " + solrDocs.size() + " documents");
        
        // 2. JSON保存
        System.out.println("\n=== Step 2: Save JSON ===");
        String outputFile = converter.saveSolrDocuments(solrDocs);
        System.out.println("Saved to: " + outputFile);
        
        // 3. Solrへ投入
        System.out.println("\n=== Step 3: Index to Solr ===");
        System.out.println("Indexing to Solr core: " + CORE_NAME);
        converter.indexToSolr(solrDocs, CORE_NAME);
        System.out.println("Indexing complete!");
    }
    
    /**
     * JaQuADデータをSolr形式に変換
     */
    public List<ObjectNode> convertJaQuADToSolrFormat() throws IOException {
        // 入力パスを解決（複数の候補から）
        Path inputPath = resolveInputPath();
        
        // JSONデータ読み込み
        JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
        
        if (!rootNode.isArray()) {
            throw new IOException("Expected JSON array at root");
        }
        
        List<ObjectNode> solrDocs = new ArrayList<>();
        int totalRecords = rootNode.size();
        System.out.println("Total records to process: " + totalRecords);
        
        for (int i = 0; i < totalRecords; i++) {
            JsonNode record = rootNode.get(i);
            
            // 元のドキュメント作成
            ObjectNode solrDoc = convertRecord(record, i, totalRecords);
            solrDocs.add(solrDoc);
            
            // チャンキングが有効な場合、チャンクドキュメントも作成
            if (CHUNK_SIZE > 0) {
                List<ObjectNode> chunkDocs = createChunkDocuments(record, i, totalRecords);
                solrDocs.addAll(chunkDocs);
            }
            
            // 進捗表示
            if ((i + 1) % 10 == 0 || i == totalRecords - 1) {
                System.out.println("Processed: " + (i + 1) + " / " + totalRecords);
            }
        }
        
        return solrDocs;
    }
    
    /**
     * チャンクドキュメントを作成
     */
    private List<ObjectNode> createChunkDocuments(JsonNode record, int index, int total) {
        List<ObjectNode> chunkDocs = new ArrayList<>();
        
        String id = record.get("id").asText();
        String title = record.get("title").asText();
        String context = record.get("context").asText();
        String question = record.get("question").asText();
        
        // コンテキストをチャンキング
        int chunkCount = 0;
        for (int start = 0; start < context.length(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, context.length());
            String chunkText = context.substring(start, end);
            chunkCount++;
            
            // チャンクドキュメント作成
            ObjectNode chunkDoc = objectMapper.createObjectNode();
            chunkDoc.put("id", id + "-" + chunkCount);
            chunkDoc.put("is_chunk", true);
            chunkDoc.put("original_doc_id", id);
            chunkDoc.put("title", title);
            chunkDoc.put("question", question);
            chunkDoc.put("context", chunkText);
            
            // チャンクテキストをOpenAI APIでベクトル化
            System.out.println("    [Chunk " + chunkCount + "] Generating embedding for: " + id + "-" + chunkCount);
            List<Double> embedding = null;
            try {
                embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(chunkText, apiKey);
                if (embedding == null) {
                    System.err.println("      Embedding is null for chunk " + id + "-" + chunkCount);
                    embedding = new ArrayList<>();
                }
            } catch (Exception e) {
                System.err.println("      Error getting embedding for chunk " + id + "-" + chunkCount + ": " + e.getMessage());
                embedding = new ArrayList<>();
            }
            
            // ベクトルをJSON配列に変換
            ArrayNode vectorArray = objectMapper.createArrayNode();
            if (embedding != null && !embedding.isEmpty()) {
                for (Double value : embedding) {
                    vectorArray.add(value);
                }
            }
            chunkDoc.set("chunk_vector", vectorArray);
            
            chunkDocs.add(chunkDoc);
        }
        
        return chunkDocs;
    }
    
    /**
     * コンテキストをトークン制限に収まるように切り詰める
     * text-embedding-3-largeの上限は8192トークン
     * 安全のため約6000文字（約2000トークン相当）に制限
     */
    private String truncateContext(String context, int maxChars) {
        if (context.length() <= maxChars) {
            return context;
        }
        System.out.println("    Context too long (" + context.length() + " chars), truncating to " + maxChars);
        return context.substring(0, maxChars);
    }
    
    /**
     * 1レコードを変換
     */
    private ObjectNode convertRecord(JsonNode record, int index, int total) {
        String id = record.get("id").asText();
        String title = record.get("title").asText();
        String context = record.get("context").asText();
        String question = record.get("question").asText();
        
        // コンテキストを切り詰める（長すぎる場合）
        String truncatedContext = truncateContext(context, 6000);
        
        // Solrドキュメント作成
        ObjectNode solrDoc = objectMapper.createObjectNode();
        solrDoc.put("id", id);
        solrDoc.put("is_chunk", false);
        solrDoc.put("original_doc_id", id);
        solrDoc.put("title", title);
        solrDoc.put("question", question);
        solrDoc.put("context", context);  // 元のcontextを保存
        
        // truncatedContextをOpenAI APIでベクトル化
        System.out.println("  [" + (index + 1) + "/" + total + "] Generating embedding for: " + id);
        List<Double> embedding = null;
        try {
            embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(truncatedContext, apiKey);
            if (embedding == null) {
                System.err.println("    Embedding is null for " + id);
                embedding = new ArrayList<>();
            }
        } catch (Exception e) {
            System.err.println("    Error getting embedding for " + id + ": " + e.getMessage());
            embedding = new ArrayList<>();  // 空のベクトル
        }
        
        // ベクトルをJSON配列に変換
        ArrayNode vectorArray = objectMapper.createArrayNode();
        if (embedding != null && !embedding.isEmpty()) {
            for (Double value : embedding) {
                vectorArray.add(value);
            }
        }
        solrDoc.set("context_vector", vectorArray);
        
        return solrDoc;
    }
    
    /**
     * Solrドキュメントを JSON ファイルに保存
     */
    public String saveSolrDocuments(List<ObjectNode> docs) throws IOException {
        // 出力ディレクトリ作成
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        // 出力ファイル名（件数を含める）
        String fileName = "jaquad_validation_solr_" + docs.size() + ".json";
        Path outputPath = outputDir.resolve(fileName);
        
        // JSON配列として保存
        ArrayNode rootArray = objectMapper.createArrayNode();
        for (ObjectNode doc : docs) {
            rootArray.add(doc);
        }
        
        objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputPath.toFile(), rootArray);
        
        return outputPath.toAbsolutePath().toString();
    }
    
    /**
     * SolrにドキュメントをインデックスTodo
     */
    public void indexToSolr(List<ObjectNode> docs, String coreName) throws Exception {
        String solrCoreUrl = SOLR_URL + "/" + coreName;
        
        try (SolrClient solrClient = new HttpSolrClient.Builder(solrCoreUrl).build()) {
            List<SolrInputDocument> solrInputDocs = new ArrayList<>();
            
            for (ObjectNode doc : docs) {
                SolrInputDocument solrInputDoc = new SolrInputDocument();
                
                // フィールドを追加
                solrInputDoc.addField("id", doc.get("id").asText());
                solrInputDoc.addField("is_chunk", doc.get("is_chunk").asBoolean());
                solrInputDoc.addField("original_doc_id", doc.get("original_doc_id").asText());
                solrInputDoc.addField("title", doc.get("title").asText());
                solrInputDoc.addField("question", doc.get("question").asText());
                solrInputDoc.addField("context", doc.get("context").asText());
                
                // ベクトルフィールドを追加（JSON配列 → float配列）
                JsonNode vectorNode = doc.get("context_vector");
                if (vectorNode != null && vectorNode.isArray()) {
                    List<Float> vectorList = new ArrayList<>();
                    for (JsonNode element : vectorNode) {
                        vectorList.add((float) element.asDouble());
                    }
                    solrInputDoc.addField("context_vector", vectorList);
                }
                
                // チャンクベクトルフィールドを追加
                JsonNode chunkVectorNode = doc.get("chunk_vector");
                if (chunkVectorNode != null && chunkVectorNode.isArray()) {
                    List<Float> chunkVectorList = new ArrayList<>();
                    for (JsonNode element : chunkVectorNode) {
                        chunkVectorList.add((float) element.asDouble());
                    }
                    solrInputDoc.addField("chunk_vector", chunkVectorList);
                }
                
                solrInputDocs.add(solrInputDoc);
            }
            
            // バッチでコミット
            System.out.println("Adding " + solrInputDocs.size() + " documents to Solr...");
            solrClient.add(solrInputDocs);
            solrClient.commit();
            System.out.println("Successfully indexed " + solrInputDocs.size() + " documents");
        }
    }
    
    /**
     * 入力ファイルパスを解決
     */
    private Path resolveInputPath() throws IOException {
        List<Path> candidates = new ArrayList<>();
        
        // 候補パスを追加
        candidates.add(Paths.get(INPUT_FILE));
        candidates.add(Paths.get("../" + INPUT_FILE));
        candidates.add(Paths.get("/app/" + INPUT_FILE));  // Docker環境
        
        // 存在するパスを探す
        for (Path path : candidates) {
            if (Files.exists(path)) {
                System.out.println("Found input file: " + path.toAbsolutePath());
                return path;
            }
        }
        
        throw new IOException("Input file not found: " + INPUT_FILE);
    }
}
