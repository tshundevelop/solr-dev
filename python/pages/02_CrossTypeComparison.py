import json
from pathlib import Path
import sys
import streamlit as st
import pandas as pd

# Ensure parent directory (python/) is on sys.path so we can import view_results utilities
_THIS_DIR = Path(__file__).resolve().parent
_PARENT_DIR = _THIS_DIR.parent
if str(_PARENT_DIR) not in sys.path:
    sys.path.insert(0, str(_PARENT_DIR))

from view_results import find_result_root, list_tree, load_json


def main():
    st.set_page_config(page_title="Cross-type comparison", layout="wide")
    st.title("Cross-type comparison (keyword vs embedding vs hybrid)")

    result_root = find_result_root()
    st.caption(f"Base: {result_root}")

    tree = list_tree(result_root)
    if not tree:
        st.warning("java/Result 配下が見つかりませんでした。まずは実行結果を生成してください。")
        return

    # Build selectors for each type
    type_names = ["keyword", "embedding", "hybrid"]
    sel_cols = st.columns(3)
    selections = {}
    for col, tname in zip(sel_cols, type_names):
        with col:
            st.subheader(tname)
            folders = sorted(tree.get(tname, {}).keys())
            if not folders:
                st.info(f"No results for {tname}")
                continue
            folder_sel_cmp = st.selectbox(f"{tname} folder", folders, key=f"cmp_{tname}_folder")
            ts_list_cmp = tree[tname].get(folder_sel_cmp, [])
            ts_sel_cmp = st.selectbox(
                f"{tname} timestamp",
                ts_list_cmp,
                index=len(ts_list_cmp)-1 if ts_list_cmp else None,
                key=f"cmp_{tname}_ts",
            )
            selections[tname] = (folder_sel_cmp, ts_sel_cmp)

    # Load results for selected triples
    def load_results_for(tname: str):
        if tname not in selections:
            return []
        folder, ts = selections[tname]
        path = result_root / tname / folder / ts / "results.json"
        return load_json(path) or []

    kw_res = load_results_for("keyword")
    em_res = load_results_for("embedding")
    hy_res = load_results_for("hybrid")

    if kw_res and em_res and hy_res:
        # Build maps by correctId
        def map_by_id(lst):
            m = {}
            for it in lst:
                cid = str(it.get("correctId"))
                if cid:
                    m[cid] = it
            return m
        kw_map = map_by_id(kw_res)
        em_map = map_by_id(em_res)
        hy_map = map_by_id(hy_res)
        common_ids = sorted(set(kw_map.keys()) & set(em_map.keys()) & set(hy_map.keys()))

        # Compute correctness flags
        rows = []
        for cid in common_ids:
            k = kw_map[cid]
            e = em_map[cid]
            h = hy_map[cid]
            k_ok = 1.0 if float(k.get("coverage", 0.0)) == 1.0 else 0.0
            e_ok = 1.0 if float(e.get("coverage", 0.0)) == 1.0 else 0.0
            h_ok = 1.0 if float(h.get("coverage", 0.0)) == 1.0 else 0.0
            rows.append({
                "correctId": cid,
                "question": k.get("question"),
                "KW": k_ok,
                "EM": e_ok,
                "HY": h_ok,
                "KW_mrr": k.get("mrr"),
                "EM_mrr": e.get("mrr"),
                "HY_mrr": h.get("mrr"),
            })

        cmp_df = pd.DataFrame(rows)

        # Scenario filter
        st.subheader("Scenarios")
        scenario = st.selectbox(
            "choose scenario",
            [
                "KW✓, EM✗, HY✓",
                "KW✗, EM✓, HY✓",
                "KW✗, EM✗, HY✓",
                "KW✓, EM✓, HY✗",
                "All",
            ],
            index=0,
        )

        def apply_scenario(df: pd.DataFrame) -> pd.DataFrame:
            if scenario == "KW✓, EM✗, HY✓":
                return df[(df["KW"] == 1.0) & (df["EM"] == 0.0) & (df["HY"] == 1.0)]
            if scenario == "KW✗, EM✓, HY✓":
                return df[(df["KW"] == 0.0) & (df["EM"] == 1.0) & (df["HY"] == 1.0)]
            if scenario == "KW✗, EM✗, HY✓":
                return df[(df["KW"] == 0.0) & (df["EM"] == 0.0) & (df["HY"] == 1.0)]
            if scenario == "KW✓, EM✓, HY✗":
                return df[(df["KW"] == 1.0) & (df["EM"] == 1.0) & (df["HY"] == 0.0)]
            return df

        filtered = apply_scenario(cmp_df).copy()
        st.write(f"Matches: {len(filtered)} / {len(cmp_df)}")
        st.dataframe(filtered.head(200), width='stretch')

        # Pick a record to inspect in detail
        st.subheader("Inspect example")
        if not filtered.empty:
            sel_id = st.selectbox("correctId", filtered["correctId"].tolist(), key="cmp_pick_id")
            k_rec = kw_map.get(sel_id)
            e_rec = em_map.get(sel_id)
            h_rec = hy_map.get(sel_id)

            # Show main data details from the selected record (use keyword record as representative)
            st.markdown("---")
            st.subheader("Main data")
            base_rec = k_rec or e_rec or h_rec or {}
            mcols = st.columns(2)
            with mcols[0]:
                st.json({
                    "correctId": sel_id,
                    "title": base_rec.get("title"),
                    "question": base_rec.get("question"),
                    "splittedQuestion": base_rec.get("splittedQuestion"),
                    "paraphraseQuestion": base_rec.get("paraphraseQuestion"),
                })
            with mcols[1]:
                st.json({
                    "numFound": base_rec.get("numFound"),
                    "coverage": base_rec.get("coverage"),
                    "mrr": base_rec.get("mrr"),
                    "lrap": base_rec.get("lrap"),
                    "avgMRR_LRAP": base_rec.get("averageMrrAndLrap"),
                })

            show_n = st.number_input("hits per view", min_value=1, max_value=50, value=10, step=1, key="cmp_hits")
            cols3 = st.columns(3)
            for col, name, rec in zip(cols3, ["KW", "EM", "HY"], [k_rec, e_rec, h_rec]):
                with col:
                    st.markdown(f"**{name}**")
                    st.json({
                        "coverage": rec.get("coverage"),
                        "mrr": rec.get("mrr"),
                        "lrap": rec.get("lrap"),
                        "avgMRR_LRAP": rec.get("averageMrrAndLrap"),
                        "numFound": rec.get("numFound"),
                    })
                    sr_list = rec.get("searchResults", []) or []
                    for i, s in enumerate(sr_list[: int(show_n)]):
                        title = s.get("title")
                        score = s.get("score")
                        sid = s.get("id")
                        header = f"{i+1}. [score={score}] {title} (id={sid})"
                        with st.expander(header, expanded=False):
                            ctx = s.get("context")
                            if not isinstance(ctx, str):
                                ctx = str(ctx) if ctx is not None else ""
                            st.text_area("context", value=ctx, height=200, key=f"cmp_ctx_{name}_{sel_id}_{i}_{sid}")
        else:
            st.info("Select valid result sets for all three types to compare.")
    else:
        st.info("Select valid result sets for keyword, embedding, and hybrid to enable comparison.")


if __name__ == "__main__":
    main()
