"""上传文件文本抽取。

BFF 只负责把原始字节落盘并透传，**文本抽取统一在 agent-service 完成**，
这样"能否解析某类文件"这件事只有一处真相，避免 Java / Python 两侧解析行为漂移。

支持：pdf（pypdf）、docx（zip + xml，零依赖）、md / txt（utf-8 -> gb18030 回落）。
不支持的扩展名一律抛 PayloadInvalid，并给出稳定错误码，禁止静默返回空文本——
空文本会让上游把"扫描件解析不出内容"误判成"简历一无可取"。
"""

from __future__ import annotations

import base64
import binascii
import io
import re
import zipfile

from .errors import PayloadInvalid

# 简历正文上限，超出仅保留前 N 字符（纯文本 3 万字已远超单页简历，防止提示词被灌爆）
MAX_TEXT_CHARS = 30_000

PDF_EXT = "pdf"
DOCX_EXT = "docx"
PLAIN_EXTS = ("txt", "md", "markdown")
DOC_EXT = "doc"

_TAG = re.compile(r"<[^>]+>")
_XML_SPACE = re.compile(r"[ \t]+")


def extract_text(file_name: str, data: bytes) -> str:
    """按扩展名把字节转成纯文本。"""
    if not data:
        raise PayloadInvalid("上传文件内容为空", code="EMPTY_CONTENT")
    ext = _ext_of(file_name)
    if ext == PDF_EXT:
        text = _from_pdf(data)
    elif ext == DOCX_EXT:
        text = _from_docx(data)
    elif ext in PLAIN_EXTS:
        text = _decode_text(data)
    elif ext == DOC_EXT:
        raise PayloadInvalid("暂不支持 .doc 旧格式，请另存为 .docx 或 PDF 后重试",
                             code="LEGACY_DOC_UNSUPPORTED")
    else:
        raise PayloadInvalid(f"暂不支持的文件类型：.{ext or '未知'}，请上传 PDF / Word / Markdown / 纯文本",
                             code="UNSUPPORTED_FILE_TYPE")

    text = _normalize(text)
    if not text:
        raise PayloadInvalid("未能从文件中提取到文本，若为扫描件请上传可复制文本的版本",
                             code="EMPTY_TEXT")
    return text


def decode_base64(raw: str) -> bytes:
    """解码 BFF 透传的 base64 文件体。"""
    payload = raw.strip()
    if payload.startswith("data:") and "," in payload:
        payload = payload.split(",", 1)[1]
    try:
        return base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise PayloadInvalid("文件内容不是合法的 base64 编码，请重新上传",
                             code="BAD_BASE64") from exc


# ---------------------------------------------------------------------------
# 各格式实现
# ---------------------------------------------------------------------------

def _from_pdf(data: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # pragma: no cover - 依赖缺失时给出可诊断提示
        raise PayloadInvalid("服务缺少 pypdf 依赖，无法解析 PDF", code="PDF_ENGINE_MISSING") from exc
    try:
        reader = PdfReader(io.BytesIO(data))
        pages = [(page.extract_text() or "") for page in reader.pages]
    except Exception as exc:  # 加密 / 损坏 / 非法结构统一归一
        raise PayloadInvalid("PDF 解析失败，文件可能已加密或损坏", code="PDF_PARSE_FAILED") from exc
    return "\n".join(pages)


def _from_docx(data: bytes) -> str:
    """docx 本体是 zip，正文在 word/document.xml，段落以 </w:p> 断行。"""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            xml = archive.read("word/document.xml").decode("utf-8", errors="ignore")
    except (zipfile.BadZipFile, KeyError) as exc:
        raise PayloadInvalid("Word 解析失败，文件可能已损坏或不是标准 docx",
                             code="DOCX_PARSE_FAILED") from exc

    xml = xml.replace("</w:p>", "\n").replace("<w:br/>", "\n").replace("<w:tab/>", " ")
    text = _TAG.sub("", xml)
    return _unescape(text)


def _decode_text(data: bytes) -> str:
    for encoding in ("utf-8", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="ignore")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def _ext_of(file_name: str) -> str:
    name = (file_name or "").strip().lower()
    if "." not in name:
        return ""
    return name.rsplit(".", 1)[1]


def _unescape(text: str) -> str:
    return (text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", '"').replace("&apos;", "'"))


def _normalize(text: str) -> str:
    """去掉控制字符、压平空白，保证摘要/技能词命中不被排版噪声干扰。"""
    cleaned = text.replace("\x00", "").replace("\r\n", "\n").replace("\r", "\n")
    lines = [_XML_SPACE.sub(" ", line).strip() for line in cleaned.split("\n")]
    kept = [line for line in lines if line]
    joined = "\n".join(kept)
    joined = re.sub(r"\n{3,}", "\n\n", joined)
    return joined[:MAX_TEXT_CHARS].strip()


__all__ = ["extract_text", "decode_base64", "MAX_TEXT_CHARS"]
