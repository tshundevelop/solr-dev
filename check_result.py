import json

embedding_path = "java/Result/embedding/baseline/20251107_020625/status.json"
keyword_path = "java/Result/keyword/baseline/20251107_074332/status.json"
hybrid_path = "java/Result/hybrid/baseline/20251107_074301/status.json"

def read_status(path: str) -> dict[str, any]:
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)

embedding_status = read_status(embedding_path)
keyword_status = read_status(keyword_path)
hybrid_status = read_status(hybrid_path)

print(len(set(embedding_status["correct"] + keyword_status["correct"])))
print(len(set(hybrid_status["correct"])))
