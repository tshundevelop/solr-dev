import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import com.atilika.kuromoji.ipadic.Tokenizer.Builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WordSplitter {
	public static void main(String[] args) {
		String text = "「奈良の大仏」の高さは何メートルなの?";
		String[] partOfSpeechs = {"名詞", "動詞", "形容詞"};
		String words = getSplittedWords(text, partOfSpeechs);
		System.out.println(words);
	}

	public static String getSplittedWords(String text, String[] partOfSpeechs) {
		List<String> removeWords = new ArrayList<String>(Arrays.asList("?"));

		Builder builder = new Tokenizer.Builder();
		Tokenizer tokenizer = null;
		try {
			tokenizer = builder.userDictionary("./userDic.csv").build();
		} catch (IOException e) {
			e.printStackTrace();
			return "";
		}
		// Tokenizer tokenizer = new Tokenizer();
		List<Token> tokens = tokenizer.tokenize(text);
		String words = "";
		for (Token token : tokens) {
			String target = token.getAllFeatures().split(",")[0];
			if (Arrays.asList(partOfSpeechs).contains(target) && !removeWords.contains(token.getSurface())) {
				words += token.getSurface() + " ";
			}
		}

		return words;
	}
}
