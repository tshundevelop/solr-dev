# Solr 検索システム Makefile

.PHONY: help up down build test clean logs cores search status java-shell python-shell solr-shell core-create core-delete

CORE_NAME ?= jaquad_core
SOLR_URL ?= http://localhost:8983

help:
	@echo "====================================="
	@echo "  Solr 検索システム - コマンド一覧"
	@echo "====================================="
	@echo ""
	@echo "セットアップ:"
	@echo "  make up          - コンテナを起動"
	@echo "  make build       - コンテナをビルドして起動"
	@echo "  make test        - 包括的テストを実行"
	@echo ""
	@echo "操作:"
	@echo "  make down        - コンテナを停止"
	@echo "  make logs        - ログを表示"
	@echo "  make status      - コンテナの状態を表示"
	@echo ""
	@echo "Solr操作:"
	@echo "  make cores       - Solrコアの一覧を表示"
	@echo "  make search      - 検索を実行 (Q=<query> CORE_NAME=<name>)"
	@echo ""
	@echo "開発:"
	@echo "  make java-shell  - Javaコンテナに入る"
	@echo "  make python-shell - Pythonコンテナに入る"
	@echo "  make solr-shell  - Solrコンテナに入る"
	@echo ""
	@echo "クリーンアップ:"
	@echo "  make clean       - すべて削除"
	@echo "  make inputdata   - データをSolrに投入"
	@echo "  make run         - メイン処理を実行"
	@echo ""
	@echo "データセット取得:"
	@echo "  make dataset     - Hugging Faceデータセットを取得・処理"
	@echo "                     DATASET=<name> NAME=<short> ID=<field> TITLE=<field> CONTEXT=<field>"
	@echo ""

up:
	@echo "コンテナを起動しています..."
	@docker compose up -d
	@echo "Solrの起動を待っています..."
	@sleep 10
	@make status

build:
	@echo "コンテナをビルドして起動しています..."
	@docker compose up -d --build
	@echo "Solrの起動を待っています..."
	@sleep 10
	@make status

down:
	@echo "コンテナを停止しています..."
	@docker compose down

test:
	@./test_setup.sh

logs:
	@docker compose logs -f

status:
	@echo "====================================="
	@echo "  コンテナの状態"
	@echo "====================================="
	@docker compose ps
	@echo ""
	@echo "Solr接続確認:"
	@curl -sf $(SOLR_URL)/solr/ > /dev/null 2>&1 && echo "  ✓ Solr は起動しています ($(SOLR_URL))" || echo "  ✗ Solr に接続できません"

cores:
	@echo "Solrコアの一覧:"
	@curl -s "$(SOLR_URL)/solr/admin/cores?action=STATUS" | \
		grep -o '"name":"[^"]*"' | cut -d'"' -f4 | sed 's/^/  - /' || echo "  (コアが見つかりません)"

Q ?= 日本
search:
	@echo "検索: $(Q) (コア: $(CORE_NAME))"
	@curl -s "$(SOLR_URL)/solr/$(CORE_NAME)/select?q=$(Q)&rows=3&fl=id,title&wt=json&indent=true"

java-shell:
	@docker exec -it java bash

python-shell:
	@docker exec -it python bash

solr-shell:
	@docker exec -it solr bash

clean:
	@echo "すべてのコンテナとボリュームを削除しています..."
	@docker compose down -v
	@echo "クリーンアップが完了しました"

inputdata:
	@docker exec java bash -c "mvn exec:java -Dexec.mainClass=\"DataInputSolr\""

run:
	@docker exec java bash -c "mvn exec:java -Dexec.mainClass=\"Main\""

# データセット取得と処理
DATASET ?= rajpurkar/squad
SPLIT ?= train
NAME ?= squad
ID_FIELD ?= id
TITLE_FIELD ?= title
CONTEXT_FIELD ?= context
ID_PREFIX ?= 
SUBSET ?= 
MAX_RECORDS ?= 

dataset:
	@echo "====================================="
	@echo "  データセット取得・処理"
	@echo "====================================="
	@echo "Dataset: $(DATASET)"
	@echo "Split: $(SPLIT)"
	@echo "Name: $(NAME)"
	@echo "ID field: $(ID_FIELD)"
	@echo "Title field: $(TITLE_FIELD)"
	@echo "Context field: $(CONTEXT_FIELD)"
	@echo "====================================="
	@docker exec python python /app/python/download_and_process.py \
		--dataset "$(DATASET)" \
		--split "$(SPLIT)" \
		--name "$(NAME)" \
		--id-field "$(ID_FIELD)" \
		--title-field "$(TITLE_FIELD)" \
		--context-field "$(CONTEXT_FIELD)" \
		$(if $(ID_PREFIX),--id-prefix "$(ID_PREFIX)",) \
		$(if $(SUBSET),--subset "$(SUBSET)",) \
		$(if $(MAX_RECORDS),--max-records $(MAX_RECORDS),)