Noto Sans SC test subset

This directory contains a glyph subset of NotoSansSC-VF.ttf for Task14 PDF
contract tests only. Production code does not load this classpath resource.

Font: Noto Sans SC
Upstream project: https://github.com/notofonts/noto-cjk
Original font SHA-256: 763146584CF0710223441356B4395E279021B0806C196614377A7A0174AE074A
Subset tool: fontTools 4.63.0, python -m fontTools.subset
Subset size: 316256 bytes
Subset SHA-256: B9AF0C4CB362CD9BCB6A4DF055B5C9E0B275F74E8A424D3E9D4EB40E5CA5871F
License: SIL Open Font License 1.1; see OFL-1.1.txt

The subset keeps ASCII and the Chinese glyphs used by the Task14 renderer
contract. CI may override it with -Dtest.pdf.font-path=<font-file>.
