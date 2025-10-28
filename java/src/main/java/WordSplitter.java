import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import com.atilika.kuromoji.ipadic.Tokenizer.Builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.solr.client.solrj.util.ClientUtils;

public class WordSplitter {
	public static void main(String[] args) {
		String text = "「奈良の大仏」の高さは何メートルなの?";
		String[] partOfSpeechs = {"名詞", "動詞", "形容詞"};
		int choiceWordNumFromTop = 2;
		String[] words = getSplittedWords(text, partOfSpeechs, choiceWordNumFromTop);
		System.out.println(Arrays.toString(words));
	}

	/**
	 * 指定した品詞に一致する語のみを、入力文の登場順のまま先頭から choiceWordNumFromTop 件抽出して返す。
	 * 返り値は重複をそのまま許容する（必要に応じて変更可）。
	 */
	public static String[] getSplittedWords(String text, String[] partOfSpeechs, int choiceWordNumFromTop) {
		if (text == null || text.isEmpty() || choiceWordNumFromTop <= 0) {
			return new String[0];
		}

		List<String> removeWords = new ArrayList<String>(Arrays.asList("?"));
		List<String> result = new ArrayList<>();

		Builder builder = new Tokenizer.Builder();
		Tokenizer tokenizer = null;
		try {
			tokenizer = builder.userDictionary("./userDic.csv").build();
		} catch (IOException e) {
			e.printStackTrace();
			return new String[0];
		}

		List<Token> tokens = tokenizer.tokenize(text);
		List<String> posList = Arrays.asList(partOfSpeechs);

		for (Token token : tokens) {
			if (result.size() >= choiceWordNumFromTop) break;
			String targetPos = token.getAllFeatures().split(",")[0];
			String surface = token.getSurface();
			if (!posList.contains(targetPos)) continue;
			if (removeWords.contains(surface)) continue;

			String cleaned = clean(surface);
			if (!cleaned.isEmpty()) {
				result.add(cleaned);
			}
		}

		System.out.println("Split keyword list: " + String.join(", ", result));

		return result.toArray(new String[0]);
	}

	// 既存の呼び出し（文字列を期待）と互換性を保つヘルパー
	public static String getSplittedWords(String text, String[] partOfSpeechs) {
		// Configの既定数がある場合はそれを使う設計に将来拡張可
		int limit = 1000; // デフォルトで十分大きな上限
		String[] arr = getSplittedWords(text, partOfSpeechs, limit);
		return String.join(" ", arr);
	}

	private static String clean(String s) {
		String w = ClientUtils.escapeQueryChars(s);
		List<String> escapeChars = Arrays.asList(
			"『", "』", "\\\\", "?』", "?(", "-", "", "/", "~", "!", "@", "#", "$", "%", "^", "&", "*", "+", "=", "|", "\\", ":", ";", "\"", "'", "<", ">", ",", ".", "?", "`", "!』", "(", ")", "「", ")(\"", ")\")」", "「(", ")、", ")〜(", "』("
		);
		for (String c : escapeChars) {
			w = w.replace(c, "");
		}
		return w.trim();
	}
}
