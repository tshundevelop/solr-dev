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
import java.util.Arrays;
import java.util.List;

/**
 * JaQuADデータをSolr用に変換し、OpenAI APIでベクトル化してSolrに投入する
 */
public class JaQuADToSolr {
    
    private static final List<String> DEFAULT_INPUT_FILES = Arrays.asList(
        // "data/jaquad/processed/jaquad_validation_50.json",
        // "data/wikipedia_ja/processed/wikipedia_ja_validation_50.json"
        "data/jaquad/processed/jaquad_production_792.json",
        "data/wikipedia_ja/processed/wikipedia_ja_production_10000.json"
    );
    private static final String OUTPUT_DIR = "data/embedding";
    private static final String PARENT_DOCS_DIR = "data/embedding/parent_docs";  // 親ドキュメント保存先
    private static final String SOLR_URL = "http://solr:8983/solr";
    private static final String CORE_NAME = "production4000";  // 任意のコア名を指定
    private static final int CHUNK_SIZE = 4000;  // チャンキングする文字数（0の場合はチャンキングしない）
    private static final int BATCH_SIZE = 100;  // バッチサイズ（この件数ごとにSolrに送信）
    
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

        List<String> inputFiles = args.length > 0 ? Arrays.asList(args) : DEFAULT_INPUT_FILES;
        List<Path> inputPaths = converter.resolveInputPaths(inputFiles);
        
        // モード選択: 環境変数 MODE で制御
        String mode = System.getProperty("MODE", "batch");  // デフォルトはbatch
        
        if ("parent".equals(mode)) {
            // モード1: 親ドキュメントのみ生成して保存
            System.out.println("\n=== Parent Documents Generation Mode ===");
            System.out.println("Generating parent documents (is_chunk=false) with embeddings...");
            converter.generateParentDocuments(inputPaths);
        } else {
            // モード2: バッチ処理モード (親ドキュメント読み込み + チャンク生成 + Solr投入)
            System.out.println("\n=== Batch Processing Mode ===");
            System.out.println("Batch size: " + BATCH_SIZE + " documents per commit");
            System.out.println("Loading data from " + inputPaths.size() + " file(s):");
            for (Path path : inputPaths) {
                System.out.println("  - " + path.toAbsolutePath());
            }
            
            converter.processBatchMode(inputPaths, CORE_NAME);
        }
        System.out.println("\n=== All Processing Complete! ===");
    }
    
    /**
     * 親ドキュメント生成モード: embedding済み親ドキュメントをJSONファイルとして保存
     */
    public void generateParentDocuments(List<Path> inputPaths) throws Exception {
        // 出力ディレクトリ作成
        Path parentDocsDir = Paths.get(PARENT_DOCS_DIR);
        Files.createDirectories(parentDocsDir);
        
        for (Path inputPath : inputPaths) {
            System.out.println("\n=== Processing file: " + inputPath.getFileName() + " ===");
            
            JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
            if (!rootNode.isArray()) {
                throw new IOException("Expected JSON array at root for file: " + inputPath);
            }
            
            int totalRecords = rootNode.size();
            System.out.println("Total records: " + totalRecords);
            
            ArrayNode parentDocsArray = objectMapper.createArrayNode();
            
            for (int i = 0; i < totalRecords; i++) {
                JsonNode record = rootNode.get(i);
                
                // 親ドキュメント作成 (is_chunk=false, embedding付き)
                ObjectNode parentDoc = convertRecord(record, i, totalRecords);
                parentDocsArray.add(parentDoc);
                
                if ((i + 1) % 10 == 0 || i == totalRecords - 1) {
                    System.out.println("  Processed: " + (i + 1) + " / " + totalRecords);
                }
            }
            
            // JSONファイルとして保存
            String outputFileName = inputPath.getFileName().toString().replace(".json", "_parent_embedded.json");
            Path outputPath = parentDocsDir.resolve(outputFileName);
            
            objectMapper.writerWithDefaultPrettyPrinter()
                       .writeValue(outputPath.toFile(), parentDocsArray);
            
            System.out.println("✓ Saved " + parentDocsArray.size() + " parent documents to: " + outputPath.toAbsolutePath());
        }
    }
    
    /**
     * バッチモード: 少しずつ処理してSolrに送信
     * 親ドキュメントが既に存在する場合はそれを読み込み、チャンクのみ生成
     */
    public void processBatchMode(List<Path> inputPaths, String coreName) throws Exception {
        String solrCoreUrl = SOLR_URL + "/" + coreName;
        
        try (SolrClient solrClient = new HttpSolrClient.Builder(solrCoreUrl).build()) {
            int totalProcessed = 0;
            int totalDocuments = 0;
            
            for (Path inputPath : inputPaths) {
                System.out.println("\n=== Processing file: " + inputPath.getFileName() + " ===");
                
                // 保存済み親ドキュメントファイルをチェック
                String parentFileName = inputPath.getFileName().toString().replace(".json", "_parent_embedded.json");
                Path parentFilePath = Paths.get(PARENT_DOCS_DIR).resolve(parentFileName);
                
                List<ObjectNode> parentDocs = new ArrayList<>();
                boolean usePreGeneratedParents = Files.exists(parentFilePath);
                
                if (usePreGeneratedParents) {
                    System.out.println("✓ Found pre-generated parent documents: " + parentFilePath.getFileName());
                    System.out.println("  Loading parent documents from cache...");
                    JsonNode parentArray = objectMapper.readTree(parentFilePath.toFile());
                    if (parentArray.isArray()) {
                        for (JsonNode node : parentArray) {
                            parentDocs.add((ObjectNode) node);
                        }
                    }
                    System.out.println("  Loaded " + parentDocs.size() + " parent documents");
                } else {
                    System.out.println("⚠ Parent documents not found. Will generate embeddings on-the-fly.");
                }
                
                JsonNode rootNode = objectMapper.readTree(inputPath.toFile());
                if (!rootNode.isArray()) {
                    throw new IOException("Expected JSON array at root for file: " + inputPath);
                }
                
                int totalRecords = rootNode.size();
                System.out.println("Total records: " + totalRecords);
                
                List<SolrInputDocument> batch = new ArrayList<>();
                
                for (int i = 0; i < totalRecords; i++) {
                    JsonNode record = rootNode.get(i);
                    
                    // 親ドキュメント: キャッシュから取得 or 新規生成
                    ObjectNode parentDoc;
                    if (usePreGeneratedParents && i < parentDocs.size()) {
                        parentDoc = parentDocs.get(i);
                        System.out.println("  [" + (i+1) + "/" + totalRecords + "] Using cached parent: " + parentDoc.get("id").asText());
                    } else {
                        System.out.println("  [" + (i+1) + "/" + totalRecords + "] Generating parent with embedding...");
                        parentDoc = convertRecord(record, i, totalRecords);
                    }
                    batch.add(convertToSolrInputDocument(parentDoc));
                    
                    // チャンキングが有効な場合、チャンクドキュメントも作成
                    if (CHUNK_SIZE > 0) {
                        System.out.println("    Generating chunks...");
                        List<ObjectNode> chunkDocs = createChunkDocuments(record, i, totalRecords);
                        for (ObjectNode chunkDoc : chunkDocs) {
                            batch.add(convertToSolrInputDocument(chunkDoc));
                        }
                    }
                    
                    // バッチサイズに達したらSolrに送信
                    if (batch.size() >= BATCH_SIZE) {
                        System.out.println("  Indexing batch: " + batch.size() + " documents...");
                        solrClient.add(batch);
                        solrClient.commit();
                        totalDocuments += batch.size();
                        System.out.println("  ✓ Indexed " + totalDocuments + " documents so far");
                        batch.clear();  // メモリ解放
                    }
                    
                    totalProcessed++;
                }
                
                // 残りのドキュメントを送信
                if (!batch.isEmpty()) {
                    System.out.println("  Indexing final batch: " + batch.size() + " documents...");
                    solrClient.add(batch);
                    solrClient.commit();
                    totalDocuments += batch.size();
                    System.out.println("  ✓ Indexed " + totalDocuments + " documents total");
                    batch.clear();
                }
            }
            
            System.out.println("\n=== Summary ===");
            System.out.println("Total records processed: " + totalProcessed);
            System.out.println("Total documents indexed: " + totalDocuments);
        }
    }
    
    /**
     * ObjectNodeをSolrInputDocumentに変換
     */
    private SolrInputDocument convertToSolrInputDocument(ObjectNode doc) {
        SolrInputDocument solrInputDoc = new SolrInputDocument();
        
        solrInputDoc.addField("id", doc.get("id").asText());
        solrInputDoc.addField("is_chunk", doc.get("is_chunk").asBoolean());
        solrInputDoc.addField("original_doc_id", doc.get("original_doc_id").asText());
        solrInputDoc.addField("title", doc.get("title").asText());
        if (doc.has("question")) {
            solrInputDoc.addField("question", doc.get("question").asText());
        }
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
        
        return solrInputDoc;
    }
    
    /**
     * JaQuADデータをSolr形式に変換
     */
    public List<ObjectNode> convertJaQuADToSolrFormat(List<Path> inputPaths) throws IOException {
        List<ObjectNode> solrDocs = new ArrayList<>();

        for (Path inputPath : inputPaths) {
            System.out.println("Processing file: " + inputPath.toAbsolutePath());

            JsonNode rootNode = objectMapper.readTree(inputPath.toFile());

            if (!rootNode.isArray()) {
                throw new IOException("Expected JSON array at root for file: " + inputPath);
            }

            int totalRecords = rootNode.size();
            System.out.println("Total records in file: " + totalRecords);

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
        String question = record.has("question") ? record.get("question").asText() : null;
        
        // コンテキストを切り詰める（長すぎる場合）
        String truncatedContext = truncateContext(context, 6000);
        
        // Solrドキュメント作成
        ObjectNode solrDoc = objectMapper.createObjectNode();
        solrDoc.put("id", id);
        solrDoc.put("is_chunk", false);
        solrDoc.put("original_doc_id", id);
        solrDoc.put("title", title);
        if (question != null) {
            solrDoc.put("question", question);
        }
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
                if (doc.has("question")) {
                    solrInputDoc.addField("question", doc.get("question").asText());
                }
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
    private List<Path> resolveInputPaths(List<String> inputFiles) throws IOException {
        List<Path> resolvedPaths = new ArrayList<>();

        List<Path> baseDirs = Arrays.asList(
            Paths.get(""),
            Paths.get(".."),
            Paths.get("/app")
        );

        for (String inputFile : inputFiles) {
            boolean found = false;
            for (Path baseDir : baseDirs) {
                Path candidate = baseDir.resolve(inputFile).normalize();
                if (Files.exists(candidate)) {
                    System.out.println("Found input file: " + candidate.toAbsolutePath());
                    resolvedPaths.add(candidate);
                    found = true;
                    break;
                }
            }

            if (!found) {
                throw new IOException("Input file not found: " + inputFile);
            }
        }

        return resolvedPaths;
    }
}
