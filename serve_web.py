#!/usr/bin/env python3
"""极简 SPA 静态服务器（不依赖 Node / nginx）。

用途：部署时托管前端构建产物 ``web/dist``。命中静态文件就返回，未命中则回退 ``index.html``，
让 React Router 的深链（如 ``/admin/billing``、``/interview/123``）刷新时不会 404。

之所以不用 ``vite preview``：那需要服务器装 Node 与 node_modules；而部署机上本来就有 Python
（agent-service 依赖），用它托管可以把服务器的依赖压到最少。

用法::

    python3 serve_web.py <静态根目录> [端口]      # 默认 web/dist 3000
"""

from __future__ import annotations

import functools
import http.server
import os
import sys


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "web/dist"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 3000

    if not os.path.isdir(root):
        print(f"[serve_web] 静态目录不存在：{root}", file=sys.stderr)
        return 1

    class SpaHandler(http.server.SimpleHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            target = self.translate_path(self.path)
            # 未命中的路径（SPA 深链 / 前端路由）统一回退到 index.html
            if not os.path.exists(target):
                self.path = "/index.html"
            super().do_GET()

        def log_message(self, fmt: str, *args: object) -> None:
            return  # 静默，避免每请求一行刷屏

    handler = functools.partial(SpaHandler, directory=root)
    with http.server.ThreadingHTTPServer(("0.0.0.0", port), handler) as httpd:
        print(f"[serve_web] 已启动 http://0.0.0.0:{port} -> {root}", flush=True)
        httpd.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
