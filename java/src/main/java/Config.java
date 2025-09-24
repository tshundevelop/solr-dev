public class Config {
    private String coreName;
    private String type;
    private int numRows;
    private String[] targetFields;
    private String[] partOfSpeech;

    public Config() {
        // デフォルト値の設定
        this.coreName = "JaQuAD_dev_all";
        this.type = "embedding";
        this.numRows = 10;
        this.targetFields = new String[]{"id", "title", "context"};
        this.partOfSpeech = new String[]{"名詞", "動詞", "形容詞"};
    }

    // getter/setterメソッド    
    public String getCoreName() { return coreName; }
    public void setCoreName(String coreName) { this.coreName = coreName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public int getNumRows() { return numRows; }
    public void setNumRows(int numRows) { this.numRows = numRows; }
    
    public String[] getTargetFields() { return targetFields; }
    public void setTargetFields(String[] targetFields) { this.targetFields = targetFields; }
    
    public String[] getPartOfSpeech() { return partOfSpeech; }
    public void setPartOfSpeech(String[] partOfSpeech) { this.partOfSpeech = partOfSpeech; }
}