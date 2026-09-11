/**
 * 极简、安全的 Markdown -> HTML 渲染器（先转义再解析，避免 XSS）。
 * 仅覆盖简历常用语法：标题 / 粗体 / 斜体 / 行内代码 / 有序·无序列表 / 分割线 / 段落。
 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function inline(s: string): string {
  return s
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
}

export function renderMarkdown(md: string): string {
  const lines = escapeHtml(md ?? '').split('\n');
  const html: string[] = [];
  let listType: 'ul' | 'ol' | null = null;
  const closeList = () => {
    if (listType) {
      html.push(`</${listType}>`);
      listType = null;
    }
  };

  const isHeading = (l: string) => /^(#{1,3})\s+/.test(l);
  const isUl = (l: string) => /^\s*[-*]\s+/.test(l);
  const isOl = (l: string) => /^\s*\d+\.\s+/.test(l);
  const isHr = (l: string) => /^(-{3,}|\*{3,})$/.test(l.trim());

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === '') {
      closeList();
      i += 1;
      continue;
    }
    const h = line.match(/^(#{1,3})\s+(.*)$/);
    if (h) {
      closeList();
      const lvl = h[1].length;
      html.push(`<h${lvl}>${inline(h[2])}</h${lvl}>`);
      i += 1;
      continue;
    }
    if (isHr(line)) {
      closeList();
      html.push('<hr/>');
      i += 1;
      continue;
    }
    const ul = line.match(/^\s*[-*]\s+(.*)$/);
    if (ul) {
      if (listType !== 'ul') {
        closeList();
        html.push('<ul>');
        listType = 'ul';
      }
      html.push(`<li>${inline(ul[1])}</li>`);
      i += 1;
      continue;
    }
    const ol = line.match(/^\s*\d+\.\s+(.*)$/);
    if (ol) {
      if (listType !== 'ol') {
        closeList();
        html.push('<ol>');
        listType = 'ol';
      }
      html.push(`<li>${inline(ol[1])}</li>`);
      i += 1;
      continue;
    }
    closeList();
    const para: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !isHeading(lines[i]) &&
      !isUl(lines[i]) &&
      !isOl(lines[i]) &&
      !isHr(lines[i])
    ) {
      para.push(inline(lines[i]));
      i += 1;
    }
    html.push(`<p>${para.join('<br/>')}</p>`);
  }
  closeList();
  return html.join('\n');
}
