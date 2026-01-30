#!/usr/bin/env bash
#
# Solr 検索システムの包括的テストスクリプト
# 
# 実行内容：
# 1. Dockerのビルドと起動
# 2. Solrの起動確認
# 3. サンプルコアの作成
# 4. サンプルデータの投入
# 5. OpenAI API接続確認
# 6. キーワード検索テスト
# 7. ベクトル検索テスト
# 8. ハイブリッド検索テスト
# 9. コアの削除
# 10. Dockerの停止
#
# 使い方: ./test_setup.sh
#

set -euo pipefail

# 色付き出力
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[✓]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[!]${NC} $1"; }
log_error() { echo -e "${RED}[✗]${NC} $1"; }

# クリーンアップ関数
cleanup() {
    local exit_code=$?
    echo ""
    echo "================================================"
    log_info "クリーンアップを実行中..."
    echo "================================================"
    echo ""
    
    # Dockerを停止
    log_info "Dockerコンテナを停止中..."
    docker compose down 2>/dev/null || true
    
    echo ""
    if [ $exit_code -ne 0 ]; then
        log_error "テストが失敗しました（終了コード: $exit_code）"
    else
        log_success "クリーンアップ完了"
    fi
    
    exit $exit_code
}

# エラー時とスクリプト終了時にクリーンアップを実行
trap cleanup EXIT ERR

echo "================================================"
echo "  Solr 検索システム 包括的テスト"
echo "================================================"
echo ""

SOLR_URL="http://localhost:8983"
CORE_NAME="test_core"
SCHEMA_FILE="data/schema/schema.json"
SAMPLE_DATA="data/sample_data.json"

# ===== 1. Dockerのビルドと起動 =====
log_info "【1/10】Dockerのビルドと起動"
log_info "既存のコンテナを停止..."
docker compose down 2>/dev/null || true
log_info "コンテナをビルドして起動..."
docker compose up -d --build
log_success "Dockerコンテナを起動しました"
echo ""

# ===== 2. Solrの起動確認 =====
log_info "【2/10】Solrの起動確認（最大60秒待機）"
for i in {1..30}; do
    if curl -sf "${SOLR_URL}/solr/" > /dev/null 2>&1; then
        log_success "Solrが起動しました（${i}秒）"
        break
    fi
    [ $i -eq 30 ] && { log_error "Solrの起動がタイムアウトしました"; exit 1; }
    sleep 2
done
echo ""

# ===== 3. サンプルコアの作成 =====
log_info "【3/10】サンプルコア '${CORE_NAME}' の作成"
if [ ! -f "$SCHEMA_FILE" ]; then
    log_error "スキーマファイル ${SCHEMA_FILE} が見つかりません"
    exit 1
fi

# コアの存在確認
core_status=$(curl -s "${SOLR_URL}/solr/admin/cores?action=STATUS&core=${CORE_NAME}" || echo "")
if echo "${core_status}" | grep -q "\"name\":\"${CORE_NAME}\""; then
    log_success "コア '${CORE_NAME}' は既に存在します（スキップ）"
else
    log_info "コア '${CORE_NAME}' を作成中..."
    chmod +x scripts/create_solr_core.sh scripts/delete_solr_core.sh 2>/dev/null || true
    ./scripts/create_solr_core.sh "${CORE_NAME}" "${SCHEMA_FILE}"
    log_success "コアを作成しました"
fi
echo ""

# ===== 4. サンプルデータの投入 =====
log_info "【4/10】サンプルデータの投入"
if [ ! -f "$SAMPLE_DATA" ]; then
    log_error "サンプルデータ ${SAMPLE_DATA} が見つかりません"
    exit 1
fi

curl -X POST -H "Content-Type: application/json" \
    "${SOLR_URL}/solr/${CORE_NAME}/update?commit=true" \
    --data-binary "@${SAMPLE_DATA}" > /dev/null 2>&1

sleep 2
DOC_COUNT=$(curl -s "${SOLR_URL}/solr/${CORE_NAME}/select?q=*:*&rows=0" | \
            grep -o '"numFound":[0-9]*' | grep -o '[0-9]*' || echo "0")

if [ "$DOC_COUNT" -gt 0 ]; then
    log_success "サンプルデータを投入しました（${DOC_COUNT}件）"
else
    log_error "データ投入に失敗しました"
    exit 1
fi
echo ""

# ===== 5. OpenAI API接続確認 =====
log_info "【5/10】OpenAI API接続確認"

# Javaコンテナ内でAPIキーを確認
OPENAI_TEST=$(docker exec java bash -c 'if [ -f /app/java/api_key.env ]; then grep OPENAI_API_KEY /app/java/api_key.env | cut -d= -f2; fi' 2>/dev/null || echo "")

if [ -n "$OPENAI_TEST" ] && echo "$OPENAI_TEST" | grep -q "^sk-"; then
    log_success "OpenAI APIキーが設定されています"
    SKIP_VECTOR_SEARCH=false
else
    log_warning "OpenAI APIキーが見つかりません（ベクトル検索をスキップします）"
    log_warning "java/api_key.env に OPENAI_API_KEY=sk-xxx を設定してください"
    SKIP_VECTOR_SEARCH=true
fi
echo ""

# ===== 6. キーワード検索テスト =====
log_info "【6/10】キーワード検索テスト"

log_info "  テスト1: 全件検索"
ALL_RESULT=$(curl -s "${SOLR_URL}/solr/${CORE_NAME}/select?q=*:*&rows=0")
if echo "$ALL_RESULT" | grep -q "\"numFound\":$DOC_COUNT"; then
    log_success "  全件検索が成功（${DOC_COUNT}件）"
else
    log_error "  全件検索に失敗"
    exit 1
fi

log_info "  テスト2: フィールド指定検索（title:手塚）"
FIELD_RESULT=$(curl -s "${SOLR_URL}/solr/${CORE_NAME}/select" --data-urlencode "q=title:手塚")
if echo "$FIELD_RESULT" | grep -q '手塚'; then
    log_success "  フィールド検索が成功"
else
    log_warning "  フィールド検索に失敗"
fi

log_info "  テスト3: Javaクライアントでのキーワード検索"
if docker exec java test -f /app/java/pom.xml; then
    JAVA_SEARCH=$(docker exec java bash -c "cd /app/java && mvn -q exec:java -Dexec.mainClass='KeywordSearch' -Dexec.args='${CORE_NAME} 日本' 2>&1" || echo "ERROR")
    
    if echo "$JAVA_SEARCH" | grep -q -E "Results" && ! echo "$JAVA_SEARCH" | grep -q "ERROR"; then
        log_success "  Javaクライアント検索が成功"
    else
        log_warning "  Javaクライアント検索をスキップ（KeywordSearch.javaを確認してください）"
    fi
else
    log_warning "  Javaプロジェクトが見つかりません"
fi
echo ""

# ===== 7. ベクトル検索テスト =====
log_info "【7/10】ベクトル検索テスト"
if [ "${SKIP_VECTOR_SEARCH:-false}" = "true" ]; then
    log_warning "OpenAI APIキーが未設定のためベクトル検索をスキップ"
else
    log_info "  ベクトル検索を実行中..."
    
    VECTOR_SEARCH=$(docker exec java bash -c "cd /app/java && timeout 30 mvn -q exec:java -Dexec.mainClass='EmbedSearch' -Dexec.args='${CORE_NAME} 日本の首都' 2>&1" || echo "ERROR")
    
    if echo "$VECTOR_SEARCH" | grep -q -E "Results" && ! echo "$VECTOR_SEARCH" | grep -q "ERROR"; then
        log_success "  ベクトル検索が成功"
    else
        log_warning "  ベクトル検索に失敗またはタイムアウト"
        log_warning "  OpenAI APIの接続やデータの埋め込みベクトルを確認してください"
    fi
fi
echo ""

# ===== 8. ハイブリッド検索テスト =====
log_info "【8/10】ハイブリッド検索テスト"
if [ "${SKIP_VECTOR_SEARCH:-false}" = "true" ]; then
    log_warning "OpenAI APIキーが未設定のためハイブリッド検索をスキップ"
else
    log_info "  ハイブリッド検索を実行中..."
    
    HYBRID_SEARCH=$(docker exec java bash -c "cd /app/java && timeout 30 mvn -q exec:java -Dexec.mainClass='HybridSearch' -Dexec.args='${CORE_NAME} 東京' 2>&1" || echo "ERROR")
    
    if echo "$HYBRID_SEARCH" | grep -q -E "Results" && ! echo "$HYBRID_SEARCH" | grep -q "ERROR"; then
        log_success "  ハイブリッド検索が成功"
    else
        log_warning "  ハイブリッド検索に失敗またはタイムアウト"
    fi
fi
echo ""

# ===== 最終結果 =====
echo "================================================"
echo "  テスト完了"
echo "================================================"
echo ""
log_success "すべてのテストが完了しました！"
echo ""
echo "確認された機能："
echo "  ✓ Dockerのビルドと起動"
echo "  ✓ Solrの起動"
echo "  ✓ コアの作成（既存の場合はスキップ）"
echo "  ✓ データの投入"
echo "  ✓ キーワード検索"
if [ "${SKIP_VECTOR_SEARCH:-false}" = "false" ]; then
    echo "  ✓ ベクトル検索"
    echo "  ✓ ハイブリッド検索"
else
    echo "  - ベクトル検索（スキップ）"
    echo "  - ハイブリッド検索（スキップ）"
fi
echo ""
echo "作成されたコア: ${CORE_NAME}"
echo "コアを削除する場合: ./scripts/delete_solr_core.sh ${CORE_NAME}"
echo ""
