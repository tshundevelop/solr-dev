# python_service/app.py
from flask import Flask, request, jsonify
from embedder import TextEmbedder

app = Flask(__name__)
# モデルを事前にロードしておくことで、リクエストごとのオーバーヘッドを削減します
model = TextEmbedder("intfloat/multilingual-e5-large")

@app.route('/embed', methods=['POST'])
def get_embedding():
    """
    POSTリクエストで受け取ったテキストの埋め込みベクトルを返します。
    """
    data = request.get_json()
    if 'text' not in data:
        return jsonify({"error": "No 'text' field provided"}), 400

    text_to_embed = data['text']
    try:
        # テキストをベクトルに変換
        embedding = model.embed(text_to_embed)
        # NumPy配列をリストに変換してJSONで返せるようにする
        embedding_list = embedding.tolist()
        return jsonify({"embedding": embedding_list})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    # 外部からのアクセスを許可するために'0.0.0.0'でホストします
    app.run(host='0.0.0.0', port=5000)
