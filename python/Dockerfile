# Pythonの公式イメージを使用
FROM python:3.9-slim

# 作業ディレクトリを設定
WORKDIR /app

# 依存関係をインストール
COPY python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# アプリケーションコードをコンテナにコピー
COPY . .

# Flaskアプリケーションを実行
CMD ["python", "python/app.py"]
