import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.QueryRequest; // QueryRequestをインポート
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrRequest; // SolrRequestをインポート
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams; // ModifiableSolrParamsをインポート
import org.eclipse.jetty.util.ssl.SslContextFactory.Client;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Array;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.solr.client.solrj.util.ClientUtils;

public class Keyword {
	public static void main(String[] args) {
		// 引数があればそれを使う
        String keyword;
        try {
            keyword = args[0];
        } catch (Exception e) {
            System.out.println("No argument found. Escape process.");
            return;
        }

        keyword = WordSplitter.getSplittedWords(keyword, new String[]{"名詞", "動詞", "形容詞"});
        System.out.println("Keyword after word split: " + keyword);

		SolrDocumentList results = getKeywordSearchResult("JaQuAD_dev_all", keyword, "id,title,context,score", "context", 10);

		for (SolrDocument result : results) {
            System.out.println("ID: " + result.getFieldValue("id") + ", Score: " + result.getFieldValue("score"));
            System.out.println("title: " + result.getFieldValue("title"));
        }
	}

	public static SolrDocumentList getKeywordSearchResult(
        String coreName,
        String keyword,
        String field,
        String targetField,
        Integer topk
    ) {
		String solrUrl = "http://solr:8983/solr/" + coreName; // docker-composeのsolrサービス名に合わせる
		try (SolrClient solr = new HttpSolrClient.Builder(solrUrl).build()) {

			// SolrQueryではなくModifiableSolrParamsを使用し、パラメータを設定
            ModifiableSolrParams params = new ModifiableSolrParams();

            // 'text_vec' はSolrスキーマで定義したベクトルフィールド名に合わせる
            System.out.println("keyword: " + keyword);
            keyword = ClientUtils.escapeQueryChars(keyword);
            System.out.println("Escaped keyword: " + keyword);
            List<String> escapeChars = Arrays.asList("『", "』", "\\\\", "?』", "?(", "-", "", "/", "~", "!", "@", "#", "$", "%", "^", "&", "*", "+", "=", "|", "\\", ":", ";", "\"", "'", "<", ">", ",", ".", "?", "`", "!』", "(", ")", "「", ")(\"", ")\")」", "「(", ")、", ")〜(", "』(");
            for (String escapeChar : escapeChars) {
                keyword = keyword.replace(escapeChar, "");
            }
            // keyword = keyword.replaceAll("\\\\", "");
            String[] keywordList = keyword.split(" ");
            System.out.println("Split keyword list: " + String.join(", ", keywordList));
            if (keywordList.length > 1) {
                // 各単語にフィールド名とコロンを前置する
                // 例: "keyword1" -> "context:keyword1"
                List<String> fieldQualifiedKeywords = new ArrayList<>();
                // List<String> escapeChars = Arrays.asList("?)』", "?(", "-", "", "/", "~", "!", "@", "#", "$", "%", "^", "&", "*", "+", "=", "|", "\\", ":", ";", "\"", "'", "<", ">", ",", ".", "?", "`", "!』", "(", ")", "「", "『", ")(\"", ")\")」", "「(", ")、", ")〜(", "』(");
                for (int i = 0; i < keywordList.length; i++) {
                    boolean flag = false;
                    String a = keywordList[i];
                    for (String escapeChar : escapeChars) {
                        if (a.equals(escapeChar)) {
                            flag = true;
                            break;
                        }
                    }
                    if (flag) {
                        continue;
                    }
                    fieldQualifiedKeywords.add(targetField + ":" + a);
                }

                // 3. フィールド指定された単語を ' AND ' で結合
                System.out.println("Field qualified keywords: " + String.join(", ", fieldQualifiedKeywords));
                keyword = String.join(" OR ", fieldQualifiedKeywords);
            }
            System.out.println("Final keyword for query: " + keyword);

            // params.set("q", "context:\"" + keyword + "\""); // フレーズ検索
            params.set("q.op", "AND");
            params.set("q", keyword);
            params.set("fl", field); // 必要なフィールドを指定
            params.set("rows", topk); // 取得件数を指定

            // QueryRequestオブジェクトを作成し、POSTメソッドを明示的に指定
            QueryRequest queryRequest = new QueryRequest(params);
            queryRequest.setMethod(SolrRequest.METHOD.POST);

            // クエリを実行
            QueryResponse response = queryRequest.process(solr);

			SolrDocumentList docs = response.getResults();

			return docs;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}
