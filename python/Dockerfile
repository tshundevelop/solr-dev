# Pythonの公式イメージを使用
FROM python:3.9-slim

# 必要なツールをインストール
RUN apt-get update && apt-get install -y \
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
RUN pip install --cache-dir /root/.cache/pip -r requirements.txt

# 必要なPythonファイルのみをコピー
COPY python/*.py .

# Flaskアプリケーションを実行
# CMD ["python", "app.py"]
