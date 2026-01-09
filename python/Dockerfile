# Pythonの公式イメージを使用
FROM python:3.9-slim

# 必要なツールをインストール
RUN apt-get update && apt-get install -y \
    wget \
    tar \
    mecab \
    libmecab-dev \
    mecab-ipadic-utf8 \
    build-essential \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# 作業ディレクトリを設定
WORKDIR /app/python

# 依存関係をインストール
COPY python/requirements.txt .
RUN pip install --upgrade pip
# RUN pip install --no-cache-dir -r requirements.txt
RUN pip install --cache-dir /root/.cache/pip -r requirements.txt

# chiVeモデルをダウンロード（約90MB）
RUN mkdir -p /app/models && \
    cd /app/models && \
    wget -q https://sudachi.s3-ap-northeast-1.amazonaws.com/chive/chive-1.2-mc90.tar.gz && \
    tar xzf chive-1.2-mc90.tar.gz && \
    rm chive-1.2-mc90.tar.gz && \
    echo "✅ chiVeモデルをダウンロードしました"

# アプリケーションコードをコンテナにコピー
COPY . .

# Flaskアプリケーションを実行
# CMD ["python", "python/app.py"]
