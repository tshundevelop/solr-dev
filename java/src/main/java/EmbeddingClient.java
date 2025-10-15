// java_service/src/main/java/EmbeddingClient.java
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EmbeddingClient {

    private static final String PYTHON_SERVICE_URL = "http://python:5000/embed";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {
            String text = "JavaとPythonの連携を試しています。";
            List<Double> embeddingVector = getEmbeddingFromPython(text);

            if (embeddingVector != null) {
                System.out.println("Received embedding vector with " + embeddingVector.size() + " dimensions.");
                System.out.println("First few values: " + embeddingVector.subList(0, Math.min(5, embeddingVector.size())));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static List<Double> getEmbeddingFromPython(String text) throws Exception {
        // リクエストURLを構築
        URL url = new URL(PYTHON_SERVICE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // 接続設定
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true); // POSTリクエストを許可

        // リクエストボディをJSON形式で作成
        String requestBody = objectMapper.writeValueAsString(new RequestPayload(text));

        // リクエストボディをOutputStreamに書き込み
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
            writer.write(requestBody);
        }

        // レスポンスコードを確認
        int responseCode = conn.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            // JSONレスポンスを読み込み
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
                String jsonResponse = responseBuilder.toString();

                // JSONレスポンスをパース
                JsonNode rootNode = objectMapper.readTree(jsonResponse);
                JsonNode embeddingNode = rootNode.get("embedding");
                
                // JSON配列をList<Double>にマッピング
                return objectMapper.convertValue(embeddingNode, List.class);
            }
        } else {
            System.err.println("Error from Python service: " + responseCode);
            // エラーレスポンスの読み取り
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                String errorBody = errorReader.lines().reduce("", String::concat);
                System.err.println("Error body: " + errorBody);
            }
            return null;
        }
    }

    // JSONシリアライズ用のヘルパークラス
    static class RequestPayload {
        public String text;

        public RequestPayload(String text) {
            this.text = text;
        }
    }
}