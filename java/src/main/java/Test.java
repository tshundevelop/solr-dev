import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Test {
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
    
    // 💡 キャッシュファイルのベースディレクトリのみを定義
    private static final String CACHE_BASE_DIR = "cache/"; 
    private static final String CACHE_FILE_NAME = "embedding_data.bin"; // 共通のファイル名
    
    // 埋め込みベクトルをキャッシュするための静的Map (キーはキーワードのみ)
    private static final Map<String, List<Double>> EMBEDDING_CACHE = new ConcurrentHashMap<>();
    
    // 💡 使用する埋め込みモデル名を定数として定義
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; 

    public static void main(String[] args) {
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Using default keyword 'Solr vector search'.");
            keyword = "Solr vector search";
        }

        String apiKey = "";
        try {
            // ... (apiKey取得ロジックは変更なし。DotEnvLoaderが別途必要) ...
            apiKey = System.getProperty(API_KEY_ENV_VAR);
            if (apiKey == null) {
                System.err.println("APIキーが見つかりません。DotEnvLoaderが実行されているか確認してください。");
                apiKey = "DUMMY_API_KEY"; 
            }
        } catch (Exception e) {
            System.err.println("エラー: APIキーのロード中に問題が発生しました: " + e.getMessage());
            return;
        }

        // 💡 mainメソッドではキャッシュのロード/保存は行わない (getEmbeddingSearchResult内でモデルごとに処理)
        
        String processedKeyword = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"});
        System.out.println("Keyword after word split: " + processedKeyword);

        try {
            SolrDocumentList results = getEmbeddingSearchResult(
                "JaQuAD_dev_all", 
                processedKeyword, 
                "context_vec", 
                apiKey, 
                10, 
                EMBEDDING_MODEL // モデル名を渡す
            );

            if (results != null) {
                System.out.println("\n--- 検索結果 ---");
                for (SolrDocument result : results) {
                    System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
                    System.out.println("title: " + result.getFieldValue("title"));
                }
            }
        } catch (Exception e) {
            System.err.println("検索中に致命的なエラーが発生しました。");
            e.printStackTrace();
        } 
        // finally ブロックから saveCacheToFile() を削除
    }

    public static SolrDocumentList getEmbeddingSearchResult(
        String coreName,
        String keyword,
        String field,
        String apiKey,
        Integer topk,
        String modelName // 💡 メソッド引数にモデル名を追加
    ) throws Exception {
        String solrUrl = "http://solr:8983/solr/" + coreName;
        List<Double> embedding = null;
        
        // 💡 モデル名に応じたキャッシュファイルパスを決定
        String modelCachePath = CACHE_BASE_DIR + modelName.replace('/', '_') + "/" + CACHE_FILE_NAME;
        
        // 💡 検索処理の開始時にキャッシュをロード
        loadCacheFromFile(modelCachePath); 

        // 1. キャッシュの確認 (キーはキーワードのみ)
        if (EMBEDDING_CACHE.containsKey(keyword)) {
            embedding = EMBEDDING_CACHE.get(keyword);
            System.out.println("✅ キャッシュから埋め込みベクトルをロードしました (モデル: " + modelName + ")。");
        } 
        
        // 2. キャッシュにない場合
        if (embedding == null) {
            System.out.println("⏳ 埋め込みベクトルを新規生成します (モデル: " + modelName + ")...");
            if (field.contains("openai")) {
                embedding = OpenAIEmbeddingClient.getEmbeddingFromOpenAI(keyword, apiKey); 
            } else {
                embedding = EmbeddingClient.getEmbeddingFromPython(keyword);
            }
            
            // 3. キャッシュに保存
            if (embedding != null && !embedding.isEmpty()) {
                EMBEDDING_CACHE.put(keyword, List.copyOf(embedding)); 
                System.out.println("💾 埋め込みベクトルをキャッシュに保存しました。");
                
                // 💡 新しい埋め込みが生成されたので、ファイルに保存
                saveCacheToFile(modelCachePath); 
            } else {
                throw new Exception("埋め込みベクトルの取得に失敗しました。");
            }
        }
        
        // --- Solr検索処理 ---
        try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {
            double[] rawDoubleVector = embedding.stream().mapToDouble(Double::doubleValue).toArray();
            float[] queryVector = new float[rawDoubleVector.length];
            for (int i = 0; i < rawDoubleVector.length; i++) {
                queryVector[i] = (float) rawDoubleVector[i];
            }
            String vectorString = floatArrayToJson(queryVector);

            ModifiableSolrParams params = new ModifiableSolrParams();
            params.set("q", String.format("{!knn f=%s topK=%s}", field, topk) + vectorString);
            params.set("fl", "id,score,title,context");

            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            QueryResponse response = queryRequest.process(solr);
            return response.getResults();
        }
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
    
    // ----------------------------------------------------
    // バイナリキャッシュ永続化メソッド (ファイルパスを引数で受け取るように変更)
    // ----------------------------------------------------

    /**
     * キャッシュファイルを読み込み、EMBEDDING_CACHEにロードします。（バイナリ）
     */
    @SuppressWarnings("unchecked")
    private static void loadCacheFromFile(String filePath) {
        EMBEDDING_CACHE.clear(); // 💡 既存のキャッシュをクリア (異なるモデルのキャッシュをロードするため)
        File cacheFile = new File(filePath);
        
        if (cacheFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cacheFile);
                BufferedInputStream bis = new BufferedInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(bis)) {

                Object loadedObject = ois.readObject();
                
                if (loadedObject instanceof Map) {
                    EMBEDDING_CACHE.putAll((Map<String, List<Double>>) loadedObject);
                    System.out.println("✅ キャッシュをファイルからロードしました: " + EMBEDDING_CACHE.size() + "件");
                }
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("エラー: キャッシュファイルの読み込み中にエラーが発生しました。ファイルは再作成されます: " + e.getMessage());
            }
        } else {
            File cacheDir = cacheFile.getParentFile();
            if (cacheDir != null && !cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            System.out.println("ℹ️ キャッシュファイルが見つかりません。新規作成されます。");
        }
    }

    /**
     * 現在のEMBEDDING_CACHEの内容をバイナリファイルに保存します。
     */
    private static void saveCacheToFile(String filePath) {
        File cacheFile = new File(filePath);

        File cacheDir = cacheFile.getParentFile();
        if (cacheDir != null && !cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(cacheFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            
            oos.writeObject(new HashMap<>(EMBEDDING_CACHE)); 
            System.out.println("💾 キャッシュをファイルに保存しました: " + EMBEDDING_CACHE.size() + "件");
        } catch (IOException e) {
            System.err.println("エラー: キャッシュファイルの保存中にエラーが発生しました: " + e.getMessage());
        }
    }
}