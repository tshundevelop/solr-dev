import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Properties;
import java.io.FileInputStream;

public class EmbedSearch {
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    private static final String PROPERTY_FILE = "api_key.env";
    
    private static final String CACHE_BASE_DIR = "cache/";
    private static final String CACHE_FILE_NAME = "embedding-cache.json";

    private static final ConcurrentMap<String, ConcurrentMap<String, float[]>> IN_MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Boolean> CACHE_FILE_LOADED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> CACHE_FILE_LOCKS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CACHE_FILE_TYPE = new TypeToken<Map<String, List<Double>>>() {}.getType();
    
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; 

    public static void main(String[] args) {
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Using default keyword.");
            keyword = "芥川賞を受賞した人は誰ですか。";
        }

        String apiKey = "";
        try {
            Properties property = new Properties();
            property.load(new FileInputStream(PROPERTY_FILE));
            apiKey = property.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null) {
                System.err.println("APIキーが見つかりません。");
                apiKey = "DUMMY_API_KEY"; 
            }
        } catch (Exception e) {
            System.err.println("エラー: APIキーのロード中に問題が発生しました: " + e.getMessage());
            return;
        }

        String[] keywordList = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"}, 2);
        System.out.println("Keyword after word split: " + String.join(", ", keywordList));

        try {
            // is_chunk = false のドキュメントを検索
            Object[] resultsNonChunkObj = getEmbeddingSearchResultWithChunkFilter(
                "validation2000",
                keywordList,
                "context_vector",
                "id,original_doc_id,score,title,context",
                apiKey,
                EMBEDDING_MODEL,
                false
            );

            System.out.println("\n=== Results for is_chunk=false ===");
            SolrDocumentList resultsNonChunk = (SolrDocumentList) resultsNonChunkObj[0];
            resultsNonChunk = Main.sliceSolrDocumentList(resultsNonChunk, 10);
            if (resultsNonChunk != null) {
                for (SolrDocument result : resultsNonChunk) {
                    System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                    System.out.println("title: " + result.getFieldValue("title"));
                }
            }

            // is_chunk = true のドキュメントを検索
            Object[] resultsChunkObj = getEmbeddingSearchResultWithChunkFilter(
                "validation2000",
                keywordList,
                "chunk_vector",
                "id,original_doc_id,score,title,context",
                apiKey,
                EMBEDDING_MODEL,
                true
            );

            System.out.println("\n=== Results for is_chunk=true ===");
            SolrDocumentList resultsChunk = (SolrDocumentList) resultsChunkObj[0];
            resultsChunk = Main.sliceSolrDocumentList(resultsChunk, 10);
            if (resultsChunk != null) {
                for (SolrDocument result : resultsChunk) {
                    System.out.println("Chunk ID: " + result.getFieldValue("id") + ", Original Doc ID: " + result.getFieldValue("original_doc_id") + ", Score: " + result.getFieldValue("score"));
                    System.out.println("title: " + result.getFieldValue("title"));
                }
            }
        } catch (Exception e) {
            System.err.println("検索中に致命的なエラーが発生しました。");
            e.printStackTrace();
        } 
    }

    /**
     * is_chunkフィールドでフィルタリングして埋め込みベクトル検索を実行
     * 
     * @param coreName Solrコア名
     * @param keywordList 検索キーワードリスト
     * @param field ベクトルフィールド名
     * @param apiKey OpenAI APIキー
     * @param modelName モデル名
     * @param isChunk is_chunkフィルタ値（true/false）
     * @return 検索結果
     */
    public static Object[] getEmbeddingSearchResultWithChunkFilter(
        String coreName,
        String[] keywordList,
        String field,
        String targetField,
        String apiKey,
        String modelName,
        boolean isChunk
    ) throws Exception {
        String solrUrl = "http://solr:8983/solr/" + coreName;
        String keyword = String.join(" ", keywordList);
        float[] queryVector = getOrCreateEmbedding(keyword, field, apiKey, modelName);

        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            String vectorString = floatArrayToJson(queryVector);

            // is_chunkフィルタを追加
            String filterQuery = "is_chunk:" + isChunk;

            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", String.format("{!knn f=%s topK=10000}", field) + vectorString);
            params.set("fq", filterQuery); // フィルタクエリでis_chunkを指定
            params.set("fl", targetField);
            params.set("rows", 10000);

            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            QueryResponse response = queryRequest.process(solr);
            SolrDocumentList docs = response.getResults();
            
            return new Object[]{docs, params};
        }
    }

    public static float[] getOrCreateEmbedding(String keyword, String field, String apiKey, String modelName) throws Exception {
        float[] queryVector = getCachedVector(modelName, keyword);
        if (queryVector != null) {
            return queryVector;
        }
        System.out.println("⏳ 埋め込みベクトルを新規生成します (モデル: " + modelName + ")...");
        List<Double> embedding;
        if (field.contains("openai")) {
            embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(keyword, apiKey);
        } else {
            embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(keyword, apiKey);
        }
        if (embedding == null || embedding.isEmpty()) {
            throw new Exception("埋め込みベクトルの取得に失敗しました。");
        }
        queryVector = toFloatArray(embedding);
        cacheVector(modelName, keyword, queryVector);
        System.out.println("💾 埋め込みベクトルをJSONキャッシュに保存しました。");
        return queryVector;
    }

    public static String floatArrayToJson(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static float[] getCachedVector(String modelName, String keyword) {
        ConcurrentMap<String, float[]> memoryCache = getInMemoryCache(modelName);
        float[] inMemory = memoryCache.get(keyword);
        if (inMemory != null) {
            System.out.println("✅ メモリキャッシュから埋め込みベクトルをロードしました (モデル: " + modelName + ")。");
            return inMemory;
        }

        ensureCacheLoadedFromDisk(modelName);

        float[] persisted = memoryCache.get(keyword);
        if (persisted != null) {
            System.out.println("✅ JSONキャッシュから埋め込みベクトルをロードしました (モデル: " + modelName + ")。");
        }
        return persisted;
    }

    private static void cacheVector(String modelName, String keyword, float[] vector) {
        ensureCacheLoadedFromDisk(modelName);
        float[] safeCopy = vector.clone();
        getInMemoryCache(modelName).put(keyword, safeCopy);
        persistCacheToDisk(modelName);
    }

    private static ConcurrentMap<String, float[]> getInMemoryCache(String modelName) {
        return IN_MEMORY_CACHE.computeIfAbsent(modelName, key -> new ConcurrentHashMap<>());
    }

    private static void ensureCacheLoadedFromDisk(String modelName) {
        if (Boolean.TRUE.equals(CACHE_FILE_LOADED.get(modelName))) {
            return;
        }

        Object lock = CACHE_FILE_LOCKS.computeIfAbsent(modelName, key -> new Object());
        synchronized (lock) {
            if (Boolean.TRUE.equals(CACHE_FILE_LOADED.get(modelName))) {
                return;
            }

            Path cacheFile = resolveCacheFile(modelName);
            if (!Files.exists(cacheFile)) {
                CACHE_FILE_LOADED.put(modelName, true);
                return;
            }

            try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                Map<String, List<Double>> persisted = GSON.fromJson(reader, CACHE_FILE_TYPE);
                if (persisted != null) {
                    ConcurrentMap<String, float[]> memoryCache = getInMemoryCache(modelName);
                    for (Map.Entry<String, List<Double>> entry : persisted.entrySet()) {
                        List<Double> values = entry.getValue();
                        if (values != null) {
                            memoryCache.putIfAbsent(entry.getKey(), toFloatArray(values));
                        }
                    }
                    System.out.println("キャッシュファイルを正常に読み込みました (エントリ数: " + persisted.size() + ", モデル: " + modelName + ")");
                }
            } catch (com.google.gson.JsonSyntaxException e) {
                System.err.println("❌ JSONキャッシュファイルが破損しています。削除して再生成します (モデル: " + modelName + ")");
                System.err.println("エラー詳細: " + e.getMessage());
                try {
                    Files.deleteIfExists(cacheFile);
                    System.err.println("✅ 破損したキャッシュファイルを削除しました: " + cacheFile);
                } catch (IOException deleteError) {
                    System.err.println("キャッシュファイルの削除に失敗: " + deleteError.getMessage());
                }
            } catch (IOException e) {
                System.err.println("JSONキャッシュの読み込みに失敗しました (モデル: " + modelName + "): " + e.getMessage());
            }

            CACHE_FILE_LOADED.put(modelName, true);
        }
    }

    private static void persistCacheToDisk(String modelName) {
        Object lock = CACHE_FILE_LOCKS.computeIfAbsent(modelName, key -> new Object());
        ConcurrentMap<String, float[]> memoryCache = getInMemoryCache(modelName);

        synchronized (lock) {
            Path cacheFile = resolveCacheFile(modelName);
            try {
                Path parent = cacheFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Map<String, float[]> snapshot = new HashMap<>(memoryCache);
                try (BufferedWriter writer = Files.newBufferedWriter(
                        cacheFile,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )) {
                    GSON.toJson(snapshot, writer);
                }
            } catch (IOException e) {
                System.err.println("JSONキャッシュの書き込みに失敗しました (モデル: " + modelName + "): " + e.getMessage());
            }

            CACHE_FILE_LOADED.put(modelName, true);
        }
    }

    private static Path resolveCacheFile(String modelName) {
        String safeModelName = modelName.replace('/', '_');
        return Paths.get(CACHE_BASE_DIR, safeModelName, CACHE_FILE_NAME);
    }

    private static float[] toFloatArray(List<Double> source) {
        float[] vector = new float[source.size()];
        for (int i = 0; i < source.size(); i++) {
            vector[i] = source.get(i).floatValue();
        }
        return vector;
    }
}
