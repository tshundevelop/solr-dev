# Solr 検索システム

Apache Solr を使用した日本語文書の検索システムです。キーワード検索、ベクトル検索、ハイブリッド検索に対応しています。

## 目次
- [必要要件](#必要要件)
- [クイックスタート](#クイックスタート)
- [詳細な使い方](#詳細な使い方)
- [検索方法](#検索方法)
- [トラブルシューティング](#トラブルシューティング)

## 必要要件

- Docker & Docker Compose
- 8GB以上のメモリ推奨
- OpenAI APIキー（ベクトル検索を使用する場合）

## クイックスタート

### 自動テストで全機能を確認

```bash
# 包括的なテストを実行（推奨）
./test_setup.sh

# 完全にクリーンアップしてテストする場合
docker compose down -v  # data volumeも削除
./test_setup.sh
```

**注意**: `docker compose down -v` を使うと、Solrのdata volumeが削除され、既存のコアとデータもすべて削除されます。本番データがある場合は注意してください。

このスクリプトは以下を自動実行します：
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

### Makefileで簡単操作

```bash
# 利用可能なコマンドを表示
make help

# テスト実行
make test

# コンテナ起動
make up

# 検索実行
make search Q=東京

# クリーンアップ
make clean
```

## 詳細な使い方

### 1. 環境のセットアップ

#### OpenAI APIキーの設定（ベクトル検索に必要）

```bash
# APIキーを設定
echo "OPENAI_API_KEY=sk-your-api-key-here" > java/api_key.env
```

#### コンテナの起動

```bash
# コンテナをビルドして起動
docker compose up -d --build

# 起動確認
docker compose ps
```

### 2. Solrコアの作成

```bash
# コアを作成（スキーマファイルを使用）
./scripts/create_solr_core.sh my_core data/schema/schema.json
```

### 3. データの投入

#### JSONファイルから投入

```bash
# サンプルデータを投入
curl -X POST -H "Content-Type: application/json" \
  "http://localhost:8983/solr/my_core/update?commit=true" \
  --data-binary "@data/sample_data.json"

# JaQuADデータを投入
curl -X POST -H "Content-Type: application/json" \
  "http://localhost:8983/solr/my_core/update?commit=true" \
  --data-binary "@data/JaQuAD_dev_all.json"
```

#### Javaクライアントから投入

```bash
docker exec -it java bash
cd /app/java
mvn clean compile
mvn exec:java -Dexec.mainClass="JaQuADToSolr" \
  -Dexec.args="my_core /app/data/jaquad/processed/jaquad_production_792.json"
exit
```

## 検索方法

### Web UIでの検索

ブラウザで Solr Admin UI にアクセス：
```
http://localhost:8983/solr/
```

### curlでの検索

#### キーワード検索

```bash
# 基本的な検索
curl "http://localhost:8983/solr/my_core/select?q=東京&wt=json&indent=true"

# フィールド指定検索
curl "http://localhost:8983/solr/my_core/select?q=title:富士山&wt=json&indent=true"

# AND検索
curl "http://localhost:8983/solr/my_core/select?q=東京%20AND%20人口&wt=json&indent=true"
```

#### 結果の絞り込み

```bash
# 件数指定
curl "http://localhost:8983/solr/my_core/select?q=日本&rows=5"

# フィールド指定
curl "http://localhost:8983/solr/my_core/select?q=日本&fl=id,title"

# ページネーション
curl "http://localhost:8983/solr/my_core/select?q=日本&rows=10&start=20"
```

### Javaクライアントでの検索

```bash
# Javaコンテナに入る
docker exec -it java bash
cd /app/java

# コンパイル（初回のみ）
mvn clean compile

# キーワード検索
mvn exec:java -Dexec.mainClass="KeywordSearch" \
  -Dexec.args="my_core '東京の人口'"

# ベクトル検索（埋め込みベクトルが必要）
mvn exec:java -Dexec.mainClass="EmbedSearch" \
  -Dexec.args="my_core '日本の首都はどこですか'"

# ハイブリッド検索
mvn exec:java -Dexec.mainClass="HybridSearch" \
  -Dexec.args="my_core '東京オリンピック'"

exit
```

### 検索の高度なオプション

#### ハイライト

```bash
curl "http://localhost:8983/solr/my_core/select?q=日本&hl=true&hl.fl=text"
```

#### ファセット検索

```bash
curl "http://localhost:8983/solr/my_core/select?q=*:*&facet=true&facet.field=title"
```

#### スコアのデバッグ

```bash
curl "http://localhost:8983/solr/my_core/select?q=日本&debug=true"
```

## データ管理

### データの更新

```bash
# 部分更新
curl -X POST -H "Content-Type: application/json" \
  "http://localhost:8983/solr/my_core/update?commit=true" \
  -d '[{"id":"doc001","title":{"set":"新しいタイトル"}}]'
```

### データの削除

```bash
# IDで削除
curl -X POST -H "Content-Type: application/json" \
  "http://localhost:8983/solr/my_core/update?commit=true" \
  -d '{"delete":{"id":"doc001"}}'

# クエリで一括削除
curl -X POST -H "Content-Type: application/json" \
  "http://localhost:8983/solr/my_core/update?commit=true" \
  -d '{"delete":{"query":"title:テスト"}}'
```

### コアの削除

```bash
./scripts/delete_solr_core.sh my_core
```

## スキーマ管理

### スキーマの確認

```bash
# スキーマ全体
curl "http://localhost:8983/solr/my_core/schema"

# フィールド一覧
curl "http://localhost:8983/solr/my_core/schema/fields"
```

### フィールドの追加

```bash
# テキストフィールド
curl -X POST -H 'Content-Type: application/json' \
  "http://localhost:8983/solr/my_core/schema" \
  -d '{
    "add-field": {
      "name": "author",
      "type": "text_ja",
      "stored": true,
      "indexed": true
    }
  }'

# ベクトルフィールド
curl -X POST -H 'Content-Type: application/json' \
  "http://localhost:8983/solr/my_core/schema" \
  -d '{
    "add-field": {
      "name": "text_vector",
      "type": "knn_vector",
      "stored": true,
      "indexed": true
    }
  }'
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
| `make core-create CORE_NAME=<name>` | コアを作成 |
| `make core-delete CORE_NAME=<name>` | コアを削除 |
| `make java-shell` | Javaコンテナに入る |
| `make python-shell` | Pythonコンテナに入る |
| `make clean` | すべて削除 |
