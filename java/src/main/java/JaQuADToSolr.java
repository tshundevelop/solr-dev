import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * JaQuADデータをSolr用に変換し、OpenAI APIでベクトル化してSolrに投入する
 */
public class JaQuADToSolr {
    
    private static final List<String> DEFAULT_INPUT_FILES = Arrays.asList(
        // "data/jaquad/processed/jaquad_validation_50.json",
        // "data/wikipedia_ja/processed/wikipedia_ja_validation_50.json"
        "data/jaquad/processed/jaquad_production_792.json",
        "data/wikipedia_ja/processed/wikipedia_ja_production_9932.json"
    );
    private static final String OUTPUT_DIR = "data/embedding";
    private static final String PARENT_DOCS_DIR = "data/embedding/parent_docs";  // 親ドキュメント保存先
    private static final String SOLR_URL = "http://solr:8983/solr";
    private static final String CORE_NAME = "production_split-semchunk4";  // 任意のコア名を指定
    private static final int CHUNK_SIZE = 1000;  // チャンキングする文字数（０の場合はチャンキングしない、fixedモード用）
    private static final int BATCH_SIZE = 100;  // バッチサイズ（この件数ごとにSolrに送信）
    
    // チャンキングモード: "fixed" = 固定文字数、"section" = セクション区切り
    private static final String CHUNK_MODE = "section";  // "fixed" or "section"
    
    // セクションモード用: 前後に結合するセクション数（0=オーバーラップなし、1=前後1セクションずつ）
    private static final int OVERLAP_SECTIONS = 4;
    
    // 最小チャンク文字数（これ以下のチャンクは除外される）
    private static final int MIN_CHUNK_SIZE = 100;
    
    // セクション分割用セパレーター（nullの場合は文単位分割、設定時はカスタム分割）
    private static final String SECTION_SEPARATOR = "。";  // 例: "\n\n", ",", "---" など
    
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
        // batch or parent
        String mode = System.getProperty("MODE", "batch");  // デフォルトをparentに変更
        
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
            
            // 並列処理用のスレッドプール (CPUコア数の2倍)
            int threadPoolSize = Runtime.getRuntime().availableProcessors() * 2;
            ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
            System.out.println("Using thread pool with " + threadPoolSize + " threads for parallel processing");
            
            ArrayNode parentDocsArray = objectMapper.createArrayNode();
            
            try {
                // バッチ単位で並列処理
                for (int batchStart = 0; batchStart < totalRecords; batchStart += BATCH_SIZE) {
                    int batchEnd = Math.min(batchStart + BATCH_SIZE, totalRecords);
                    
                    System.out.println("\n=== Processing batch [" + (batchStart+1) + "-" + batchEnd + "] ===");
                    
                    // 並列でタスクを投入
                    Map<Integer, Future<ObjectNode>> futures = new HashMap<>();
                    for (int i = batchStart; i < batchEnd; i++) {
                        final int index = i;
                        final JsonNode record = rootNode.get(i);
                        futures.put(i, executorService.submit(() -> convertRecord(record, index, totalRecords, new HashSet<>())));
                    }
                    
                    System.out.println("  → Submitted " + futures.size() + " parallel tasks");
                    
                    // 結果を収集（インデックス順に保持）
                    for (int i = batchStart; i < batchEnd; i++) {
                        ObjectNode parentDoc = futures.get(i).get();
                        parentDocsArray.add(parentDoc);
                    }
                    
                    System.out.println("  → Completed batch: " + (batchEnd - batchStart) + " documents");
                }
                
                System.out.println("\nTotal processed: " + parentDocsArray.size() + " documents");
                
            } finally {
                executorService.shutdown();
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
        
        // 登録済みIDを取得
        System.out.println("📋 Checking existing documents in Solr core...");
        Set<String> existingIds = getExistingIdsFromSolr(SOLR_URL, coreName);
        System.out.println("✅ Found " + existingIds.size() + " existing documents in core");
        System.out.println();
        
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
                
                // 並列処理用のスレッドプール (CPUコア数の2倍)
                int threadPoolSize = Runtime.getRuntime().availableProcessors() * 2;
                ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
                System.out.println("Using thread pool with " + threadPoolSize + " threads for parallel processing");
                
                try {
                    // バッチ単位で処理
                    for (int batchStart = 0; batchStart < totalRecords; batchStart += BATCH_SIZE) {
                        int batchEnd = Math.min(batchStart + BATCH_SIZE, totalRecords);
                        int batchSize = batchEnd - batchStart;
                        
                        System.out.println("\n=== Processing batch [" + (batchStart+1) + "-" + batchEnd + "] ===");
                        
                        // Phase 1: すべてのタスクを並列投入
                        Map<Integer, Future<ObjectNode>> parentFutures = new HashMap<>();
                        Map<Integer, Future<List<ObjectNode>>> chunkFutures = new HashMap<>();
                        
                        for (int i = batchStart; i < batchEnd; i++) {
                            final int index = i;
                            final JsonNode record = rootNode.get(i);
                            
                            // 親ドキュメント: キャッシュから取得 or 並列生成
                            if (usePreGeneratedParents && i < parentDocs.size()) {
                                final ObjectNode cachedParent = parentDocs.get(i);
                                parentFutures.put(i, executorService.submit(() -> cachedParent));
                            } else {
                                parentFutures.put(i, executorService.submit(() -> convertRecord(record, index, totalRecords, existingIds)));
                            }
                            
                            // チャンクドキュメントを並列生成
                            if (CHUNK_SIZE > 0) {
                                chunkFutures.put(i, executorService.submit(() -> createChunkDocuments(record, index, totalRecords, existingIds)));
                            }
                        }
                        
                        System.out.println("  → Submitted " + parentFutures.size() + " parent tasks and " + chunkFutures.size() + " chunk tasks");
                        
                        // Phase 2: 結果を収集してバッチに追加
                        List<SolrInputDocument> batch = new ArrayList<>();
                        for (int i = batchStart; i < batchEnd; i++) {
                            // 親ドキュメント
                            ObjectNode parentDoc = parentFutures.get(i).get();
                            batch.add(convertToSolrInputDocument(parentDoc));
                            
                            // チャンクドキュメント
                            if (chunkFutures.containsKey(i)) {
                                List<ObjectNode> chunkDocs = chunkFutures.get(i).get();
                                for (ObjectNode chunkDoc : chunkDocs) {
                                    batch.add(convertToSolrInputDocument(chunkDoc));
                                }
                            }
                        }
                        
                        System.out.println("  → Collected " + batch.size() + " documents from parallel processing");
                        
                        // Phase 3: Solrに送信
                        System.out.println("  → Indexing batch: " + batch.size() + " documents...");
                        solrClient.add(batch);
                        solrClient.commit();
                        totalDocuments += batch.size();
                        totalProcessed += batchSize;
                        System.out.println("  ✓ Indexed " + totalDocuments + " documents (" + totalProcessed + " records processed)");
                    }
                } finally {
                    executorService.shutdown();
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
                ObjectNode solrDoc = convertRecord(record, i, totalRecords, new HashSet<>());
                if (solrDoc == null) {
                    continue; // 既に登録済みの場合はスキップ
                }
                solrDocs.add(solrDoc);

                // チャンキングが有効な場合、チャンクドキュメントも作成
                if (CHUNK_SIZE > 0) {
                    List<ObjectNode> chunkDocs = createChunkDocuments(record, i, totalRecords, new HashSet<>());
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
    private List<ObjectNode> createChunkDocuments(JsonNode record, int index, int total, Set<String> existingIds) {
        List<ObjectNode> chunkDocs = new ArrayList<>();
        
        String id = record.get("id").asText();
        String title = record.get("title").asText();
        String context = record.get("context").asText();
        
        // チャンキングモードに応じて分割
        List<String> chunks;
        if ("section".equals(CHUNK_MODE)) {
            chunks = splitIntoSectionChunks(context);
        } else {
            chunks = splitIntoFixedChunks(context, CHUNK_SIZE);
        }
        
        // 各チャンクをドキュメント化
        int chunkCount = 0;
        for (String chunkText : chunks) {
            chunkCount++;
            
            final String chunkId = id + "-" + chunkCount;
            
            // 既に登録済みの場合はスキップ
            if (existingIds.contains(chunkId)) {
                System.out.println("    ⏭️  Skipping chunk " + chunkId + " (already exists)");
                continue;
            }
            
            // チャンクドキュメント作成
            ObjectNode chunkDoc = objectMapper.createObjectNode();
            chunkDoc.put("id", chunkId);
            chunkDoc.put("is_chunk", true);
            chunkDoc.put("original_doc_id", id);
            chunkDoc.put("title", title);
            chunkDoc.put("context", chunkText);
            
            // チャンクテキストをOpenAI APIでベクトル化 - 絶対に成功させる
            System.out.println("    [Chunk " + chunkCount + "] Generating embedding for: " + id + "-" + chunkCount);
            List<Double> embedding = null;
            boolean success = false;
            int attempts = 0;
            
            while (!success) {
                attempts++;
                try {
                    embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(chunkText, apiKey);
                    if (embedding != null && !embedding.isEmpty()) {
                        success = true;
                        if (attempts > 1) {
                            System.out.println("      ✅ SUCCESS after " + attempts + " attempts for chunk " + id + "-" + chunkCount);
                        }
                    } else {
                        System.err.println("      ⚠ Embedding is null/empty for chunk " + id + "-" + chunkCount + " (attempt " + attempts + ") - retrying...");
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { /* ignore */ }
                    }
                } catch (InterruptedException e) {
                    System.err.println("      ⚠ Interrupted for chunk " + id + "-" + chunkCount + " (attempt " + attempts + ") - retrying...");
                    Thread.currentThread().interrupt();
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { /* ignore */ }
                } catch (Exception e) {
                    System.err.println("      ❌ Error for chunk " + id + "-" + chunkCount + " (attempt " + attempts + "): " + e.getMessage() + " - RETRYING...");
                    try { Thread.sleep(Math.min(attempts * 1000L, 10000L)); } catch (InterruptedException ie) { /* ignore */ }
                }
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
     * セクションベースでチャンキング（「。\n\n」で区切り、前後のセクションを結合）
     */
    private List<String> splitIntoSectionChunks(String text) {
        List<String> chunks = new ArrayList<>();
        
        // 「。\n\n」（句点 + 改行2つ）でセクション分割
        String[] sections = text.split("。\\s*\\n\\s*\\n");
        List<String> sectionList = new ArrayList<>();
        
        for (int i = 0; i < sections.length; i++) {
            String section = sections[i].trim();
            
            // 空セクションをスキップ
            if (section.isEmpty()) {
                continue;
            }
            
            // セクションの末尾に「。」を復元（最後のセクション以外）
            if (i < sections.length - 1 && !section.endsWith("。")) {
                section = section + "。";
            }
            
            sectionList.add(section);
        }
        
        // セクションが1つもない場合は全体を1チャンクとして扱う
        if (sectionList.isEmpty()) {
            chunks.add(text);
            return chunks;
        }
        
        // 各セクションを中心に、前後OVERLAP_SECTIONSセクションを結合
        for (int i = 0; i < sectionList.size(); i++) {
            StringBuilder chunkBuilder = new StringBuilder();
            
            // 開始位置と終了位置を計算
            int start = Math.max(0, i - OVERLAP_SECTIONS);
            int end = Math.min(sectionList.size() - 1, i + OVERLAP_SECTIONS);
            
            // セクションを結合
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
    
    /**
     * 固定文字数でチャンキング（従来の方法）
     */
    private List<String> splitIntoFixedChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
        }
        
        return chunks;
    }
    
    /**
     * 簡易的なトークン数推定（OpenAIEmbeddingClientと同じロジック）
     * 日本語の場合、文字数の約1.3倍程度をトークン数として推定
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 日本語テキストの場合、文字数 × 1.3 + 50（固定オーバーヘッド）
        return Math.round(text.length() * 1.3f) + 50;
    }
    
    /**
     * 長いコンテキストを複数セクションに分割して各セクションの埋め込みベクトルを平均化
     * text-embedding-3-largeの上限は8192トークン、安全のため5000トークンに制限
     * OpenAIEmbeddingClientのrate limiting機能と連携
     */
    private List<Double> generateAveragedEmbedding(String context, String apiKey) {
        final int MAX_TOKENS = 5000;  // さらに保守的な制限値（8192の約1/4）
        int estimatedTokens = estimateTokenCount(context);
        
        if (estimatedTokens <= MAX_TOKENS) {
            // トークン制限内なら通常の埋め込み生成
            try {
                return OpenAIEmbeddingClient.getEmbeddingFromOpenAI(context, apiKey);
            } catch (InterruptedException e) {
                System.err.println("    Rate limit control interrupted");
                Thread.currentThread().interrupt();
                return new ArrayList<>();
            } catch (Exception e) {
                System.err.println("    Error getting embedding: " + e.getMessage());
                return new ArrayList<>();
            }
        }
        
        System.out.println("    Context exceeds token limit (" + estimatedTokens + " tokens), splitting into sections");
        
        // セクション分割
        List<String> sections = splitIntoSections(context, MAX_TOKENS);
        System.out.println("    Split into " + sections.size() + " sections");
        
        // 各セクションの埋め込みを生成
        List<List<Double>> sectionEmbeddings = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            String section = sections.get(i);
            int sectionTokens = estimateTokenCount(section);
            System.out.println("      Generating embedding for section " + (i + 1) + "/" + sections.size() + " (" + sectionTokens + " tokens)");
            
            // セクションが依然として大きすぎる場合は更に分割
            if (sectionTokens > MAX_TOKENS) {
                System.out.println("        Section still too large, further splitting...");
                List<String> subSections = splitIntoSections(section, MAX_TOKENS / 3);
                for (String subSection : subSections) {
                    try {
                        List<Double> embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(subSection, apiKey);
                        if (embedding != null && !embedding.isEmpty()) {
                            sectionEmbeddings.add(embedding);
                        }
                    } catch (InterruptedException e) {
                        System.err.println("        Rate limit control interrupted for sub-section");
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        System.err.println("        Error getting embedding for sub-section: " + e.getMessage());
                    }
                }
            } else {
                try {
                    List<Double> embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(section, apiKey);
                    if (embedding != null && !embedding.isEmpty()) {
                        sectionEmbeddings.add(embedding);
                    }
                } catch (InterruptedException e) {
                    System.err.println("      Rate limit control interrupted for section " + (i + 1));
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("      Error getting embedding for section " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        
        if (sectionEmbeddings.isEmpty()) {
            System.err.println("    No valid embeddings generated from sections");
            return new ArrayList<>();
        }
        
        // ベクトルの平均を計算
        System.out.println("    Averaging " + sectionEmbeddings.size() + " section embeddings");
        return averageEmbeddings(sectionEmbeddings);
    }
    
    /**
     * テキストをトークン制限に基づいて複数セクションに分割
     * SECTION_SEPARATOR定数でセパレーターを指定可能
     */
    private List<String> splitIntoSections(String text, int maxTokensPerSection) {
        if (SECTION_SEPARATOR != null && !SECTION_SEPARATOR.trim().isEmpty()) {
            // カスタムセパレーターで分割
            System.out.println("    Using custom separator: '" + SECTION_SEPARATOR + "'");
            return splitWithCustomSeparator(text, maxTokensPerSection, SECTION_SEPARATOR);
        } else {
            // デフォルトの文単位分割
            return splitWithDefaultSeparator(text, maxTokensPerSection);
        }
    }
    
    /**
     * カスタムセパレーターでテキストを分割
     */
    private List<String> splitWithCustomSeparator(String text, int maxTokensPerSection, String separator) {
        List<String> sections = new ArrayList<>();
        String[] parts = text.split(separator, -1); // -1で空文字列も保持
        StringBuilder currentSection = new StringBuilder();
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            
            // セパレーターで結合した場合のテキストを構築
            String candidateSection;
            if (currentSection.length() == 0) {
                candidateSection = part;
            } else {
                candidateSection = currentSection.toString() + separator + part;
            }
            
            if (estimateTokenCount(candidateSection) <= maxTokensPerSection) {
                currentSection = new StringBuilder(candidateSection);
            } else {
                // 現在のセクションを保存
                if (currentSection.length() > 0) {
                    sections.add(currentSection.toString());
                }
                
                // 新しいセクションを開始
                currentSection = new StringBuilder(part);
                
                // 1つのpartが制限を超える場合は文字数で強制分割
                if (estimateTokenCount(part) > maxTokensPerSection) {
                    currentSection = new StringBuilder();
                    int charsPerSection = maxTokensPerSection * 2;
                    for (int start = 0; start < part.length(); start += charsPerSection) {
                        int end = Math.min(start + charsPerSection, part.length());
                        sections.add(part.substring(start, end));
                    }
                }
            }
        }
        
        // 残りのセクションを追加
        if (currentSection.length() > 0) {
            sections.add(currentSection.toString());
        }
        
        return sections;
    }
    
    /**
     * デフォルトの文単位でテキストを分割
     */
    private List<String> splitWithDefaultSeparator(String text, int maxTokensPerSection) {
        List<String> sections = new ArrayList<>();
        
        // 文単位で分割を試行
        String[] sentences = text.split("[。！？]|\\n\\n");
        StringBuilder currentSection = new StringBuilder();
        
        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) continue;
            
            String candidateSection = currentSection.length() > 0 ? 
                currentSection.toString() + sentence + "。" : sentence + "。";
            
            if (estimateTokenCount(candidateSection) <= maxTokensPerSection) {
                currentSection = new StringBuilder(candidateSection);
            } else {
                // 現在のセクションを保存し、新しいセクションを開始
                if (currentSection.length() > 0) {
                    sections.add(currentSection.toString().trim());
                }
                currentSection = new StringBuilder(sentence + "。");
                
                // 1文が制限を超える場合は文字数で強制分割
                if (estimateTokenCount(currentSection.toString()) > maxTokensPerSection) {
                    String longSentence = currentSection.toString();
                    currentSection = new StringBuilder();
                    
                    // 文字数ベースで分割
                    int charsPerSection = (maxTokensPerSection * 2); // より保守的な文字数計算
                    for (int start = 0; start < longSentence.length(); start += charsPerSection) {
                        int end = Math.min(start + charsPerSection, longSentence.length());
                        sections.add(longSentence.substring(start, end));
                    }
                }
            }
        }
        
        // 残りのセクションを追加
        if (currentSection.length() > 0) {
            sections.add(currentSection.toString().trim());
        }
        
        // 空のセクションを除去
        sections.removeIf(s -> s.trim().isEmpty());
        
        return sections;
    }
    
    /**
     * 複数の埋め込みベクトルの平均を計算
     */
    private List<Double> averageEmbeddings(List<List<Double>> embeddings) {
        if (embeddings.isEmpty()) {
            return new ArrayList<>();
        }
        
        int dimensions = embeddings.get(0).size();
        List<Double> averaged = new ArrayList<>(dimensions);
        
        // 各次元の平均を計算
        for (int dim = 0; dim < dimensions; dim++) {
            double sum = 0.0;
            int count = 0;
            
            for (List<Double> embedding : embeddings) {
                if (dim < embedding.size()) {
                    sum += embedding.get(dim);
                    count++;
                }
            }
            
            averaged.add(count > 0 ? sum / count : 0.0);
        }
        
        return averaged;
    }
    
    /**
     * 1レコードを変換
     */
    private ObjectNode convertRecord(JsonNode record, int index, int total, Set<String> existingIds) {
        String id = record.get("id").asText();
        
        // 既に登録済みの場合はnullを返す
        if (existingIds.contains(id)) {
            System.out.println("⏭️  Skipping parent document " + id + " (already exists)");
            return null;
        }
        
        String title = record.get("title").asText();
        String context = record.get("context").asText();
        String question = record.has("question") ? record.get("question").asText() : null;
        
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
        
        // 長い記事の場合はセクション分割して埋め込み平均化、短い場合は通常処理
        System.out.println("  [" + (index + 1) + "/" + total + "] Generating embedding for: " + id);
        List<Double> embedding = generateAveragedEmbedding(context, apiKey);
        
        if (embedding == null || embedding.isEmpty()) {
            System.err.println("    No valid embedding generated for " + id);
            embedding = new ArrayList<>();
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

    /**
     * Solrコアから登録済みのIDを取得
     */
    private static Set<String> getExistingIdsFromSolr(String solrUrl, String coreName) throws Exception {
        Set<String> existingIds = new HashSet<>();
        
        try (HttpSolrClient solrClient = new HttpSolrClient.Builder(solrUrl + "/" + coreName).build()) {
            // 登録済みドキュメントのIDを全件取得
            SolrQuery query = new SolrQuery("*:*");
            query.setFields("id");
            query.setRows(Integer.MAX_VALUE); // 全件取得
            
            System.out.println("🔍 Querying Solr for existing document IDs...");
            QueryResponse response = solrClient.query(query);
            
            response.getResults().forEach(doc -> {
                String id = (String) doc.getFieldValue("id");
                if (id != null) {
                    existingIds.add(id);
                }
            });
            
            System.out.println("📊 Retrieved " + existingIds.size() + " existing document IDs from Solr");
        }
        
        return existingIds;
    }
}
