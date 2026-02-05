#!/usr/bin/env python3
"""
Hugging Faceデータセットをダウンロードして処理するスクリプト
使い方: python download_and_process.py --dataset <dataset_name> --name <short_name> --id-field <id> --title-field <title> --context-field <context>
"""
import json
import argparse
from pathlib import Path
from typing import Optional, Any, Dict
from tqdm import tqdm
from datasets import load_dataset
from huggingface_hub import list_repo_files, hf_hub_download
from collections import defaultdict


def find_nested_field(data: Any, field_name: str) -> Any:
    """
    ネストされた辞書から指定されたフィールドを再帰的に検索
    
    Args:
        data: 検索対象のデータ（辞書、リスト、またはその他）
        field_name: 検索するフィールド名
    
    Returns:
        見つかった値、見つからない場合はNone
    """
    if isinstance(data, dict):
        # 直接フィールドが存在する場合
        if field_name in data:
            return data[field_name]
        # 再帰的に検索
        for value in data.values():
            result = find_nested_field(value, field_name)
            if result is not None:
                return result
    elif isinstance(data, list) and len(data) > 0:
        # リストの最初の要素を検索
        return find_nested_field(data[0], field_name)
    
    return None


def extract_field_value(record: Dict, field_name: str, default: str = "") -> str:
    """
    レコードからフィールド値を抽出（ネスト対応）
    """
    # 直接アクセスを試みる
    if field_name in record:
        return str(record[field_name])
    
    # ネストされたフィールドを検索
    value = find_nested_field(record, field_name)
    if value is not None:
        return str(value)
    
    return default


def process_jaquad_merging(raw_data):
    """
    JaQuAD専用処理: IDの左3桁でグループ化してcontextを結合
    ID形式: tr-000-00-000 の左3桁が同じものはcontextを結合
    引数: フラット化されたprocessed_data（id, title, context フィールドを持つ辞書のリスト）
    """
    print("\n=== JaQuAD Special Processing ===")
    print("Grouping by ID prefix and merging contexts...")
    
    # IDの左3桁でグループ化
    grouped = defaultdict(list)
    
    for record in raw_data:
        record_id = record.get("id", "")
        if not record_id:
            continue
            
        # IDから左3桁を取得 (例: "tr-000-00-000" -> "tr-000")
        id_parts = record_id.split("-")
        if len(id_parts) >= 2:
            prefix = f"{id_parts[0]}-{id_parts[1]}"  # "tr-000" または "de-000"
        else:
            prefix = record_id
        
        grouped[prefix].append(record)
    
    print(f"  Grouped into {len(grouped)} groups from {len(raw_data)} records")
    
    # グループごとにcontextを結合
    merged_data = []
    total_original_contexts = 0
    total_unique_contexts = 0
    
    for prefix, records in tqdm(grouped.items(), desc="Merging"):
        # recordsを元のIDでソート
        records_sorted = sorted(records, key=lambda x: x.get("id", ""))
        
        # 最初のレコードをベースにする
        base_record = records_sorted[0].copy()
        
        # 統計用
        original_count = len(records_sorted)
        total_original_contexts += original_count
        
        # contextを重複排除して結合
        unique_contexts = []
        seen_contexts = set()
        
        for r in records_sorted:
            context = r.get("context", "").strip()
            if not context:
                continue
                
            # 完全一致チェック
            if context in seen_contexts:
                continue
            
            # 部分重複チェック
            is_duplicate = False
            for existing_context in seen_contexts:
                # 新しいcontextが既存の中に完全に含まれている場合はスキップ
                if context in existing_context:
                    is_duplicate = True
                    break
                # 既存のcontextが新しいcontextに完全に含まれている場合は既存を削除
                elif existing_context in context:
                    unique_contexts = [c for c in unique_contexts if c != existing_context]
                    seen_contexts.remove(existing_context)
                    break
            
            if not is_duplicate:
                unique_contexts.append(context)
                seen_contexts.add(context)
        
        # 重複排除されたcontextを改行で結合
        merged_context = "\n".join(unique_contexts)
        
        # マージされたレコードを作成
        merged_record = {
            "id": prefix,
            "title": base_record.get("title", ""),
            "context": merged_context
        }
        
        # 統計更新
        unique_count = len(unique_contexts)
        total_unique_contexts += unique_count
        
        merged_data.append(merged_record)
    
    # 統計表示
    duplicate_count = total_original_contexts - total_unique_contexts
    duplicate_ratio = (duplicate_count / total_original_contexts * 100) if total_original_contexts > 0 else 0
    print(f"\nJaQuAD Merging Statistics:")
    print(f"  Original contexts: {total_original_contexts}")
    print(f"  Unique contexts: {total_unique_contexts}")
    print(f"  Duplicates removed: {duplicate_count} ({duplicate_ratio:.1f}%)")
    print(f"  Original records: {len(raw_data)}")
    print(f"  Merged records: {len(merged_data)}")
    print(f"  Reduction ratio: {((len(raw_data) - len(merged_data)) / len(raw_data) * 100):.1f}%")
    
    return merged_data
import json


def download_and_process_dataset(
    dataset_name: str,
    split: str,
    name: str,
    id_field: str,
    title_field: str,
    context_field: str,
    id_prefix: Optional[str] = None,
    subset: Optional[str] = None,
    max_records: Optional[int] = None
) -> None:
    """
    Hugging Faceからデータセットをダウンロードして処理
    
    Args:
        dataset_name: Hugging Faceのデータセット名（例: "rajpurkar/squad"）
        split: データセットのsplit（例: "train", "validation"）
        name: データセット短縮名（フォルダ名・ファイル名用）
        id_field: IDフィールド名（"_generated_"で自動生成）
        title_field: タイトルフィールド名
        context_field: コンテキストフィールド名
        id_prefix: ID自動生成時のプレフィックス
        subset: データセットのサブセット名（オプション）
        max_records: 取得する最大レコード数（Noneの場合は全件）
    """
    print("=" * 70)
    print("Downloading and Processing Hugging Face Dataset")
    print("=" * 70)
    print(f"Dataset: {dataset_name}")
    print(f"Split: {split}")
    print(f"Subset: {subset if subset else 'None'}")
    print(f"Output name: {name}")
    print(f"Field mapping:")
    print(f"  ID field: {id_field}")
    print(f"  Title field: {title_field}")
    print(f"  Context field: {context_field}")
    print("=" * 70)
    
    # 出力ディレクトリ作成
    base_dir = Path("/app/data")
    raw_dir = base_dir / name
    processed_dir = raw_dir / "processed"
    raw_dir.mkdir(parents=True, exist_ok=True)
    processed_dir.mkdir(parents=True, exist_ok=True)
    
    # Step 1: ダウンロード
    print("\n[Step 1/3] Downloading dataset from Hugging Face...")
    if max_records:
        print(f"Maximum records to download: {max_records}")
    dataset = None
    
    # 方法1: 標準のload_dataset
    try:
        print("Trying standard load_dataset...")
        if subset:
            dataset = load_dataset(dataset_name, subset, split=split)
        else:
            dataset = load_dataset(dataset_name, split=split)
        print(f"✓ Downloaded {len(dataset)} records via standard method")
    except Exception as e:
        print(f"✗ Standard method failed: {e}")
        
        # 方法2: Parquetファイルから取得
        try:
            print("\nTrying to load from parquet files...")
            # splitに対応するparquetファイルを探す
            parquet_patterns = [
                f"{split}/*.parquet",
                f"**/{split}*.parquet",
                "**/*.parquet"
            ]
            
            for pattern in parquet_patterns:
                try:
                    data_files = {split: pattern}
                    dataset = load_dataset(dataset_name, data_files=data_files, split=split)
                    print(f"✓ Downloaded {len(dataset)} records from parquet files (pattern: {pattern})")
                    break
                except:
                    continue
            else:
                raise Exception("No parquet files found")
                
        except Exception as e2:
            print(f"✗ Parquet method failed: {e2}")
            
            # 方法3: JSONファイルを直接ダウンロード
            try:
                print("\nTrying to download JSON files directly from repository...")
                
                # リポジトリ内のファイル一覧を取得
                try:
                    files = list_repo_files(dataset_name, repo_type="dataset")
                    json_files = [f for f in files if f.endswith('.json') and split in f.lower()]
                    
                    if not json_files:
                        # splitが見つからない場合は全JSONファイルを試す
                        json_files = [f for f in files if f.endswith('.json')]
                    
                    print(f"  Found {len(json_files)} JSON files in repository")
                    
                    if json_files:
                        # 全JSONファイルをダウンロード
                        raw_data_list = []
                        for json_file in json_files:
                            print(f"  Downloading: {json_file}")
                            try:
                                file_path = hf_hub_download(
                                    repo_id=dataset_name,
                                    filename=json_file,
                                    repo_type="dataset"
                                )
                                
                                with open(file_path, 'r', encoding='utf-8') as f:
                                    file_content = json.load(f)
                                    # JaQuAD形式の場合、dataキーの中身を展開
                                    if isinstance(file_content, dict) and 'data' in file_content:
                                        raw_data_list.extend(file_content['data'])
                                    elif isinstance(file_content, list):
                                        raw_data_list.extend(file_content)
                                    else:
                                        raw_data_list.append(file_content)
                            except Exception as file_error:
                                print(f"  ⚠ Failed to download {json_file}: {str(file_error)[:100]}")
                                continue
                        
                        # 辞書のリストに変換（datasets形式に合わせる）
                        dataset = raw_data_list
                        print(f"✓ Downloaded {len(dataset)} records from {len(json_files)} JSON files")
                    else:
                        raise Exception("No JSON files found in repository")
                        
                except Exception as hub_error:
                    print(f"  Direct download failed: {str(hub_error)[:100]}")
                    raise
                    
            except Exception as e3:
                print(f"✗ JSON method failed: {e3}")
                print(f"\nAll download methods failed. Please check:")
                print(f"  1. Dataset name: {dataset_name}")
                print(f"  2. Split: {split}")
                print(f"  3. Dataset availability on Hugging Face")
                return
    
    if dataset is None:
        print("✗ Failed to download dataset")
        return
    
    # datasetがリストの場合（直接ダウンロードの場合）とDatasetオブジェクトの場合を区別
    if isinstance(dataset, list):
        # 直接ダウンロードの場合はすでにリスト形式
        raw_data = dataset
        is_direct_download = True
    else:
        # Datasetオブジェクトの場合
        is_direct_download = False
        # データ数を制限
        if max_records and len(dataset) > max_records:
            print(f"Limiting dataset to {max_records} records (original: {len(dataset)})")
            dataset = dataset.select(range(max_records))
    
    # Step 2: RAWデータを保存
    raw_file = raw_dir / f"{name}_{split}_raw.json"
    print(f"\n[Step 2/3] Saving raw data to {raw_file}...")
    
    if is_direct_download:
        # 直接ダウンロードの場合
        if max_records and len(raw_data) > max_records:
            print(f"Limiting dataset to {max_records} records (original: {len(raw_data)})")
            raw_data = raw_data[:max_records]
    else:
        # Datasetオブジェクトの場合
        raw_data = []
        for item in tqdm(dataset, desc="Converting to JSON"):
            raw_data.append(dict(item))
    
    with open(raw_file, "w", encoding="utf-8") as f:
        json.dump(raw_data, f, ensure_ascii=False, indent=2)
    print(f"✓ Saved {len(raw_data)} records")
    
    # JaQuAD専用処理: article + paragraphs + qas 構造をフラット化
    if "jaquad" in dataset_name.lower():
        print("\n[JaQuAD] Flattening nested structure (article → paragraphs → QA pairs)...")
        flattened_data = []
        for article in tqdm(raw_data, desc="Flattening"):
            title = article.get("title", "")
            paragraphs = article.get("paragraphs", [])
            
            for paragraph in paragraphs:
                context = paragraph.get("context", "")
                qas = paragraph.get("qas", [])
                
                for qa in qas:
                    flattened_record = {
                        "id": qa.get("id", ""),
                        "title": title,
                        "context": context,
                        "question": qa.get("question", ""),
                        "answers": qa.get("answers", [])
                    }
                    flattened_data.append(flattened_record)
        
        raw_data = flattened_data
        print(f"✓ Flattened to {len(raw_data)} QA pair records")
        
        # MAX_RECORDS制限を適用（フラット化後）
        if max_records and len(raw_data) > max_records:
            print(f"Limiting to {max_records} records (flattened: {len(raw_data)})")
            raw_data = raw_data[:max_records]
    
    # 利用可能なフィールドを表示
    if raw_data:
        print("\nAvailable fields in dataset:")
        sample_record = raw_data[0]
        
        def print_fields(data, prefix=""):
            """ネストされたフィールドも表示"""
            if isinstance(data, dict):
                for key, value in data.items():
                    field_path = f"{prefix}{key}" if prefix else key
                    if isinstance(value, (dict, list)):
                        print(f"  - {field_path} (nested)")
                        if isinstance(value, dict):
                            print_fields(value, f"{field_path}.")
                        elif isinstance(value, list) and len(value) > 0 and isinstance(value[0], dict):
                            print_fields(value[0], f"{field_path}[0].")
                    else:
                        print(f"  - {field_path}")
        
        print_fields(sample_record)
    
    # Check field existence with nested support
    if raw_data:
        missing_fields = []
        sample_record = raw_data[0]
        
        if id_field != "_generated_":
            if id_field not in sample_record and find_nested_field(sample_record, id_field) is None:
                missing_fields.append(id_field)
        
        if title_field != "_generated_":
            if title_field not in sample_record and find_nested_field(sample_record, title_field) is None:
                missing_fields.append(title_field)
        
        if context_field not in sample_record and find_nested_field(sample_record, context_field) is None:
            missing_fields.append(context_field)
        
        if missing_fields:
            print(f"\n⚠️  WARNING: The following specified fields are NOT found in the dataset:")
            for field in missing_fields:
                print(f"     - {field}")
            print("\n   Please check your field names and re-run with correct field names.")
            print("   Note: Nested fields are searched automatically.")
            return
    
    # Step 3: Process data
    print(f"\n[Step 3/3] Processing data...")
    
    processed_data = []
    
    # サンプルレコードの構造をデバッグ表示
    if raw_data:
        print(f"\nDebug: First record structure:")
        print(json.dumps(raw_data[0], ensure_ascii=False, indent=2)[:500])
        print("...")
    
    for idx, record in enumerate(tqdm(raw_data, desc="Processing")):
        # Get or generate ID with nested support
        if id_field == "_generated_":
            prefix = id_prefix or name[:2]
            record_id = f"{prefix}-{idx+1:06d}"
        else:
            record_id = extract_field_value(record, id_field, f"{name}-{idx+1:06d}")
        
        # Get title and context with nested support
        title = extract_field_value(record, title_field, "") if title_field != "_generated_" else ""
        context = extract_field_value(record, context_field, "")
        
        # デバッグ: 最初の数レコードの抽出結果を表示
        if idx < 2:
            print(f"\nDebug record {idx}:")
            print(f"  ID field '{id_field}' -> '{record_id}'")
            print(f"  Title field '{title_field}' -> '{title[:50] if title else '(empty)'}'")
            print(f"  Context field '{context_field}' -> '{context[:50] if context else '(empty)'}'")
        
        # Skip empty data
        if not context.strip():
            continue
        
        processed_record = {
            "id": record_id,
            "title": title,
            "context": context
        }
        
        processed_data.append(processed_record)
    
    print(f"✓ Processed {len(processed_data)} records")
    
    # JaQuAD専用処理: contextをグループ化して結合（フラット化後に実行）
    if "jaquad" in dataset_name.lower():
        print("\nApplying JaQuAD-specific merging...")
        processed_data = process_jaquad_merging(processed_data)
    
    # 統計情報
    if processed_data:
        avg_context_len = sum(len(r["context"]) for r in processed_data) / len(processed_data)
        print(f"  Average context length: {avg_context_len:.1f} characters")
    
    # productionデータ保存（validation不要）
    production_output = processed_dir / f"{name}_production_{len(processed_data)}.json"
    with production_output.open("w", encoding="utf-8") as f:
        json.dump(processed_data, f, ensure_ascii=False, indent=2)
    print(f"\n✓ Production: {len(processed_data)} records → {production_output}")
    
    # サンプル表示
    if processed_data:
        print("\n" + "=" * 70)
        print("Sample record:")
        print("=" * 70)
        sample = processed_data[0]
        print(f"ID: {sample['id']}")
        print(f"Title: {sample['title']}")
        print(f"Context length: {len(sample['context'])} chars")
        print(f"Context preview: {sample['context'][:200]}...")
        print("=" * 70)
    
    print(f"\n✓ All done! Files saved to:")
    print(f"  Raw data: {raw_file}")
    print(f"  Processed: {production_output}")


def main():
    parser = argparse.ArgumentParser(
        description="Download and process Hugging Face dataset in one command",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # SQuAD dataset
  python download_and_process.py \\
    --dataset rajpurkar/squad \\
    --split train \\
    --name squad \\
    --id-field id \\
    --title-field title \\
    --context-field context

  # Generate ID automatically
  python download_and_process.py \\
    --dataset wikitext \\
    --subset wikitext-2-raw-v1 \\
    --split train \\
    --name wikitext \\
    --id-field _generated_ \\
    --title-field _generated_ \\
    --context-field text \\
    --id-prefix wt

  # Japanese dataset
  python download_and_process.py \\
    --dataset SkelterLabsInc/JaQuAD \\
    --split train \\
    --name jaquad \\
    --id-field id \\
    --title-field title \\
    --context-field context
        """
    )
    
    parser.add_argument(
        "--dataset", "-d",
        required=True,
        help="Hugging Face dataset name (e.g., 'rajpurkar/squad')"
    )
    parser.add_argument(
        "--split", "-s",
        default="train",
        help="Dataset split (default: train)"
    )
    parser.add_argument(
        "--name", "-n",
        required=True,
        help="Short name for output files and folders"
    )
    parser.add_argument(
        "--id-field",
        default="id",
        help="Field name for ID (use '_generated_' to auto-generate)"
    )
    parser.add_argument(
        "--title-field",
        default="title",
        help="Field name for title (use '_generated_' for auto-generated empty title)"
    )
    parser.add_argument(
        "--context-field",
        default="context",
        help="Field name for context"
    )
    parser.add_argument(
        "--id-prefix",
        default=None,
        help="Prefix for auto-generated IDs (default: first 2 chars of name)"
    )
    parser.add_argument(
        "--subset",
        default=None,
        help="Dataset subset name (if applicable)"
    )
    parser.add_argument(
        "--max-records",
        type=int,
        default=None,
        help="Maximum number of records to download (default: all)"
    )
    
    args = parser.parse_args()
    
    download_and_process_dataset(
        dataset_name=args.dataset,
        split=args.split,
        name=args.name,
        id_field=args.id_field,
        title_field=args.title_field,
        context_field=args.context_field,
        id_prefix=args.id_prefix,
        subset=args.subset,
        max_records=args.max_records
    )


if __name__ == "__main__":
    main()
