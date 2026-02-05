import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import java.util.ArrayList;
import java.util.List;

public class Evaluation {
    private static List<Double> coverageList = new ArrayList<>();
    private static List<Double> mrrList = new ArrayList<>();
    private static List<Double> lrapList = new ArrayList<>();
    private static List<Double> averageMrrAndLrapList = new ArrayList<>();
    /**
     * 検索結果を評価し、評価結果を返す
     * @param searchResults 検索結果のSolrDocumentList
     * @param question 検索に使用したクエリ
     * @param correctId 正解ドキュメントのID
     * @return EvaluationResult 評価結果
     */
    public static EvaluationResult evaluate(SolrDocumentList searchResults, String question, String correctId) {
        double coverage = getCoverage(searchResults, question, correctId);
        double mrr = getMrr(searchResults, question, correctId);
        double lrap = getLrap(searchResults, question, correctId);
        double averageMrrAndLrap = (mrr + lrap) / 2;

        coverageList.add(coverage);
        mrrList.add(mrr);
        lrapList.add(lrap);
        averageMrrAndLrapList.add(averageMrrAndLrap);

        return new EvaluationResult(coverage, mrr, lrap, averageMrrAndLrap);
    }

    public static double getAverageCoverage() {
        if (coverageList.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double val : coverageList) {
            sum += val;
        }
        return sum / coverageList.size();
    }

    public static double getAverageMrr() {
        if (mrrList.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double val : mrrList) {
            sum += val;
        }
        return sum / mrrList.size();
    }

    public static double getAverageLrap() {
        if (lrapList.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double val : lrapList) {
            sum += val;
        }
        return sum / lrapList.size();
    }

    public static double getAverageMrrAndLrap() {
        if (averageMrrAndLrapList.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double val : averageMrrAndLrapList) {
            sum += val;
        }
        return sum / averageMrrAndLrapList.size();
    }

    private static double getCoverage(SolrDocumentList searchResults, String question, String correctId) {
        double score = 0.0;

        // 検索結果の中に正解IDが含まれているかチェック
        for (SolrDocument doc : searchResults) {
            String docId = getDocumentId(doc);
            if (docId != null && docId.equals(correctId)) {
                score = 1.0;
                break;
            }
        }
        return score;
    }

    private static double getMrr(SolrDocumentList searchResults, String question, String correctId) {
        double score = 0.0;

        for (int i = 0; i < searchResults.size(); i++) {
            SolrDocument doc = searchResults.get(i);
            String docId = getDocumentId(doc);
            if (docId != null && docId.equals(correctId)) {
                score = 1.0 / (i + 1);
                break;
            }
        }
        return score;
    }

    public static double getLrap(SolrDocumentList searchResults, String question, String correctId) {
        double totalScore = 0.0;
        int relevantCount = 0;

        for (int i = 0; i < searchResults.size(); i++) {
            SolrDocument doc = searchResults.get(i);
            String docId = getDocumentId(doc);
            if (docId != null && docId.equals(correctId)) {
                relevantCount++;
                totalScore += (double) relevantCount / (i + 1);
                return totalScore / relevantCount;
            } else if (docId != null && docId.split("-").length > 1 && correctId.split("-").length > 1 &&
                       docId.split("-")[1].equals(correctId.split("-")[1])) {
                relevantCount++;
                totalScore += (double) relevantCount / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * ドキュメントIDを取得するヘルパーメソッド
     * original_doc_idがあればそれを使用、なければidを使用
     */
    private static String getDocumentId(SolrDocument doc) {
        String docId = (String) doc.getFirstValue("original_doc_id");
        if (docId == null) {
            docId = (String) doc.getFirstValue("id");
        }
        return docId;
    }
}
