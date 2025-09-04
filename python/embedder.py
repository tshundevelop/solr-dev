from sentence_transformers import SentenceTransformer

class TextEmbedder:
    def __init__(self, model_name="intfloat/multilingual-e5-small"):
        self.model = SentenceTransformer(model_name)

    def embed(self, text):
        return self.model.encode(text)
    
if __name__ == "__main__":
    embedder = TextEmbedder("intfloat/multilingual-e5-small")
    vector = embedder.embed("こんにちは、世界！")
    print(vector)
    print(type(vector))
    print(vector.shape)
    print(vector.dtype)
