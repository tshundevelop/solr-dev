## 目次
- [必要要件](#必要要件)
- [クイックスタート](#クイックスタート)
- [詳細な使い方](#詳細な使い方)
- [検索方法](#検索方法)

## セットアップ
### OpenAI APIキーの設定

java/api_key.envを作成しOPENAI_API_KEYにAPIキーを設定する

## 動作テスト
### 自動テストで全機能を確認

```bash
# 包括的なテストを実行
./test_setup.sh

make test
make help
```

このスクリプトは以下を自動実行：
1. ✅ Dockerのビルドと起動
2. ✅ Solrの起動確認
3. ✅ サンプルコアの作成
4. ✅ サンプルデータの投入
5. ✅ OpenAI API接続確認
6. ✅ キーワード検索テスト
7. ✅ ベクトル検索テスト（APIキーがある場合）
8. ✅ ハイブリッド検索テスト（APIキーがある場合）
9. ✅ コアの削除
10. ✅ Dockerの停止

### Web UI

ブラウザで Solr Admin UI にアクセス：
```
http://localhost:8983/solr/
```

## 検索評価実行
### コア作成・削除

```bash
scripts/create_solr_core.sh ${CORE_NAME} ${SCHEMA_FILE}
```

```bash
scripts/delete_solr_core.sh ${CORE_NAME}
```


### データ投入

java/src/main/java/DataInputSolr.javaに以下のパラメータを設定する。

- DEFAULT_INPUT_FILES：投入データファイルパス（jsonのみ、スキーマに準じ「id、title、context、question」フィールド必須）
- OUTPUT_DIR：作成ベクトル保存フォルダ
- PARENT_DOCS_DIR：親ドキュメントベクトル保存フォルダ
- CORE_NAME：対象コアの名前
- CHUNK_SIZE：チャンクサイズ
- BATCH_SIZE：データ投入バッチサイズ
- CHUNK_MODE：テキスト分割選択（fixed=固定文字数、section=セクション）
- MODE：データ作成選択（batch=前データ投入、parent=チャンキングなしの親ドキュメントのみ投入）
- OVERLAP_SECTIONS：前後に結合するセクション数（0=前後結合なし、n=前後nセクションずつ結合）
- MIN_CHUNK_SIZE：最小チャンクサイズ
- SECTION_SEPARATOR：チャンキングのセパレーター

以下のコマンドを実行

```bash
make inputdata
```

### 検索実行

java/src/main/java/Config.javaに以下のパラメータを設定する。

- coreName：対象コアの名前
- numRows：検索対象データ数制限
- topk：検索結果の上位k件を保持する
- keywordTargetField：キーワード検索の対象フィールド
- isChunk：検索対象をチャンキングデータとするか
- embeddingTargetField：ベクトル検索対象フィールド
- modelName：埋め込み生成モデルの名前
- targetFields：検索結果に含めるフィールド群
- partOfSpeech：キーワードクエリに使用する品詞群
- choiceWordNumFromTop：分ち書きされた単語群からキーワード検索に使用する単語数
- paraphraseWordNumFromTop：単語言い換え単語数
- fieldSearchMethodType：フィールド検索タイプ指定（OR、AND）
- resultsFolderName：検索結果保存フォルダ名
- groudTruthJsonPath：検索クエリが含まれるjsonファイルパス（questionフィールドにクエリを配置）
- useOriginalQuery：クエリを分ち書きするか
- queryPartOfSpeech：分ち書きする時に使用する品詞
- seasrchTypes：検索タイプ指定（keyword、embedding、hybrid複数指定可）

以下のコマンドを実行

```bash
make run
```

### 検索結果確認

検索結果はResult/searchType/resultsFolderNameに保存され以下の３つのjsonファイルが作成される。

results.json：各クエリに対する検索結果が全て含まれる

```json
[
    {
        "id": "1",
        // :
        // :
    },
    {
        "id": "2",
        // :
        // :
    }
]
```

status.json：正解・不正解したデータのIDが含まれる

```json
{
    "correct": ["id1", "id2"], // 正解ID群
    "incorrect": ["id3", "id4"] //不正解ID群
}
```

summary.json：configのパラメータと検索評価精度が含まれる

```json
{
  "data" : "20260122_142102", // 日時
  "durationMs" : 5941099, // 実行時間
  "configuration" : {
    // :
  },
  "results": {
    "totalDocumentsProcessed" : 3939, // 検索クエリ数
    "averageCoverage" : "0.5", // 網羅率
    "averageMrr" : "0.2" // 平均順位の逆数
  }
}
```

## プロジェクト構成

```
solr-dev/
├── docker-compose.yml          # Docker構成
├── Makefile                    # 便利なコマンド集
├── test_setup.sh              # 包括的テストスクリプト
├── README.md                   # このファイル
├── data/
│   ├── sample_data.json       # サンプルデータ
│   ├── schema/
│   │   └── schema.json       # Solrスキーマ定義
│   ├── jaquad/               # JaQuADデータセット
│   └── wikipedia_ja/         # Wikipediaデータ
├── scripts/
│   ├── create_solr_core.sh   # コア作成スクリプト
│   └── delete_solr_core.sh   # コア削除スクリプト
├── java/                      # Javaクライアント
│   ├── pom.xml
│   ├── api_key.env           # OpenAI APIキー設定
│   └── src/main/java/        # Javaソースコード
└── python/                    # Pythonクライアント
    ├── app.py                # Flask API
    └── requirements.txt
```

## Makefile コマンド一覧

| コマンド | 説明 |
|---------|------|
| `make help` | 利用可能なコマンドを表示 |
| `make up` | コンテナを起動 |
| `make down` | コンテナを停止 |
| `make build` | コンテナをビルドして起動 |
| `make test` | 包括的テストを実行 |
| `make status` | コンテナの状態を表示 |
| `make logs` | ログを表示 |
| `make cores` | Solrコアの一覧を表示 |
| `make search Q=<query>` | 検索を実行 |
| `make java-shell` | Javaコンテナに入る |
| `make python-shell` | Pythonコンテナに入る |
| `make clean` | すべて削除 |
| `make inputdasta` | データ投入 |
| `make run` | 検索評価実行 |
