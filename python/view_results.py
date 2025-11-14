import json
import os
from pathlib import Path
from typing import Dict, List, Any

import streamlit as st
import pandas as pd


def find_result_root() -> Path:
    # このファイルの親(=repo rootを想定)から java/Result を探す
    here = Path(__file__).resolve()
    candidates = [
        here.parent.parent / "java" / "Result",  # repo/python -> repo/java/Result
        here.parent / "java" / "Result",         # 保険
        Path.cwd() / "java" / "Result",           # 実行ディレクトリ基準
    ]
    for p in candidates:
        if p.exists() and p.is_dir():
            return p
    return candidates[0]


def list_tree(result_root: Path) -> Dict[str, Dict[str, List[str]]]:
    """
    構造を {type: {folder: [timestamps...]}} で返す
    java/Result/<type>/<folder>/<timestamp>/
    """
    tree: Dict[str, Dict[str, List[str]]] = {}
    if not result_root.exists():
        return tree
    for type_dir in sorted([p for p in result_root.iterdir() if p.is_dir()]):
        type_name = type_dir.name
        tree[type_name] = {}
        for folder_dir in sorted([p for p in type_dir.iterdir() if p.is_dir()]):
            folder_name = folder_dir.name
            timestamps = [p.name for p in sorted(folder_dir.iterdir()) if p.is_dir()]
            tree[type_name][folder_name] = timestamps
    return tree


def load_json(path: Path) -> Any:
    if not path.exists():
        return None
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def main():
    st.set_page_config(page_title="Solr-dev Result Viewer", layout="wide")
    st.title("Result Viewer (java/Result)")

    result_root = find_result_root()
    st.caption(f"Base: {result_root}")

    tree = list_tree(result_root)
    if not tree:
        st.warning("java/Result 配下が見つかりませんでした。まずは実行結果を生成してください。")
        return

    # Sidebar selectors
    with st.sidebar:
        st.header("Select Result")
        type_list = sorted(tree.keys())
        type_sel = st.selectbox("search type", type_list, index=0)
        folder_list = sorted(tree[type_sel].keys()) if type_sel in tree else []
        folder_sel = st.selectbox("folder", folder_list, index=0 if folder_list else None)
        ts_list = tree[type_sel].get(folder_sel, []) if folder_sel else []
        ts_sel = st.selectbox("timestamp", ts_list, index=len(ts_list)-1 if ts_list else None)

        st.markdown("---")
        show_context = st.checkbox("Show context field in tables", value=False)
        max_rows = st.number_input("Max rows to preview (results.json)", min_value=1, max_value=10000, value=100, step=50)

    if not (type_sel and folder_sel and ts_sel):
        st.info("左のサイドバーから結果を選択してください。")
        return

    base_dir = result_root / type_sel / folder_sel / ts_sel
    results_path = base_dir / "results.json"
    status_path = base_dir / "status.json"
    summary_path = base_dir / "summary.json"

    cols = st.columns(3)
    with cols[0]:
        st.subheader("summary.json")
        summary = load_json(summary_path)
        if summary:
            cfg = summary.get("configuration", {})
            res = summary.get("results", {})
            st.write({
                "evaluationType": cfg.get("evaluationType"),
                "topk": cfg.get("topk"),
                "numDocs": cfg.get("numberOfDocuments"),
                "resultFolder": cfg.get("resultFolderName"),
                "avgCoverage": res.get("averageCoverage"),
                "avgMRR": res.get("averageMrr"),
                "avgLRAP": res.get("averageLrap"),
                "avgMRR_LRAP": res.get("averageMrrAndLrap"),
            })
        else:
            st.info("summary.json が見つかりません")

    with cols[1]:
        st.subheader("status.json")
        status = load_json(status_path)
        if status:
            correct = status.get("correct", [])
            incorrect = status.get("incorrect", [])
            st.metric("correct", len(correct))
            st.metric("incorrect", len(incorrect))
        else:
            st.info("status.json が見つかりません")

    with cols[2]:
        st.subheader("files")
        st.write({"dir": str(base_dir)})
        st.write({"results.json": results_path.exists(), "status.json": status_path.exists(), "summary.json": summary_path.exists()})

    # results.json を読み込み
    results = load_json(results_path) or []
    if not isinstance(results, list) or len(results) == 0:
        st.info("results.json のデータがありません")
        return

    # Record detail を先に表示
    st.markdown("---")
    st.subheader("Record detail")
    # 選択方法: インデックス or 正解ID(correctId)
    id_list = [str(item.get("correctId")) for item in results]
    sel_mode = st.radio("select by", ["Index", "Correct ID"], horizontal=True)
    if sel_mode == "Index":
        idx_num = st.number_input("record index (1-based)", min_value=1, max_value=len(results), value=1, step=1)
        rec_index = int(idx_num) - 1
    else:
        default_idx = 0 if id_list else 0
        sel_id = st.selectbox("correctId", id_list, index=default_idx)
        # IDからインデックスへ解決（見つからない場合は0）
        rec_index = next((i for i, it in enumerate(results) if str(it.get("correctId")) == sel_id), 0)
    rec = results[rec_index]

    c1, c2 = st.columns(2)
    with c1:
        st.write("Main info:")
        st.json({
            "correctId": rec.get("correctId"),
            "title": rec.get("title"),
            "question": rec.get("question"),
            "splittedQuestion": rec.get("splittedQuestion"),
            "paraphraseQuestion": rec.get("paraphraseQuestion"),
        })
        st.write("Metrics:")
        st.json({
            "coverage": rec.get("coverage"),
            "mrr": rec.get("mrr"),
            "lrap": rec.get("lrap"),
            "avgMRR_LRAP": rec.get("averageMrrAndLrap"),
            "numFound": rec.get("numFound"),
        })
    with c2:
        st.write("Search results (detailed view)")
        sr_list = rec.get("searchResults", [])
        if isinstance(sr_list, list) and len(sr_list) > 0:
            show_n = st.number_input(
                "hits to show in detail",
                min_value=1,
                max_value=min(50, len(sr_list)),
                value=min(10, len(sr_list)),
                step=1,
            )
            for i, s in enumerate(sr_list[: int(show_n)]):
                title = s.get("title")
                score = s.get("score")
                sid = s.get("id")
                header = f"{i+1}. [score={score}] {title} (id={sid})"
                with st.expander(header, expanded=False):
                    ctx = s.get("context")
                    if not isinstance(ctx, str):
                        ctx = str(ctx) if ctx is not None else ""
                    unique_key = f"context_{rec_index+1}_{i}_{sid if sid is not None else 'noid'}"
                    st.text_area("context", value=ctx, height=200, key=unique_key)
        else:
            st.info("No search results found in this record.")

    # 続けて results.json のプレビューを下部に表示
    st.markdown("---")
    st.subheader("results.json (preview)")
    table_rows = []
    for i, item in enumerate(results[: int(max_rows)]):
        row = {
            "#": i + 1,
            "correctId": item.get("correctId"),
            "question": item.get("question"),
            "coverage": item.get("coverage"),
            "mrr": item.get("mrr"),
            "lrap": item.get("lrap"),
            "avgMRR_LRAP": item.get("averageMrrAndLrap"),
        }
        if show_context:
            try:
                sr = item.get("searchResults", [])
                if sr:
                    first = sr[0]
                    row["hit_id"] = first.get("id")
                    row["hit_title"] = first.get("title")
                    ctx = first.get("context")
                    if isinstance(ctx, str) and len(ctx) > 120:
                        ctx = ctx[:120] + "..."
                    row["hit_context"] = ctx
            except Exception:
                pass
        table_rows.append(row)
    df = pd.DataFrame(table_rows)
    st.dataframe(df, width='stretch')


if __name__ == "__main__":
    main()
