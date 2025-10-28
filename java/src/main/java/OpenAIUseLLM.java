import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Properties;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.BufferedWriter;
import java.nio.file.StandardOpenOption;

/**
 * OpenAI のチャットLLMを呼び出すユーティリティ。
 * 既存コードと同様に HttpURLConnection + Jackson を利用します。
 */
public class OpenAIUseLLM {
	private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
	private static final String DEFAULT_MODEL = "gpt-4o-mini"; // 推奨の軽量モデル
	private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";
	private static final String PROPERTY_FILE = "api_key.env";
	private static final ObjectMapper mapper = new ObjectMapper();

	// ===== Paraphrase cache (per model) =====
	private static final String CACHE_BASE_DIR = "cache"; // 同プロジェクトのEmbedSearchに合わせる
	private static final String PARAPHRASE_DIR = "paraphrase";
	private static final String PARAPHRASE_CACHE_FILE = "paraphrase-cache.json";

	// model -> (key -> map(original->candidates))
	private static final ConcurrentMap<String, ConcurrentMap<String, Map<String, List<String>>>> PARA_MEMORY_CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, Boolean> PARA_CACHE_LOADED = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, Object> PARA_CACHE_LOCKS = new ConcurrentHashMap<>();

    // 簡易動作確認
	public static void main(String[] args) throws Exception {
		// String result = chat(
		// 	"あなたは簡潔に答えるアシスタントです。",
		// 	"OpenAIのチャットモデルをJavaからどう呼ぶ？1行で"
		// );
		// System.out.println("--- LLM 出力 ---\n" + result);
        String[] words = new String[] {"大仏", "高さ", "奈良"};
        int N = 2; // 先頭から2語を言い換え
        String[] out = OpenAIUseLLM.paraphraseTopN(words, N);
        System.out.println("Original words: " + String.join(", ", words));
        System.out.println("Paraphrased words: " + String.join(", ", out));
	}

	// --- Public APIs ---
	public static String chat(String systemPrompt, String userPrompt) throws Exception {
		String apiKey = getApiKeyOrThrow();
		return chat(systemPrompt, userPrompt, apiKey, DEFAULT_MODEL, 0.5);
	}

	public static String chat(String systemPrompt, String userPrompt, String apiKey) throws Exception {
		return chat(systemPrompt, userPrompt, apiKey, DEFAULT_MODEL, 0.5);
	}

	public static String chat(String systemPrompt, String userPrompt, String apiKey, String model) throws Exception {
		return chat(systemPrompt, userPrompt, apiKey, model, 0.5);
	}

	/**
	 * OpenAI Chat Completions を叩いて最初の候補の content を返す。
	 */
	public static String chat(String systemPrompt, String userPrompt, String apiKey, String model, double temperature) throws Exception {
		if (systemPrompt == null) systemPrompt = "";
		if (userPrompt == null) userPrompt = "";
		if (model == null || model.isEmpty()) model = DEFAULT_MODEL;

		// リクエストJSONの構築
		ObjectNode root = mapper.createObjectNode();
		root.put("model", model);
		root.put("temperature", temperature);
		ArrayNode messages = root.putArray("messages");
		ObjectNode sys = messages.addObject();
		sys.put("role", "system");
		sys.put("content", systemPrompt);
		ObjectNode usr = messages.addObject();
		usr.put("role", "user");
		usr.put("content", userPrompt);

		String requestBody = mapper.writeValueAsString(root);

		// HTTP 呼び出し
		URL url = new URL(OPENAI_CHAT_URL);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Authorization", "Bearer " + apiKey);
		conn.setDoOutput(true);

		try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
			writer.write(requestBody);
		}

		int code = conn.getResponseCode();
		if (code == HttpURLConnection.HTTP_OK) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) sb.append(line);
				String json = sb.toString();

				JsonNode rootNode = mapper.readTree(json);
				JsonNode contentNode = rootNode.path("choices").path(0).path("message").path("content");
				return contentNode.isMissingNode() ? "" : contentNode.asText("");
			}
		} else {
			StringBuilder err = new StringBuilder();
			try (BufferedReader er = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
				String line;
				while ((line = er.readLine()) != null) err.append(line);
			}
			throw new RuntimeException("OpenAI Chat API error HTTP " + code + ": " + err.toString());
		}
	}

	// --- Helpers ---
	private static String getApiKeyOrThrow() {
		try {
			Properties property = new Properties();
			property.load(new FileInputStream(PROPERTY_FILE));
			String apiKey = property.getProperty(API_KEY_ENV_VAR);
			if (apiKey == null || apiKey.trim().isEmpty()) {
				throw new IllegalStateException("APIキーが見つかりません。'" + PROPERTY_FILE + "' に '" + API_KEY_ENV_VAR + "' を設定してください。");
			}
			return apiKey.trim();
		} catch (Exception e) {
			throw new RuntimeException("APIキーのロードに失敗しました: " + e.getMessage(), e);
		}
	}

	// ===== Paraphrase utilities =====
	/**
	 * 単語群に対して、それぞれ最大 k 件の言い換え候補を生成します。
	 * 扱いやすいよう、マップ形式(original -> candidates)と生テキストを含む結果を返します。
	 */
	public static ParaphraseResult paraphraseWords(String[] words, int k) throws Exception {
		List<String> list = new ArrayList<>();
		if (words != null) {
			for (String w : words) {
				if (w != null && !w.trim().isEmpty()) {
					list.add(w);
				}
			}
		}
		return paraphraseWords(list, k, getApiKeyOrThrow(), DEFAULT_MODEL);
	}

	public static ParaphraseResult paraphraseWords(List<String> words, int k, String apiKey, String model) throws Exception {
		if (words == null || words.isEmpty() || k <= 0) {
			return ParaphraseResult.empty();
		}

		// 1) キャッシュヒットを確認
		Map<String, List<String>> cached = getCachedParaphraseMap(model, words, k);
		if (cached != null) {
			String m = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
			System.out.println("✅ パラフレーズをキャッシュからロードしました (model: " + m + ")。");
			return new ParaphraseResult(cached, "", true, null, true);
		}

		// リクエストJSONの構築（JSON出力を強制するため response_format を使用）
		ObjectNode root = mapper.createObjectNode();
		root.put("model", model != null && !model.isEmpty() ? model : DEFAULT_MODEL);
		root.put("temperature", 0.7);
		ObjectNode respFmt = mapper.createObjectNode();
		respFmt.put("type", "json_object");
		root.set("response_format", respFmt);

		ArrayNode messages = root.putArray("messages");
		ObjectNode sys = messages.addObject();
		sys.put("role", "system");
		sys.put("content", String.join("\n",
			"あなたは日本語のパラフレーズ生成器です。",
			"与えられた各単語について、短い言い換え候補を最大k件ずつ生成してください。",
            "言い換えには「」や（）などの括弧や記号は含めないでください。あくまで単語のみを返してください。",
            "言い換えをするときは、与えられた各単語を合わせて考慮するのではなく、完全に独立して処理してください。",
			"出力は必ず次のJSON形式のみで返してください。説明文や余計な文字は含めないでください。",
			"{\"paraphrases\":[{\"original\":\"<word>\",\"candidates\":[\"...\"]}]}"
		));
		ObjectNode usr = messages.addObject();
		usr.put("role", "user");
		ObjectNode payload = mapper.createObjectNode();
		ArrayNode arr = payload.putArray("words");
		for (String w : words) arr.add(w);
		payload.put("k", k);
		usr.put("content", payload.toString());

	String requestBody = mapper.writeValueAsString(root);

		// HTTP 呼び出し
		URL url = new URL(OPENAI_CHAT_URL);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Authorization", "Bearer " + apiKey);
		conn.setDoOutput(true);

		try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
			writer.write(requestBody);
		}

		int code = conn.getResponseCode();
		String raw;
		if (code == HttpURLConnection.HTTP_OK) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) sb.append(line);
				String json = sb.toString();

				JsonNode rootNode = mapper.readTree(json);
				JsonNode contentNode = rootNode.path("choices").path(0).path("message").path("content");
				raw = contentNode.isMissingNode() ? "" : contentNode.asText("");
			}
		} else {
			StringBuilder err = new StringBuilder();
			try (BufferedReader er = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
				String line;
				while ((line = er.readLine()) != null) err.append(line);
			}
			throw new RuntimeException("OpenAI Chat API error HTTP " + code + ": " + err.toString());
		}

		// 返却テキスト(raw)はJSON想定。解析する。
		try {
			JsonNode parsed = mapper.readTree(raw);
			Map<String, List<String>> map = new HashMap<>();
			for (JsonNode node : parsed.path("paraphrases")) {
				String original = node.path("original").asText("");
				List<String> candidates = new ArrayList<>();
				for (JsonNode c : node.path("candidates")) {
					String s = c.asText("").trim();
					if (!s.isEmpty()) candidates.add(s);
				}
				if (!original.isEmpty()) {
					// 重複削除＆上限k
					List<String> uniq = new ArrayList<>(new LinkedHashSet<>(candidates));
					if (uniq.size() > k) uniq = uniq.subList(0, k);
					map.put(original, uniq);
				}
			}
			// 2) キャッシュ保存
			cacheParaphraseMap(model, words, k, map);
			String m = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
			System.out.println("💾 パラフレーズをキャッシュに保存しました (model: " + m + ")。");
			return new ParaphraseResult(map, raw, true, null);
		} catch (Exception parseEx) {
			// JSONでなかった場合、rawをそのまま保持して失敗扱い
			return new ParaphraseResult(new HashMap<>(), raw, false, "LLM出力のJSON解析に失敗: " + parseEx.getMessage());
		}
	}

	// ===== Cache helpers =====
	private static ConcurrentMap<String, Map<String, List<String>>> getParaMemoryCache(String model) {
		return PARA_MEMORY_CACHE.computeIfAbsent(modelSafe(model), m -> new ConcurrentHashMap<>());
	}

	private static String modelSafe(String model) {
		return (model == null || model.isEmpty()) ? DEFAULT_MODEL : model.replace('/', '_');
	}

	private static Path resolveParaCacheFile(String model) {
		String safeModel = modelSafe(model);
		return Paths.get(CACHE_BASE_DIR, PARAPHRASE_DIR, safeModel, PARAPHRASE_CACHE_FILE);
	}

	private static void ensureParaCacheLoaded(String model) {
		String safeModel = modelSafe(model);
		if (Boolean.TRUE.equals(PARA_CACHE_LOADED.get(safeModel))) return;

		Object lock = PARA_CACHE_LOCKS.computeIfAbsent(safeModel, k -> new Object());
		synchronized (lock) {
			if (Boolean.TRUE.equals(PARA_CACHE_LOADED.get(safeModel))) return;

			Path file = resolveParaCacheFile(safeModel);
			if (!Files.exists(file)) {
				PARA_CACHE_LOADED.put(safeModel, true);
				return;
			}
			try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				// JSON: { "<key>": { "original": [candidates..], ... }, ... }
				@SuppressWarnings("unchecked")
				Map<String, Map<String, List<String>>> persisted = mapper.readValue(reader, Map.class);
				if (persisted != null) {
					ConcurrentMap<String, Map<String, List<String>>> mem = getParaMemoryCache(safeModel);
					mem.putAll(persisted);
				}
			} catch (Exception e) {
				System.err.println("パラフレーズキャッシュの読み込みに失敗: " + e.getMessage());
			}
			PARA_CACHE_LOADED.put(safeModel, true);
		}
	}

	private static void persistParaCache(String model) {
		String safeModel = modelSafe(model);
		Object lock = PARA_CACHE_LOCKS.computeIfAbsent(safeModel, k -> new Object());
		ConcurrentMap<String, Map<String, List<String>>> mem = getParaMemoryCache(safeModel);
		synchronized (lock) {
			Path file = resolveParaCacheFile(safeModel);
			try {
				Path parent = file.getParent();
				if (parent != null) Files.createDirectories(parent);
				Map<String, Map<String, List<String>>> snapshot = new HashMap<>(mem);
				try (BufferedWriter writer = Files.newBufferedWriter(
						file,
						StandardCharsets.UTF_8,
						StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING)) {
					mapper.writerWithDefaultPrettyPrinter().writeValue(writer, snapshot);
				}
			} catch (Exception e) {
				System.err.println("パラフレーズキャッシュの書き込みに失敗: " + e.getMessage());
			}
			PARA_CACHE_LOADED.put(safeModel, true);
		}
	}

	private static String buildParaKey(List<String> words, int k) {
		// 安定したキーのためミニJSONを利用（words, k に加えて n=対象語数 も保持）
		ObjectNode root = mapper.createObjectNode();
		ArrayNode arr = root.putArray("words");
		for (String w : words) arr.add(w);
		root.put("k", k);
		root.put("n", words != null ? words.size() : 0);
		return root.toString();
	}

	private static Map<String, List<String>> getCachedParaphraseMap(String model, List<String> words, int k) {
		ensureParaCacheLoaded(model);
		String key = buildParaKey(words, k);
		return getParaMemoryCache(model).get(key);
	}

	private static void cacheParaphraseMap(String model, List<String> words, int k, Map<String, List<String>> map) {
		ensureParaCacheLoaded(model);
		String key = buildParaKey(words, k);
		getParaMemoryCache(model).put(key, map);
		persistParaCache(model);
	}

	/**
	 * パラフレーズ結果のDTO。扱いやすさのためにいくつかユーティリティを同梱。
	 */
	public static class ParaphraseResult {
		public final Map<String, List<String>> map; // original -> candidates
		public final String raw;                    // LLMの生テキスト（トラブルシュート用）
		public final boolean success;              // 解析成功フラグ
		public final String error;                 // エラーメッセージ（あれば）
		public final boolean fromCache;            // キャッシュヒットかどうか

		public ParaphraseResult(Map<String, List<String>> map, String raw, boolean success, String error) {
			this(map, raw, success, error, false);
		}

		public ParaphraseResult(Map<String, List<String>> map, String raw, boolean success, String error, boolean fromCache) {
			this.map = map;
			this.raw = raw;
			this.success = success;
			this.error = error;
			this.fromCache = fromCache;
		}

		public static ParaphraseResult empty() {
			return new ParaphraseResult(new HashMap<>(), "", true, null, false);
		}

		/**
		 * original の順序を保ちつつ、候補語を順にフラット化して配列で返す（重複除去）。
		 */
		public String[] flattenCandidates(List<String> originalOrder) {
			Set<String> uniq = new LinkedHashSet<>();
			if (originalOrder != null) {
				for (String o : originalOrder) {
					List<String> cands = map.get(o);
					if (cands != null) uniq.addAll(cands);
				}
			} else {
				for (Map.Entry<String, List<String>> e : map.entrySet()) {
					if (e.getValue() != null) uniq.addAll(e.getValue());
				}
			}
			return uniq.toArray(new String[0]);
		}
	}

	/**
	 * 単語配列の先頭から paraphraseWordNumFromTop 件だけを言い換えた配列を返します。
	 * 候補は1件のみ使用（第1候補）。候補が得られない場合は元の単語を残します。
	 */
	public static String[] paraphraseTopN(String[] words, int paraphraseWordNumFromTop) throws Exception {
		return paraphraseTopN(words, paraphraseWordNumFromTop, 1);
	}

	/**
	 * 単語配列の先頭から paraphraseWordNumFromTop 件だけを言い換えた配列を返します。
	 * candidatesPerWord は各単語あたり取得する候補数ですが、置換には第1候補のみを使います。
	 */
	public static String[] paraphraseTopN(String[] words, int paraphraseWordNumFromTop, int candidatesPerWord) throws Exception {
		if (words == null || words.length == 0 || paraphraseWordNumFromTop <= 0) {
			return words == null ? new String[0] : words.clone();
		}
		int n = Math.min(paraphraseWordNumFromTop, words.length);

		// 対象語の収集（位置を保つため元配列はそのまま複製）
		String[] out = words.clone();
		List<String> target = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			String w = words[i];
			if (w != null && !w.trim().isEmpty()) {
				target.add(w);
			}
		}

		if (target.isEmpty()) {
			return out;
		}

		ParaphraseResult result = paraphraseWords(target, Math.max(1, candidatesPerWord), getApiKeyOrThrow(), DEFAULT_MODEL);
		// 置換: 先頭から順に、候補があれば第1候補、なければ元語
		for (int i = 0; i < n; i++) {
			String original = words[i];
			if (original == null || original.trim().isEmpty()) continue;
			List<String> cands = result.map.get(original);
			if (cands != null && !cands.isEmpty()) {
				out[i] = cands.get(0);
			}
		}
		return out;
	}
}