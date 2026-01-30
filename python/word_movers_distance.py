#!/usr/bin/env python3
"""
日本語のWord Mover's Distance (WMD) 計算
Gensimライブラリを使用した実装
"""

import numpy as np
from typing import List, Tuple
import MeCab
from gensim.models import KeyedVectors
import warnings
import os
warnings.filterwarnings('ignore')

# デフォルトモデルパス
DEFAULT_MODEL_PATH = os.environ.get('WMD_MODEL_PATH', '/app/models/chive-1.2-mc90/chive-1.2-mc90.txt')


class JapaneseWMD:
    """日本語テキストのWord Mover's Distance計算クラス（Gensim使用）"""
    
    def __init__(self, model_path: str = None):
        """
        初期化
        
        Args:
            model_path: Word2Vecモデルのパス（Noneの場合はデフォルトパスを使用）
        """
        self.mecab = MeCab.Tagger()
        
        if model_path is None:
            model_path = DEFAULT_MODEL_PATH
        
        if not os.path.exists(model_path):
            raise FileNotFoundError(
                f"❌ モデルファイルが見つかりません: {model_path}\n"
                f"Dockerコンテナ内で実行してください。"
            )
        
        print(f"📦 モデル読み込み中: {model_path}")
        self.model = KeyedVectors.load_word2vec_format(model_path, binary=False)
        print(f"✅ モデル読み込み完了 (語彙数: {len(self.model)})")
    
    def tokenize(self, text: str, pos_filter: List[str] = None) -> List[str]:
        """
        MeCabを使用してテキストを形態素解析
        
        Args:
            text: 入力テキスト
            pos_filter: 抽出する品詞のリスト
        
        Returns:
            単語のリスト
        """
        if pos_filter is None:
            pos_filter = ['名詞', '動詞', '形容詞']
        
        node = self.mecab.parseToNode(text)
        words = []
        
        while node:
            features = node.feature.split(',')
            pos = features[0]
            
            if pos in pos_filter and len(node.surface) > 0:
                # モデルに存在する単語のみを追加
                if node.surface in self.model:
                    words.append(node.surface)
            
            node = node.next
        
        return words
    
    def compute_wmd(self, text1: str, text2: str) -> float:
        """
        2つのテキスト間のWord Mover's Distanceを計算
        
        Args:
            text1: 文書1
            text2: 文書2
        
        Returns:
            WMD距離（小さいほど類似）
        """
        # テキストをトークン化
        words1 = self.tokenize(text1)
        words2 = self.tokenize(text2)
        
        if len(words1) == 0 or len(words2) == 0:
            print("⚠️ トークン化後に有効な単語が見つかりませんでした")
            return float('inf')
        
        print(f"📝 文書1の単語: {words1}")
        print(f"📝 文書2の単語: {words2}")
        
        try:
            # Gensimの組み込みWMD機能を使用
            distance = self.model.wmdistance(words1, words2)
            return distance
        except Exception as e:
            print(f"⚠️ WMD計算エラー: {e}")
            return float('inf')
    
    def find_most_similar(self, query: str, documents: List[str], top_k: int = 5) -> List[Tuple[int, str, float]]:
        """
        クエリに最も類似した文書を検索
        
        Args:
            query: クエリテキスト
            documents: 文書のリスト
            top_k: 返す文書数
        
        Returns:
            (インデックス, 文書, WMD距離) のリスト
        """
        results = []
        
        for idx, doc in enumerate(documents):
            distance = self.compute_wmd(query, doc)
            results.append((idx, doc, distance))
            print(f"[{idx+1}/{len(documents)}] WMD距離: {distance:.4f}")
        
        # 距離でソート（昇順 = 類似度が高い順）
        results.sort(key=lambda x: x[2])
        
        return results[:top_k]


def main():
    """デモ実行"""
    import sys
    
    print("=" * 60)
    print("日本語 Word Mover's Distance デモ")
    print("=" * 60)
    
    # モデルパスを引数から取得（省略可能）
    model_path = sys.argv[1] if len(sys.argv) >= 2 else None
    
    if model_path is None:
        print(f"\nデフォルトモデルを使用: {DEFAULT_MODEL_PATH}")
    
    # WMDインスタンス作成
    try:
        wmd = JapaneseWMD(model_path)
    except FileNotFoundError as e:
        print(f"\n{e}")
        print("\n使用方法:")
        print("  python word_movers_distance.py [model_path]")
        print("\n例:")
        print("  python word_movers_distance.py /path/to/model.kv")
        print("\nまたは環境変数で指定:")
        print("  export WMD_MODEL_PATH=/path/to/model.kv")
        sys.exit(1)
    
    # サンプルテキスト
    query = "東京の美しい桜の花が咲いている"
    
    documents = [
        "京都で桜が満開になりました",
        "東京タワーは有名な観光地です",
        "美しい花が春に咲きます",
        "コンピュータのプログラミングは難しい",
        "桜の花見を楽しむ人々",
        "富士山は日本一高い山です",
        "東京の素晴らしい夜桜が咲いている",
        "東京の夜景はとても美しいです",
        "東京には美味しいレストランがたくさんあります",
        "東京で桃の花が咲いている"
    ]
    
    print(f"\n🔍 クエリ: {query}\n")
    print("📚 文書リスト:")
    for i, doc in enumerate(documents):
        print(f"  [{i}] {doc}")
    
    print("\n" + "=" * 60)
    print("WMD計算中...")
    print("=" * 60 + "\n")
    
    # 最も類似した文書を検索
    results = wmd.find_most_similar(query, documents, top_k=3)
    
    print("\n" + "=" * 60)
    print("🏆 検索結果 (類似度順)")
    print("=" * 60)
    
    for rank, (idx, doc, distance) in enumerate(results, 1):
        print(f"\n{rank}位: WMD距離 = {distance:.4f}")
        print(f"  文書ID: {idx}")
        print(f"  内容: {doc}")
    
    print("\n" + "=" * 60)
    print("✅ 完了")
    print("=" * 60)


if __name__ == "__main__":
    main()
