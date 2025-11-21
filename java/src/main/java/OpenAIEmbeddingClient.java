import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Properties;
import java.io.FileInputStream;

/**
 * OpenAI API (Embeddings) を利用するJavaクライアント。
 * APIキーは環境変数 'OPENAI_API_KEY' から読み込みます。
 */
public class OpenAIEmbeddingClient {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "text-embedding-3-large"; // 推奨される埋め込みモデル
    private static final String PROPERTY_FILE = "api_key.env";
    private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY"; // 環境変数名
    private static final ObjectMapper objectMapper = new ObjectMapper();    

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
     * @param text 埋め込みを取得するテキスト
     * @param apiKey OpenAI APIキー
     * @return 埋め込みベクトル (List<Double>)
     */
    public static List<Double> getEmbeddingFromOpenAI(String text, String apiKey) throws Exception {
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

            System.err.println("OpenAI APIからのエラー (HTTP " + responseCode + ")");
            System.err.println("エラーボディ: " + errorBody);
            return null;
        }
    }
}
