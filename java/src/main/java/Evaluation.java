import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import java.util.Map;
import java.util.HashMap;

public class Evaluation {
    
    /**
     * 検索結果を評価し、評価結果を返す
     * @param searchResults 検索結果のSolrDocumentList
     * @param question 検索に使用したクエリ
     * @param correctId 正解ドキュメントのID
     * @return EvaluationResult 評価結果
     */
    public static EvaluationResult evaluate(SolrDocumentList searchResults, String question, String correctId) {
        double score = 0.0;

        // 検索結果の中に正解IDが含まれているかチェック
        for (SolrDocument doc : searchResults) {
            String docId = (String) doc.getFirstValue("id");
            if (docId.equals(correctId)) {
                score = 1.0;
                break;
            }
        }
        
        return new EvaluationResult(score);
    }
}
