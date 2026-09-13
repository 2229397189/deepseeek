#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chiron 前后端联调自测：直打线上 121.199.22.206/api，逐接口验证
鉴权 / 请求响应结构 / 核心业务流程。stdlib only（urllib），无需第三方依赖。

用法：python selftest_frontback.py
输出：控制台摘要 + selftest_report.txt
"""
import json
import os
import random
import string
import time
import urllib.request
import urllib.error
import urllib.parse

# 直连服务器公网 IP，绕过本机可能存在的 127.0.0.1 代理
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

BASE = "http://121.199.22.206/api"
TIMEOUT = 30
SLOW = 150  # analyze / interview 调用真实 LLM，给足时间

results = []  # (name, http_status, code, ok, note)


def log(name, http_status, code, ok, note=""):
    results.append((name, http_status, code, ok, note))
    tag = "PASS" if ok else "FAIL"
    print(f"[{tag}] {name}  http={http_status} code={code}  {note}")


def req(method, path, token=None, json_body=None, file_tuple=None, params=None, timeout=TIMEOUT):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = None
    headers = {}
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if file_tuple is not None:
        fname, fcontent, ftype = file_tuple
        boundary = "----chironfst" + "".join(random.choice(string.digits) for _ in range(8))
        parts = []
        parts.append(("--" + boundary).encode())
        parts.append(f'Content-Disposition: form-data; name="file"; filename="{fname}"'.encode())
        parts.append(f"Content-Type: {ftype}".encode())
        parts.append(b"")
        parts.append(fcontent if isinstance(fcontent, bytes) else fcontent.encode("utf-8"))
        parts.append(("--" + boundary + "--").encode())
        parts.append(b"")
        data = b"\r\n".join(parts)
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    if token:
        headers["lq-token"] = token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        status = e.code
    except Exception as e:  # timeout / connection
        return None, None, f"EXC:{e}"
    # parse envelope
    code = None
    env = None
    try:
        env = json.loads(raw)
        if isinstance(env, dict) and "code" in env:
            code = env.get("code")
    except Exception:
        pass
    return status, code, env if env is not None else raw


def env_ok(status, code):
    return status == 200 and code == 0


def main():
    # ---------- A. 认证 ----------
    uname = "selftest_" + "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(8))
    pwd = "Selftest#123"
    st, code, env = req("POST", "/auth/register", json_body={
        "username": uname, "password": pwd, "email": uname + "@test.com", "nickname": "自检"})
    # 后端 register 返回 UserVO（无 token），前端注册后跳登录页再 login（设计如此）
    log("register", st, code, env_ok(st, code), f"user={uname} (返回UserVO无token，符合前端设计)")

    st, code, env = req("POST", "/auth/login", json_body={"username": uname, "password": pwd})
    tok = None
    if env_ok(st, code) and isinstance(env, dict):
        tok = (env.get("data") or {}).get("token")
    log("auth/login", st, code, env_ok(st, code) and bool(tok), f"token={'Y' if tok else 'N'}")

    st, code, env = req("GET", "/auth/me", token=tok)
    role = ((env or {}).get("data") or {}).get("role") if isinstance(env, dict) else None
    log("auth/me", st, code, env_ok(st, code) and role is not None, f"role={role}")

    # 未登录应 401001
    st, code, env = req("GET", "/decision/sessions")
    log("unauth->401", st, code, code == 401001, f"expect 401001 got {code}")

    if not tok:
        log("FATAL", 0, 0, False, "无 token，后续依赖登录的用例跳过")
        dump()
        return

    # ---------- B. 简历 ----------
    resume_txt = (
        "# 张三 后端开发工程师\n"
        "邮箱 zhang@test.com\n"
        "技能：Java / Spring Boot / Redis / MySQL / Kafka / 分布式\n"
        "经历：2021-2024 某电商 后端，负责秒杀系统（Lua 预减库存 + Redisson 分布式锁 + RocketMQ 不丢消息）\n"
        "项目：自研网盘（MinIO 分片上传 + 秒传 + FFmpeg 转码）\n"
    )
    st, code, env = req("POST", "/resume/upload", token=tok,
                        file_tuple=("resume_selftest.txt", resume_txt, "text/plain"))
    resume_id = None
    if env_ok(st, code) and isinstance(env, dict):
        resume_id = (env.get("data") or {}).get("assetId")
    log("resume/upload", st, code, env_ok(st, code) and resume_id is not None, f"assetId={resume_id}")

    st, code, env = req("GET", "/resume/list", token=tok)
    lst = ((env or {}).get("data") or []) if isinstance(env, dict) else []
    log("resume/list", st, code, env_ok(st, code) and any((a or {}).get("assetId") == resume_id for a in lst),
        f"count={len(lst)}")

    if resume_id:
        st, code, env = req("GET", f"/resume/{resume_id}", token=tok)
        log("resume/detail", st, code, env_ok(st, code), "")

    # ---------- C. 决策/JD 分析 ----------
    # 前端有「粘贴 JD 文本」走 POST /decision/jd/upload {text}；后端该端点只收 multipart file
    st, code, env = req("POST", "/decision/jd/upload", token=tok, json_body={"text": "招聘 Java 后端，要求 3 年经验，熟悉 SpringCloud/Redis/MySQL，有高并发经验优先。"})
    log("decision/jd/upload(TEXT前端路径)", st, code, env_ok(st, code),
        "后端仅收file，前端post{text} -> 若非0即为前后端不匹配")

    jd_txt = ("岗位：Java 后端开发。职责：高并发交易系统设计与开发。要求：本科及以上，"
              "熟悉 JVM/并发/Redis/MySQL/消息队列，3 年以上经验，有秒杀/电商经验优先。")
    st, code, env = req("POST", "/decision/analyze", token=tok, json_body={
        "resumeAssetId": resume_id, "jdText": jd_txt, "jobTitle": "Java后端开发", "title": "自检分析"}, timeout=SLOW)
    session_id = None
    score = None
    status_v = None
    if env_ok(st, code) and isinstance(env, dict):
        d = env.get("data") or {}
        session_id = d.get("sessionId")
        score = d.get("latestScore")
        status_v = d.get("status")
    log("decision/analyze(核心bug修复验证)", st, code,
        env_ok(st, code) and session_id is not None and score is not None,
        f"session={session_id} score={score} status={status_v}")

    st, code, env = req("GET", "/decision/sessions", token=tok)
    log("decision/sessions", st, code, env_ok(st, code), "")

    if session_id:
        st, code, env = req("GET", f"/decision/sessions/{session_id}", token=tok)
        log("decision/session/detail", st, code, env_ok(st, code), "")
        st, code, env = req("POST", f"/decision/sessions/{session_id}/ask", token=tok,
                            json_body={"question": "这个岗位对并发能力要求高吗？"}, timeout=SLOW)
        log("decision/ask", st, code, env_ok(st, code), "")

    # ---------- D. 模拟面试 ----------
    st, code, env = req("POST", "/interview/start", token=tok, json_body={
        "resumeAssetId": resume_id, "jobTitle": "Java后端"}, timeout=SLOW)
    iv_id = None
    if env_ok(st, code) and isinstance(env, dict):
        iv_id = (env.get("data") or {}).get("sessionId")
    log("interview/start", st, code, env_ok(st, code) and iv_id is not None, f"session={iv_id}")

    if iv_id:
        st, code, env = req("POST", f"/interview/sessions/{iv_id}/answer", token=tok,
                            json_body={"content": "我在上家用 Redis 做缓存和分布式锁，用 Redisson 实现可重入锁，秒杀场景用 Lua 预减库存。"}, timeout=SLOW)
        ai_msg = (((env or {}).get("data") or {}).get("aiMessage") or {}).get("content") if isinstance(env, dict) else None
        log("interview/answer", st, code, env_ok(st, code) and bool(ai_msg), f"aiMsgLen={len(ai_msg or '')}")

        st, code, env = req("POST", f"/interview/sessions/{iv_id}/next", token=tok, timeout=SLOW)
        log("interview/next", st, code, env_ok(st, code), "")

        st, code, env = req("POST", f"/interview/sessions/{iv_id}/finish", token=tok, timeout=SLOW)
        iv_score = ((env or {}).get("data") or {}).get("score") if isinstance(env, dict) else None
        log("interview/finish", st, code, env_ok(st, code) and iv_score is not None, f"score={iv_score}")

        st, code, env = req("GET", f"/interview/sessions/{iv_id}/report", token=tok)
        log("interview/report", st, code, env_ok(st, code), "")

    st, code, env = req("GET", "/interview/sessions", token=tok)
    log("interview/sessions", st, code, env_ok(st, code), "")

    # ---------- E. 能力/图谱/知识库/模型/网关/工作台 ----------
    st, code, env = req("GET", "/profile/tags", token=tok)
    log("profile/tags", st, code, env_ok(st, code), "")
    st, code, env = req("GET", "/profile/memories", token=tok)
    log("profile/memories", st, code, env_ok(st, code), "")

    st, code, env = req("GET", "/graph/nodes", token=tok)
    nodes = ((env or {}).get("data") or []) if isinstance(env, dict) else []
    log("graph/nodes", st, code, env_ok(st, code), f"n={len(nodes)}")
    first_node = (nodes[0] or {}).get("id") if nodes else None
    st, code, env = req("GET", "/graph/edges", token=tok)
    edges = ((env or {}).get("data") or []) if isinstance(env, dict) else []
    log("graph/edges", st, code, env_ok(st, code), f"n={len(edges)}")
    if first_node:
        st, code, env = req("GET", f"/graph/nodes/{first_node}/evidence", token=tok)
        log("graph/evidence", st, code, env_ok(st, code), f"node={first_node}")

    st, code, env = req("GET", "/kb/documents", token=tok)
    log("kb/documents", st, code, env_ok(st, code), "")
    st, code, env = req("GET", "/kb/search", token=tok, params={"q": "Redis", "topK": 5})
    log("kb/search", st, code, env_ok(st, code), "")
    st, code, env = req("POST", "/kb/reindex", token=tok, timeout=SLOW)
    log("kb/reindex", st, code, env_ok(st, code), "")

    st, code, env = req("GET", "/models/available", token=tok)
    log("models/available", st, code, env_ok(st, code), "")

    st, code, env = req("GET", "/gateway/metrics", token=tok)
    log("gateway/metrics", st, code, env_ok(st, code), "")
    st, code, env = req("GET", "/gateway/runs", token=tok, params={"page": 1, "size": 10})
    log("gateway/runs", st, code, env_ok(st, code), "")

    st, code, env = req("POST", "/workspace/intent", token=tok, json_body={"text": "我想评估字节后端岗的匹配度"})
    log("workspace/intent", st, code, env_ok(st, code), "")

    # ---------- F. Agent SSE 流式 ----------
    try:
        import http.client
        body = json.dumps({"bizType": "DECIDE", "bizId": 1, "stage": "ANALYZE",
                            "specHash": "selftest", "payload": {"text": "你好"}}).encode()
        conn = http.client.HTTPConnection("121.199.22.206", 80, timeout=40)
        conn.request("POST", "/api/agent/stream", body=body,
                     headers={"Content-Type": "application/json", "lq-token": tok})
        resp = conn.getresponse()
        chunk = resp.read(4096).decode("utf-8", "replace")
        conn.close()
        ok = resp.status == 200 and ("data:" in chunk or "event:" in chunk or len(chunk) > 0)
        log("agent/stream(SSE)", resp.status, None, ok, f"bytes={len(chunk)} head={chunk[:60]!r}")
    except Exception as e:
        log("agent/stream(SSE)", None, None, False, f"EXC:{e}")

    # ---------- G. 安全/越权 ----------
    st, code, env = req("GET", "/admin/models", token=tok)
    log("admin/models(普通用户应403)", st, code, code == 403001, f"expect 403001 got {code}")
    st, code, env = req("POST", "/billing/recharge", token=tok, json_body={"amount": 100})
    log("billing/recharge(应404删除)", st, code, st == 404, f"expect 404 got {st}")

    # ---------- 清理：归档测试会话，减少污染 ----------
    if session_id:
        req("DELETE", f"/decision/sessions/{session_id}", token=tok)
    if iv_id:
        req("DELETE", f"/interview/sessions/{iv_id}", token=tok)

    dump()


def dump():
    os.makedirs(os.path.dirname(os.path.abspath(__file__)) or ".", exist_ok=True)
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "selftest_report.txt"), "w", encoding="utf-8") as f:
        f.write("Chiron 前后端联调自测报告\n")
        f.write("时间: " + time.strftime("%Y-%m-%d %H:%M:%S") + "\n")
        f.write("目标: " + BASE + "\n\n")
        pas = sum(1 for r in results if r[3])
        f.write(f"总计 {len(results)} 项，通过 {pas}，失败 {len(results)-pas}\n\n")
        for name, st, code, ok, note in results:
            f.write(f"[{'PASS' if ok else 'FAIL'}] {name}  http={st} code={code}  {note}\n")
    print("\n==== 汇总 ====")
    pas = sum(1 for r in results if r[3])
    print(f"总计 {len(results)} 项，通过 {pas}，失败 {len(results)-pas}")
    print("报告已写入 selftest_report.txt")


if __name__ == "__main__":
    main()
