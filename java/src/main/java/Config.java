public class Config {
    private String coreName;
    private String type;
    private int numRows;
    private int topk;
    private String keywordTargetField;
    private String embeddingTargetField;
    private String modelName;
    private String[] targetFields;
    private String[] partOfSpeech;
    private int choiceWordNumFromTop;
    // TF-IDFランキングから上位N個の単語を取得する設定（>0でTF-IDF使用、0で従来の分かち書き使用）
    private int rankChoiceWordNumFromTop;
    private int paraphraseWordNumFromTop;
    private String fieldSearchMethodType;
    // チャンクドキュメントを検索対象にするかどうか
    private boolean isChunk;
    // 結果保存時に searchType/結果フォルダ名/日時 で使う中間フォルダ名
    private String resultFolderName;
    // 正解データJSONファイルのパス
    private String groundTruthJsonPath;
    // クエリ生成方法: true=元の文章を使用, false=分かち書き+品詞フィルタ
    private boolean useOriginalQuery;
    // 分かち書き時の品詞フィルタ（useOriginalQuery=falseの時に使用）
    private String[] queryPartOfSpeech;
    // 実行する検索タイプのリスト
    private String[] searchTypes;

    public Config() {
        // デフォルト値の設定
        this.coreName = "production_split-1000";
        // this.type は Main.java のループ内で searchTypes から設定される
        this.numRows = 10000;
        this.topk = 10;
        this.keywordTargetField = "context";
        this.isChunk = true;
        this.embeddingTargetField = isChunk ? "chunk_vector" : "context_vector";
        this.modelName = "text-embedding-3-large";
        this.targetFields = new String[]{"id", "original_doc_id", "title", "context", "score"};
        this.partOfSpeech = new String[]{"名詞", "動詞", "形容詞"};
        this.choiceWordNumFromTop = 1000;
        this.rankChoiceWordNumFromTop = 3; // 0以下なら無効（従来の分かち書き使用）
        this.paraphraseWordNumFromTop = 3; // パラフレーズ無効（TF-IDF単語のみ使用）
        this.fieldSearchMethodType = "OR";
        this.resultFolderName = "word_chunk1000_rephrase"; // デフォルトの結果フォルダ名
        this.groundTruthJsonPath = "data/jaquad_validation_3939.json";
        this.useOriginalQuery = false; // デフォルトは分かち書き
        this.queryPartOfSpeech = new String[]{"名詞", "動詞", "形容詞"};
        this.searchTypes = new String[]{"embedding"};
    }

    // getter/setterメソッド    
    public String getCoreName() { return coreName; }
    public void setCoreName(String coreName) { this.coreName = coreName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public int getNumRows() { return numRows; }
    public void setNumRows(int numRows) { this.numRows = numRows; }

    public int getTopk() { return topk; }
    public void setTopk(int topk) { this.topk = topk; }

    public String getKeywordTargetField() { return keywordTargetField; }
    public void setKeywordTargetField(String keywordTargetField) { this.keywordTargetField = keywordTargetField; }

    public String getEmbeddingTargetField() { return embeddingTargetField; }
    public void setEmbeddingTargetField(String embeddingTargetField) { this.embeddingTargetField = embeddingTargetField; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    
    public String[] getTargetFields() { return targetFields; }
    public void setTargetFields(String[] targetFields) { this.targetFields = targetFields; }
    
    public String[] getPartOfSpeech() { return partOfSpeech; }
    public void setPartOfSpeech(String[] partOfSpeech) { this.partOfSpeech = partOfSpeech; }

    public int getChoiceWordNumFromTop() { return choiceWordNumFromTop; }
    public void setChoiceWordNumFromTop(int choiceWordNumFromTop) { this.choiceWordNumFromTop = choiceWordNumFromTop; }

    public int getRankChoiceWordNumFromTop() { return rankChoiceWordNumFromTop; }
    public void setRankChoiceWordNumFromTop(int rankChoiceWordNumFromTop) { this.rankChoiceWordNumFromTop = rankChoiceWordNumFromTop; }

    public int getParaphraseWordNumFromTop() { return paraphraseWordNumFromTop; }
    public void setParaphraseWordNumFromTop(int paraphraseWordNumFromTop) { this.paraphraseWordNumFromTop = paraphraseWordNumFromTop; }

    public String getFieldSearchMethodType() { return fieldSearchMethodType; }
    public void setFieldSearchMethodType(String fieldSearchMethodType) { this.fieldSearchMethodType = fieldSearchMethodType; }

    public boolean isChunk() { return isChunk; }
    public void setChunk(boolean isChunk) { this.isChunk = isChunk; }

    public String getResultFolderName() { return resultFolderName; }
    public void setResultFolderName(String resultFolderName) { this.resultFolderName = resultFolderName; }

    public String getGroundTruthJsonPath() { return groundTruthJsonPath; }
    public void setGroundTruthJsonPath(String groundTruthJsonPath) { this.groundTruthJsonPath = groundTruthJsonPath; }

    public boolean isUseOriginalQuery() { return useOriginalQuery; }
    public void setUseOriginalQuery(boolean useOriginalQuery) { this.useOriginalQuery = useOriginalQuery; }

    public String[] getQueryPartOfSpeech() { return queryPartOfSpeech; }
    public void setQueryPartOfSpeech(String[] queryPartOfSpeech) { this.queryPartOfSpeech = queryPartOfSpeech; }

    public String[] getSearchTypes() { return searchTypes; }
    public void setSearchTypes(String[] searchTypes) { this.searchTypes = searchTypes; }
}