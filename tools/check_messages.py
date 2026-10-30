"""
檢查伺服器錯誤代碼（app/messages.py）和 App 字串（014）一致：
- 每個代碼在 values/strings.xml（英文）與 values-zh/strings.xml（中文）都有 err_<代碼>；values-b+zh+Hant 和 values-zh 內容相同
- App 沒有多出伺服器沒有的 err_*
- 中文字串和伺服器的中文模板相同（{n} 這類參數換成 %1$d…，順序照 App ErrorText.kt 的 ERR_PARAMS）
用法（本機沒有 Python，用映像跑）：
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)":/w -w /w --entrypoint python anybacking:latest tools/check_messages.py
"""
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, "app")
from messages import MESSAGES  # noqa: E402

RES = "android/app/src/main/res"
KT = "android/app/src/main/java/com/anybacking/app/ui/ErrorText.kt"


def strings(path):
    return {e.get("name"): (e.text or "").replace("\'", "'") for e in ET.parse(path).getroot().iter("string")}


en, zh = strings(f"{RES}/values/strings.xml"), strings(f"{RES}/values-zh/strings.xml")
hant = strings(f"{RES}/values-b+zh+Hant/strings.xml")
order = {m.group(1): re.findall(r'"(\w+)"', m.group(2))
         for m in re.finditer(r'"(\w+)" to listOf\(([^)]*)\)', open(KT, encoding="utf-8").read())}
bad = []
for code, tpl in MESSAGES.items():
    key = f"err_{code}"
    for name, d in (("values", en), ("values-zh", zh)):
        if key not in d:
            bad.append(f"{name} 缺 {key}")
    params = re.findall(r"\{(\w+)\}", tpl)
    if sorted(params) != sorted(order.get(code, [])):
        bad.append(f"ErrorText.kt 的 ERR_PARAMS[{code}] 應該是 {params}")
    want = tpl
    for i, p in enumerate(order.get(code, params), 1):
        want = want.replace("{" + p + "}", f"%{i}$d")
    if key in zh and zh[key] != want:
        bad.append(f"values-zh {key} 和伺服器不同：{zh[key]!r} ≠ {want!r}")
for name, d in (("values", en), ("values-zh", zh)):
    bad += [f"{name} 多了 {k}（伺服器沒有這個代碼）" for k in d if k.startswith("err_") and k[4:] not in MESSAGES]
missing = sorted(set(en) ^ set(zh))
bad += [f"中英文字串不成對：{k}" for k in missing]
if hant != zh:
    bad.append("values-b+zh+Hant 和 values-zh 內容不同（兩份要一樣）")
print("\n".join(bad) or f"OK：{len(MESSAGES)} 個錯誤代碼、{len(en)} 段字串")
sys.exit(1 if bad else 0)
