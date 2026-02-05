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
		// サンプル1: 基本的な形態素解析
		System.out.println("=== サンプル1: 基本的な形態素解析 ===");
		kuromojiBasicSample();
		
		// サンプル2: 品詞フィルタリング
		System.out.println("\n=== サンプル2: 品詞フィルタリング ===");
		String text = "「奈良の大仏」の高さは何メートルなの?";
		String[] partOfSpeechs = {"名詞", "動詞", "形容詞"};
		int choiceWordNumFromTop = 1000;
		String[] words = getSplittedWords(text, partOfSpeechs, choiceWordNumFromTop);
		System.out.println(Arrays.toString(words));
		
		// サンプル3: 品詞付き分割
		System.out.println("\n=== サンプル3: 品詞付き分割 ===");
		kuromojiWithPosSample();
		
		// サンプル4: カスタム辞書のテスト
		System.out.println("\n=== サンプル4: カスタム辞書のテスト ===");
		kuromojiUserDictionarySample();
	}
	
	/**
	 * Kuromojiの基本的な使い方サンプル
	 */
	public static void kuromojiBasicSample() {
		String text = "東京スカイツリーの高さは634メートルです。";
		System.out.println("入力テキスト: " + text);
		System.out.println("\n形態素解析結果:");
		
		Tokenizer tokenizer = new Tokenizer();
		List<Token> tokens = tokenizer.tokenize(text);
		
		System.out.printf("%-15s %-10s %-15s %-10s %-10s%n", 
			"表層形", "品詞", "品詞細分類1", "基本形", "読み");
		printLine(70);
		
		for (Token token : tokens) {
			String surface = token.getSurface();
			String[] features = token.getAllFeatures().split(",");
			String pos = features[0]; // 品詞
			String posDetail1 = features.length > 1 ? features[1] : "-"; // 品詞細分類1
			String baseForm = features.length > 6 ? features[6] : surface; // 基本形
			String reading = features.length > 7 ? features[7] : "-"; // 読み
			
			System.out.printf("%-15s %-10s %-15s %-10s %-10s%n", 
				surface, pos, posDetail1, baseForm, reading);
		}
	}
	
	/**
	 * 品詞情報付きで抽出するサンプル
	 */
	public static void kuromojiWithPosSample() {
		String text = "芥川賞を受賞した人は誰ですか。";
		String[] targetPos = {"名詞", "動詞"};
		
		System.out.println("入力テキスト: " + text);
		System.out.println("抽出対象品詞: " + Arrays.toString(targetPos));
		System.out.println("\n抽出結果:");
		
		List<String[]> wordsWithPos = getWordsWithPos(text, targetPos, 1000);
		
		System.out.printf("%-15s %-10s%n", "単語", "品詞");
		printLine(30);
		for (String[] wordPos : wordsWithPos) {
			System.out.printf("%-15s %-10s%n", wordPos[0], wordPos[1]);
		}
	}
	
	/**
	 * ユーザー辞書を使用するサンプル
	 */
	public static void kuromojiUserDictionarySample() {
		String text = "奈良の大仏は東大寺にあります。";
		System.out.println("入力テキスト: " + text);
		System.out.println("\nユーザー辞書使用時の形態素解析:");
		
		Builder builder = new Tokenizer.Builder();
		Tokenizer tokenizer = null;
		try {
			tokenizer = builder.userDictionary("./userDic.csv").build();
		} catch (IOException e) {
			System.out.println("警告: ユーザー辞書が読み込めませんでした。デフォルト辞書を使用します。");
			tokenizer = new Tokenizer();
		}
		
		List<Token> tokens = tokenizer.tokenize(text);
		
		System.out.printf("%-15s %-10s %-15s%n", "表層形", "品詞", "品詞細分類1");
		printLine(45);
		
		for (Token token : tokens) {
			String surface = token.getSurface();
			String[] features = token.getAllFeatures().split(",");
			String pos = features[0];
			String posDetail1 = features.length > 1 ? features[1] : "-";
			
			System.out.printf("%-15s %-10s %-15s%n", surface, pos, posDetail1);
		}
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

	/**
	 * 品詞付きで返す版。返却要素は [cleanedWord, partOfSpeech]。
	 */
	public static List<String[]> getWordsWithPos(String text, String[] partOfSpeechs, int choiceWordNumFromTop) {
		List<String[]> result = new ArrayList<>();
		if (text == null || text.isEmpty() || choiceWordNumFromTop <= 0) return result;

		List<String> removeWords = new ArrayList<String>(Arrays.asList("?"));

		Builder builder = new Tokenizer.Builder();
		Tokenizer tokenizer = null;
		try {
			tokenizer = builder.userDictionary("./userDic.csv").build();
		} catch (IOException e) {
			e.printStackTrace();
			return result;
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
				result.add(new String[]{cleaned, targetPos});
			}
		}

		return result;
	}

	// 既存の呼び出し（文字列を期待）と互換性を保つヘルパー
	public static String getSplittedWords(String text, String[] partOfSpeechs) {
		// Configの既定数がある場合はそれを使う設計に将来拡張可
		int limit = 1000; // デフォルトで十分大きな上限
		String[] arr = getSplittedWords(text, partOfSpeechs, limit);
		return String.join(" ", arr);
	}

	private static void printLine(int length) {
		for (int i = 0; i < length; i++) {
			System.out.print("-");
		}
		System.out.println();
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
