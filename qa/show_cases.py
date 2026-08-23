import json
import sys


data = json.loads(open(sys.argv[1], encoding="utf-8").read())
for sheet in sys.argv[2:]:
    print(f"---{sheet}---")
    for item in data.get(sheet, [])[:35]:
        print(f"{item['row']}: {item['values']}")
