import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Properties;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAI API (Embeddings) を利用するJavaクライアント。
 * APIキーは環境変数 'OPENAI_API_KEY' から読み込みます。
 * シンプルなrate limit防止機能付き。
 */
public class OpenAIEmbeddingClient {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; // 推奨される埋め込みモデル
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY"; // 環境変数名
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // レート制限対策 - より安全な設定
    private static final long REQUEST_INTERVAL_MS = 2000; // リクエスト間隔2秒 (30リクエスト/分)
    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final AtomicInteger requestCount = new AtomicInteger(0);

    /**
     * シンプルなrate limit制御
     * リクエスト間隔を調整してOpenAI APIの制限を回避
     */
    private static void preventRateLimit() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime.get();
        
        if (timeSinceLastRequest < REQUEST_INTERVAL_MS) {
            long sleepTime = REQUEST_INTERVAL_MS - timeSinceLastRequest;
            Thread.sleep(sleepTime);
        }
        
        lastRequestTime.set(System.currentTimeMillis());
        int count = requestCount.incrementAndGet();
        
        // 100リクエストごとにステータス表示
        if (count % 50 == 0) {
            System.out.println("📊 [API Stats] Processed " + count + " requests successfully");
        }
    }

    public static void main(String[] args) {
        try {
            // 設定ファイルを読み取る処理
			Properties property = new Properties();
			property.load(new FileInputStream(PROPERTY_FILE));

            // 環境変数からのAPIキーのロード
            String apiKey = property.getProperty(API_KEY_ENV_VAR);
            
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new Exception("APIキーが環境変数 '" + API_KEY_ENV_VAR + "' に設定されていません。");
            }
            
            System.out.println("APIキーを環境変数からロードしました。OpenAI APIに接続します。");

            String text = "JavaとOpenAI APIの連携を試しています。埋め込みベクトルを取得します。";
            List<Double> embeddingVector = getEmbeddingFromOpenAI(text, apiKey);

            if (embeddingVector != null) {
                System.out.println("--- 成功 ---");
                System.out.println("取得した埋め込みベクトルの次元数: " + embeddingVector.size());
                System.out.println("最初の5つの値: " + embeddingVector.subList(0, Math.min(5, embeddingVector.size())));
            } else {
                System.out.println("--- 失敗 ---");
                System.out.println("埋め込みベクトルの取得に失敗しました。エラーを確認してください。");
            }
        } catch (Exception e) {
            System.err.println("エラー: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * OpenAI APIを呼び出し、指定されたテキストの埋め込みベクトルを取得します。
     * シンプルなrate limit制御付き。
     * @param text 埋め込みを取得するテキスト
     * @param apiKey OpenAI APIキー
     * @return 埋め込みベクトル (List<Double>)
     */
    public static List<Double> getEmbeddingFromOpenAI(String text, String apiKey) throws Exception {
        return getEmbeddingWithRetry(text, apiKey, 0);
    }
    
    /**
     * リトライ機能付きのembedding取得
     * 絶対に成功するまでリトライを続ける
     */
    private static List<Double> getEmbeddingWithRetry(String text, String apiKey, int retryCount) throws Exception {
        // 無制限リトライ - 絶対に失敗させない
        if (retryCount > 20) {
            System.err.println("❌ 20回リトライしても失敗 - より長い待機時間で継続...");
        }
        
        // Rate limit防止
        preventRateLimit();
        
        URL url = new URL(OPENAI_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // 接続設定
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey); // 認証ヘッダーの設定
        conn.setDoOutput(true); // POSTリクエストを許可

        // リクエストボディをJacksonで安全に構築
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", EMBEDDING_MODEL);
        payload.put("input", text);
        String requestBody = objectMapper.writeValueAsString(payload);

        // リクエストボディをOutputStreamに書き込み
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(requestBody);
        }

        // レスポンスコードを確認
        int responseCode = conn.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            // 成功時のJSONレスポンスを読み込み
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
                String jsonResponse = responseBuilder.toString();

                // JSONレスポンスをパースし、埋め込みベクトルを抽出: data[0].embedding
                JsonNode rootNode = objectMapper.readTree(jsonResponse);
                JsonNode embeddingNode = rootNode.path("data").path(0).path("embedding");

                // JSON配列をList<Double>にマッピング
                if (embeddingNode.isArray()) {
                    return objectMapper.convertValue(
                        embeddingNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)
                    );
                }
                System.err.println("警告: レスポンスJSONで 'data[0].embedding' が見つからないか、配列ではありません。");
                return null;
            }
        } else {
            // エラーレスポンスの読み取り
            String errorBody = "";
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                errorBody = errorReader.lines().reduce("", String::concat);
            } catch (IOException ignored) { /* エラーボディがない場合も考慮 */ }

            // Rate limit errorの場合は絶対に成功するまで無限リトライ
            if (responseCode == 429) {
                long waitTime;
                if (retryCount <= 10) {
                    waitTime = Math.min(5000L * (1L << Math.min(retryCount, 6)), 120000L); // 5秒から最大2分まで
                } else {
                    waitTime = 180000L; // 10回超過後は3分待機
                }
                
                System.err.println("🔄 Rate limit detected (attempt " + (retryCount + 1) + "), waiting " + (waitTime/1000) + " seconds - WILL RETRY UNTIL SUCCESS");
                Thread.sleep(waitTime);
                
                // 絶対に成功するまで無限リトライ
                return getEmbeddingWithRetry(text, apiKey, retryCount + 1);
            }

            // その他のサーバーエラーも無限リトライ
            if (responseCode >= 500) {
                System.err.println("🔄 Server error " + responseCode + " (attempt " + (retryCount + 1) + "), waiting 10 seconds and retrying...");
                Thread.sleep(10000);
                return getEmbeddingWithRetry(text, apiKey, retryCount + 1);
            }

            // HTTP 400エラー（トークン数超過）の場合は分割して平均化
            if (responseCode == 400 && errorBody.contains("maximum context length")) {
                System.err.println("⚠️ Token limit exceeded, splitting text and averaging embeddings...");
                return getEmbeddingBySplitting(text, apiKey);
            }

            System.err.println("OpenAI APIからのエラー (HTTP " + responseCode + ")");
            System.err.println("エラーボディ: " + errorBody);
            return null;
        }
    }
    
    /**
     * テキストを分割して埋め込みベクトルを平均化
     * 8192トークン制限を超える場合に使用
     */
    private static List<Double> getEmbeddingBySplitting(String text, String apiKey) throws Exception {
        // 「。」で分割
        String[] sentences = text.split("。");
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int maxChunkLength = 2000; // 約2000文字（より安全なサイズ）
        
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) continue;
            
            // 「。」を復元
            if (i < sentences.length - 1) {
                sentence = sentence + "。";
            }
            
            // チャンクサイズチェック
            if (currentChunk.length() + sentence.length() > maxChunkLength && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            
            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
        }
        
        // 最後のチャンク
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        if (chunks.isEmpty()) {
            System.err.println("❌ Failed to split text into chunks");
            return null;
        }
        
        System.err.println("📝 Split into " + chunks.size() + " chunks for embedding");
        
        // 各チャンクのembeddingを取得（再帰を避けるため直接API呼び出し）
        List<List<Double>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            System.err.println("   → Processing chunk " + (i+1) + "/" + chunks.size() + " (" + chunk.length() + " chars)");
            
            // チャンクがまだ大きすぎる場合は文字数で強制分割
            if (chunk.length() > 2000) {
                System.err.println("   ⚠️ Chunk still too large, splitting by character count");
                int subChunkSize = 1500;
                for (int start = 0; start < chunk.length(); start += subChunkSize) {
                    int end = Math.min(start + subChunkSize, chunk.length());
                    String subChunk = chunk.substring(start, end);
                    List<Double> embedding = getEmbeddingDirect(subChunk, apiKey);
                    if (embedding != null) {
                        allEmbeddings.add(embedding);
                    }
                }
            } else {
                List<Double> embedding = getEmbeddingDirect(chunk, apiKey);
                if (embedding != null) {
                    allEmbeddings.add(embedding);
                }
            }
        }
        
        if (allEmbeddings.isEmpty()) {
            System.err.println("❌ Failed to get embeddings for any chunk");
            return null;
        }
        
        // ベクトルを平均化
        int dimensions = allEmbeddings.get(0).size();
        List<Double> averagedEmbedding = new ArrayList<>(dimensions);
        
        for (int i = 0; i < dimensions; i++) {
            double sum = 0.0;
            for (List<Double> embedding : allEmbeddings) {
                sum += embedding.get(i);
            }
            averagedEmbedding.add(sum / allEmbeddings.size());
        }
        
        System.err.println("✅ Successfully averaged " + allEmbeddings.size() + " chunk embeddings");
        return averagedEmbedding;
    }
    
    /**
     * 直接API呼び出し（リトライのみ、分割処理なし）
     * 無限再帰を防ぐため、getEmbeddingBySplittingから呼ばれる
     */
    private static List<Double> getEmbeddingDirect(String text, String apiKey) throws Exception {
        int maxRetries = 5;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Rate limit防止
                preventRateLimit();
                
                URL url = new URL(OPENAI_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);

                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("model", EMBEDDING_MODEL);
                payload.put("input", text);
                String requestBody = objectMapper.writeValueAsString(payload);

                try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(requestBody);
                }

                int responseCode = conn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder responseBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBuilder.append(line);
                        }
                        String jsonResponse = responseBuilder.toString();

                        JsonNode rootNode = objectMapper.readTree(jsonResponse);
                        JsonNode embeddingNode = rootNode.path("data").path(0).path("embedding");

                        if (embeddingNode.isArray()) {
                            return objectMapper.convertValue(
                                embeddingNode,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)
                            );
                        }
                    }
                } else if (responseCode == 429) {
                    // Rate limitの場合のみリトライ
                    long waitTime = 5000L * (1L << Math.min(attempt, 4));
                    System.err.println("   🔄 Rate limit (attempt " + (attempt + 1) + "), waiting " + (waitTime/1000) + "s");
                    Thread.sleep(waitTime);
                    continue;
                } else {
                    // その他のエラーは分割済みなのでnullを返す
                    String errorBody = "";
                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        errorBody = errorReader.lines().reduce("", String::concat);
                    } catch (IOException ignored) {}
                    System.err.println("   ❌ API error " + responseCode + " for direct call: " + errorBody);
                    return null;
                }
            } catch (Exception e) {
                System.err.println("   ⚠️ Error in direct call (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (attempt < maxRetries - 1) {
                    Thread.sleep(2000);
                }
            }
        }
        
        return null;
    }
}
