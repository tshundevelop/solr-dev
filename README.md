## 目次
- [セットアップ](#セットアップ)
- [動作テスト](#動作テスト)
- [検索評価実行](#検索評価実行)
- [プロジェクト構成](#プロジェクト構成)
- [Makefileコマンド一覧](#Makefileコマンド一覧)
- [パワーポイント資料](#パワーポイント資料)

## セットアップ
### OpenAI APIキーの設定

java/api_key.envを作成しOPENAI_API_KEYにAPIキーを設定する

## 動作テスト
### 自動テストで全機能を確認

```bash
# 包括的なテストを実行
./test_setup.sh

make test
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

### データダウンロード

Hugging Face上のデータセットをダウンロードする。
Hugging Face：https://huggingface.co

```bash
make dataset DATASET="<dataset_name>" NAME="<short_name>" \
  ID_FIELD="<id_field>" TITLE_FIELD="<title_field>" CONTEXT_FIELD="<context_field>"
```

dataset_nameにはHugging Faceで使用するデータセットの名前（パス）が入る
以下のデータセットなら、DATASET="range3/wikipedia-ja-20230101"
https://huggingface.co/datasets/range3/wikipedia-ja-20230101

JaQuAD、DATASET="SkelterLabsInc/JaQuAD"
https://huggingface.co/datasets/SkelterLabsInc/JaQuAD

#### パラメータ

| パラメータ | 説明 | デフォルト |
|-----------|------|----------|
| `DATASET` | Hugging Faceのデータセット名 | SkelterLabsInc/JaQuAD |
| `SPLIT` | データセットのsplit（train/validation等） | train |
| `NAME` | 短縮名（フォルダ名・ファイル名用） | squad |
| `ID_FIELD` | IDフィールド名（`_generated_`で自動生成） | id |
| `TITLE_FIELD` | タイトルフィールド名 | title |
| `CONTEXT_FIELD` | コンテキストフィールド名 | context |
| `MAX_RECORDS` | 取得する最大レコード数（省略時は全件） | - |

#### 出力

- **RAWデータ**: `data/<NAME>/<NAME>_<SPLIT>_raw.json`
- **処理済みデータ**: `data/<NAME>/processed/<NAME>_production_XXXX.json`

処理済みデータは`id`, `title`, `context`の3フィールドに統一される。

#### 例

```bash
# Wikipedia日本語データセット（1000件）
make dataset DATASET="range3/wikipedia-ja-20230101" NAME="wikipedia" \
  ID_FIELD="id" TITLE_FIELD="title" CONTEXT_FIELD="text" MAX_RECORDS=1000

# JaQuADデータセット（全件）
make dataset DATASET="SkelterLabsInc/JaQuAD" NAME="jaquad" \
  ID_FIELD="id" TITLE_FIELD="title" CONTEXT_FIELD="context"
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
├── test_setup.sh               # 包括的テストスクリプト
├── README.md                   # このファイル
├── .gitignore
├── data/
│   ├── sample_data.json      # サンプルデータ
│   ├── schema/
│   │   └── schema.json       # Solrスキーマ定義
├── scripts/
│   ├── create_solr_core.sh   # コア作成スクリプト
│   └── delete_solr_core.sh   # コア削除スクリプト
├── java/                     # Javaクライアント
│   ├── Dockerfile
│   ├── pom.xml               # 依存関係定義
│   ├── api_key.env           # OpenAI APIキー設定（自分で作成する）
│   └── src/main/java/        # Javaソースコード
│       ├── Config.java                      # 検索設定クラス
│       ├── Main.java                        # メイン
│       ├── DataInputSolr.java               # データ投入
│       ├── EmbedSearch.java                 # ベクトル検索
│       ├── KeywordSearch.java               # キーワード検索
│       ├── HybridSearch.java                # ハイブリッド検索
│       ├── DataInputSolr.java               # データ投入
│       ├── DotEnvLoader.java                # 環境変数ローダー
│       ├── EmbeddingClient.java             # pythonからベクトル埋め込み取得通信
│       ├── WordSplitter.java                # 分ち書き
│       ├── Evaluation.java                  # 評価実行クラス
│       ├── EvaluationResult.java            # 評価データ格納クラス
│       ├── OpenAIUseLLM.java                # クエリ書き換え
│       └── OpenAIEmbeddingClient.java       # OpenAI埋め込みクライアント
└── python/                   # Pythonクライアント
    ├── Dockerfile
    ├── app.py                # Flask API
    ├── embeddder.py          # ベクトル埋め込み作成
    └── requirements.txt
```

## Makefileコマンド一覧

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
| `make inputdata` | データ投入 |
| `make run` | 検索評価実行 |
| `make dataset` | Hugging Faceデータセット取得・処理 |

## パワーポイント資料
https://kensukedreamartsco.sharepoint.com/:p:/r/sites/DA114/Shared%20Documents/%E4%B8%80%E8%88%AC/%E8%B3%87%E6%96%99/%E3%83%86%E3%82%B9%E3%83%88.pptx?d=w1a9e71d067f04e7bbba6d7474f93772b&csf=1&web=1&e=22ZfVe
