"""文本抽取单测：覆盖 pdf 缺依赖 / docx 手工构造 / 编码回落 / 非法输入四类边界。"""

from __future__ import annotations

import base64
import io
import zipfile

import pytest

from app.core.errors import PayloadInvalid
from app.core.extract import decode_base64, extract_text

_DOCX_XML = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
    "<w:body>"
    "<w:p><w:r><w:t>张三</w:t></w:r></w:p>"
    "<w:p><w:r><w:t>5 年 Java &amp; 后端经验</w:t></w:r></w:p>"
    "</w:body></w:document>"
)


def _docx_bytes() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("word/document.xml", _DOCX_XML)
    return buffer.getvalue()


def test_extract_plain_text_and_markdown():
    assert extract_text("resume.txt", "张三\nJava 5 年".encode("utf-8")) == "张三\nJava 5 年"
    assert "Redis" in extract_text("resume.md", "# 技能\nRedis".encode("utf-8"))


def test_extract_gbk_fallback():
    text = extract_text("resume.txt", "张三\n后端开发".encode("gb18030"))
    assert text == "张三\n后端开发"


def test_extract_docx_keeps_paragraph_and_unescapes():
    text = extract_text("resume.docx", _docx_bytes())
    assert text.splitlines() == ["张三", "5 年 Java & 后端经验"]


def test_extract_rejects_unsupported_and_legacy_doc():
    with pytest.raises(PayloadInvalid) as exe:
        extract_text("resume.exe", b"binary")
    assert exe.value.code == "UNSUPPORTED_FILE_TYPE"

    with pytest.raises(PayloadInvalid) as exe:
        extract_text("resume.doc", b"binary")
    assert exe.value.code == "LEGACY_DOC_UNSUPPORTED"


def test_extract_rejects_empty_content():
    with pytest.raises(PayloadInvalid) as exe:
        extract_text("resume.txt", b"")
    assert exe.value.code == "EMPTY_CONTENT"


def test_extract_pdf_without_engine_reports_clear_error():
    """不注入 pypdf 桩：真依赖存在时应能解析失败归一，缺依赖时给可诊断错误码。"""
    with pytest.raises(PayloadInvalid) as exe:
        extract_text("resume.pdf", b"not a real pdf")
    assert exe.value.code in {"PDF_PARSE_FAILED", "PDF_ENGINE_MISSING"}


def test_decode_base64_accepts_data_url_and_rejects_garbage():
    raw = base64.b64encode(b"hello").decode()
    assert decode_base64(f"data:application/pdf;base64,{raw}") == b"hello"
    assert decode_base64(raw) == b"hello"

    with pytest.raises(PayloadInvalid) as exe:
        decode_base64("!!!not-base64!!!")
    assert exe.value.code == "BAD_BASE64"
