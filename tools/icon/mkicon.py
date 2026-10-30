"""
App 圖示：照 Claude Design 畫布的 A1「霓虹麥克風」產生 Android 自適應圖示的向量檔（108dp）——
背景（深色＋紫光）、前景（外圈 40 根頻譜＋置中的麥克風）、主題圖示單色層。不放文字，所有語言共用。
只用標準函式庫。

用法（repo 根目錄）：
  docker run --rm -v "$PWD/tools/icon:/gen" -v "$PWD/android/app/src/main/res:/out" --entrypoint python anybacking:latest /gen/mkicon.py
（git-bash 在前面加 MSYS_NO_PATHCONV=1、$PWD 改 $(pwd -W)）
"""
import math
import os

OUT = "/out"
GRAD = [(0, "#FFFF4FD8"), (0.55, "#FF8B5CFF"), (1, "#FF3DF5C8")]
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
MIC_DY = 4.5   # 麥克風往下移，讓整支（y 31～68）上下置中在頻譜圈（圓心 54）
MIC_HEAD = "M47,38a7,7 0,0 1,14 0v10a7,7 0,0 1,-14 0z"
MIC_STAND = "M41,46a13,13 0,0 0,26 0M54,59V68M47,68H61"
MIC_GRILLE = "M50,38H58M50,42H58M50,46H58"


def bars_path():
    d = []
    for i in range(40):
        a = math.radians(i * 9 - 90)
        ln = 2.2 + 5.3 * abs(math.sin(i * 1.71) * math.cos(i * 0.53))
        p = lambda r: (54 + r * math.cos(a), 54 + r * math.sin(a))
        (x1, y1), (x2, y2) = p(24), p(24 + ln)
        d.append(f"M{x1:.2f},{y1:.2f}L{x2:.2f},{y2:.2f}")
    return "".join(d)


def grad(tag, kind="linear", stops=GRAD, **kw):
    attrs = " ".join(f'android:{k}="{v}"' for k, v in kw.items())
    items = "".join(f'\n                <item android:offset="{o}" android:color="{c}" />' for o, c in stops)
    return (f'        <aapt:attr name="android:{tag}">\n            <gradient android:type="{kind}" {attrs}>{items}\n'
            f'            </gradient>\n        </aapt:attr>\n')


def vector(body):
    return (HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            + body + '</vector>\n')


def mic(color: str, glow: bool):
    s = f'    <group android:translateY="{MIC_DY}">\n'
    if glow:
        s += (f'        <path android:pathData="{MIC_HEAD}{MIC_STAND}" android:strokeColor="#FFFF4FD8" android:strokeWidth="5"\n'
              '            android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeAlpha="0.4" />\n')
    s += (f'        <path android:pathData="{MIC_HEAD}" android:fillColor="{color}" />\n'
          f'        <path android:pathData="{MIC_STAND}" android:strokeColor="{color}" android:strokeWidth="2.4"\n'
          '            android:strokeLineCap="round" android:strokeLineJoin="round" />\n')
    if glow:
        s += (f'        <path android:pathData="{MIC_GRILLE}" android:strokeColor="#FFB57BFF" android:strokeWidth="1"\n'
              '            android:strokeLineCap="round" android:strokeAlpha="0.7" />\n')
    return s + '    </group>\n'


def fg():
    bars, lin = bars_path(), dict(startX=24, startY=24, endX=84, endY=84)
    return vector(
        '    <!-- 外圈 40 根頻譜（霓虹粉→紫→青，同播放鍵的環狀頻譜）＋置中的麥克風；光暈用加粗半透明的同一條線（向量圖沒有模糊） -->\n'
        f'    <path android:pathData="{bars}" android:strokeWidth="3.4" android:strokeLineCap="round" android:strokeAlpha="0.28">\n'
        + grad("strokeColor", **lin) + '    </path>\n'
        f'    <path android:pathData="{bars}" android:strokeWidth="1.6" android:strokeLineCap="round">\n'
        + grad("strokeColor", **lin) + '    </path>\n'
        + mic("#FFFFF1FC", glow=True))


def mono():
    return vector(
        '    <!-- 主題圖示（系統只取形狀、自己上色） -->\n'
        f'    <path android:pathData="{bars_path()}" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round" />\n'
        + mic("#FFFFFFFF", glow=False))


bg = vector(
    '    <!-- 深色底＋中間偏上的紫色光（同 App 的 Palette.bg） -->\n'
    '    <path android:pathData="M0,0h108v108h-108z">\n'
    + grad("fillColor", "radial", [(0, "#FF3B1257"), (0.55, "#FF160A24"), (1, "#FF08070C")], centerX=54, centerY=50, gradientRadius=62)
    + '    </path>\n')

for name, s in {"ic_launcher_bg.xml": bg, "ic_launcher_fg.xml": fg(), "ic_launcher_mono.xml": mono()}.items():
    with open(f"{OUT}/drawable/{name}", "w", encoding="utf-8") as f:
        f.write(s)
    print(name, len(s))
