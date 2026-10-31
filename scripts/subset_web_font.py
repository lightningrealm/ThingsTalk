"""Rebuild the Web-only Chinese font from the upstream Noto Sans SC variable TTF.

Usage: python -m pip install fonttools
       python scripts/subset_web_font.py /path/to/NotoSansSC.ttf
The font source and license are documented beside the output resource.
"""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


root = Path(__file__).resolve().parents[1]
font = TTFont(sys.argv[1])
instantiateVariableFont(font, {"wght": 400}, inplace=True)
characters = {chr(codepoint) for codepoint in range(0x20, 0x300)}
for lead in range(0xA1, 0xF8):
    for trail in range(0xA1, 0xFF):
        try:
            characters.update(bytes((lead, trail)).decode("gb2312"))
        except UnicodeDecodeError:
            pass
for source in (root / "shared/src").rglob("*.kt"):
    characters.update(source.read_text())

options = subset.Options()
options.name_IDs = ["*"]
options.name_legacy = True
options.name_languages = ["*"]
subsetter = subset.Subsetter(options=options)
subsetter.populate(text="".join(sorted(characters)))
subsetter.subset(font)
for record in font["name"].names:
    if record.nameID in (1, 4, 6):
        name = "ThingsTalkSansSC-Regular" if record.nameID == 6 else "ThingsTalk Sans SC Regular"
        record.string = name.encode(record.getEncoding())
output = root / "shared/src/wasmJsMain/resources/fonts/ThingsTalkSansSC-Regular.ttf"
font.save(output)
print(f"Saved {output} ({output.stat().st_size:,} bytes)")
