import collections
import json
import sys


data = json.loads(open(sys.argv[1], encoding="utf-8").read())
target = sys.argv[2:]
for sheet in target:
    rows = data.get(sheet, [])
    if not rows:
        continue
    headers = rows[0]["values"]
    idx = {h: i for i, h in enumerate(headers) if h}
    def val(item, key):
        i = idx.get(key)
        return item["values"][i] if i is not None and i < len(item["values"]) else ""
    cats = collections.Counter(val(x, "功能分类") for x in rows[1:])
    types = collections.Counter(val(x, "用例类型") for x in rows[1:])
    priorities = collections.Counter(val(x, "优先级") for x in rows[1:])
    print(json.dumps({"sheet": sheet, "count": len(rows) - 1,
                      "categories": cats, "types": types, "priorities": priorities}, ensure_ascii=False, default=dict))

print("---keyword matches across all sheets---")
keywords = ["AI问答", "问答", "文件检索", "检索", "权限管理", "用户管理", "角色", "权限", "登录"]
for kw in keywords:
    matches = []
    for sheet, rows in data.items():
        for item in rows[1:]:
            joined = " ".join(str(v or "") for v in item["values"])
            if kw in joined:
                matches.append((sheet, item["row"], item["values"][0], item["values"][1], item["values"][2]))
    print(json.dumps({"keyword": kw, "count": len(matches), "sample": matches[:8]}, ensure_ascii=False))
