import json

embedding_path = "java/Result/embedding/baseline/20251113_161832/status.json"
keyword_path = "java/Result/keyword/baseline/20251113_135947/status.json"
hybrid_path = "java/Result/hybrid/baseline/20251113_162818/status.json"

def read_status(path: str) -> dict[str, any]:
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)

embedding_status = read_status(embedding_path)
keyword_status = read_status(keyword_path)
hybrid_status = read_status(hybrid_path)

positive_cases = set(embedding_status["correct"]) & set(keyword_status["correct"])
negative_cases = set(embedding_status["incorrect"]) & set(keyword_status["incorrect"])

print(len(set(embedding_status["incorrect"]) & set(keyword_status["correct"])))
print(len(set(embedding_status["correct"]) & set(keyword_status["incorrect"])))
print(len(set(embedding_status["correct"]) & set(keyword_status["incorrect"]) & set(hybrid_status["correct"])))
# print((set(embedding_status["incorrect"]) & set(keyword_status["correct"])) & set(hybrid_status["correct"]))
print(positive_cases & set(hybrid_status["incorrect"]))