# -*- coding: utf-8 -*-
"""
冷冻海产品溯源系统 —— 全流程一键演示脚本
在本地后端(8080)已启动的前提下，模拟完整业务链（跨组织交接 + 组织内工艺）：

  捕捞舰(ORG1)                加工厂(ORG2)              分装批发(ORG3)               超市(ORG4)
  capt_ship 建SOURCE批次B1    接收B1 -> PROCESS建B2     接收B2 -> REPACK建B3         接收B3
  提交+捕捞事件                打包事件                   分装事件                      到货事件+激活溯源码
  ──交接B1──►                ──交接B2──►                ──交接B3──►                消费者扫码

组织隔离规则：batch-operation(工艺) 仅限本组织批次；跨组织必须走 transfer(交接)，接收后批次归属转移。
使用标准库 urllib。密码统一 demo1234。
"""
import json
import sys
import secrets
import urllib.request
import urllib.error
import http.cookiejar

BASE = "http://localhost:8080"
ORG = {1: "蓝海远洋捕捞舰队", 2: "深海鲜冻加工厂", 3: "南海冷链分装批发中心", 4: "鲜活优选连锁超市"}


class Api:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def csrf(self):
        r = self.j("GET", "/api/v1/auth/csrf")
        return (r.get("data", r) or {}).get("token")

    def login(self, user):
        tok = self.csrf()
        self.j("POST", "/api/v1/auth/login",
               {"username": user, "password": "demo1234"}, csrf=tok)

    def j(self, method, path, body=None, csrf=None):
        url = BASE + path
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if csrf:
            headers["X-CSRF-TOKEN"] = csrf
            if method in ("POST", "PATCH", "DELETE"):
                headers["Idempotency-Key"] = secrets.token_hex(24)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=30) as resp:
                raw = resp.read().decode("utf-8")
                try:
                    return json.loads(raw)
                except Exception:
                    return {"raw": raw}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return {"__http_error__": e.code, **json.loads(raw)}
            except Exception:
                return {"__http_error__": e.code, "raw": raw}
        except Exception as e:
            return {"__transport_error__": str(e)}

    def need(self, r, label):
        if "__http_error__" in r or "__transport_error__" in r:
            print(f"  [失败] {label}: {json.dumps(r, ensure_ascii=False)}")
            sys.exit(1)
        return r


def pick(news, *keys):
    d = news.get("data", news) if isinstance(news, dict) else {}
    for k in keys:
        if k in d:
            return d[k]
    return None


api = Api()

print("== 0. 连通性检查 ==")
r = api.j("GET", "/api/v1/auth/csrf")
if "__http_error__" in r or "__transport_error__" in r:
    print("后端不可达，请确认 Spring Boot 已在 8080 启动。", r)
    sys.exit(1)
print(f"  后端在线，CSRF={api.csrf()[:8]}...")

print("\n== 1. 获取产品清单（取第一个可用产品） ==")
api.login("capt_ship")
prods = api.need(api.j("GET", "/api/v1/products"), "查产品")
plist = prods.get("data", prods)
if isinstance(plist, dict):
    plist = plist.get("items", plist.get("list", []))
pid = plist[0]["id"] if isinstance(plist, list) and plist else 1
pname = plist[0].get("publicName") if isinstance(plist, list) and plist else str(pid)
print(f"  采用产品: {pname} (id={pid})")


def mk_batch(typ, qty, origin_text):
    body = {
        "batchNo": f"DEMO{typ}-{qty}kg-0921-{secrets.token_hex(2)}",
        "productId": pid,
        "batchType": typ,
        "quantity": qty,
        "unitCode": "kg",
        "originType": "DOMESTIC_CAPTURE",
        "originText": origin_text,
        "productionDate": "2026-09-21",
        "captureDate": "2026-09-21",
        "freezeDate": "2026-09-21",
        "shelfLifeDays": 365,
    }
    tok = api.csrf()
    return api.need(api.j("POST", "/api/v1/batches", body, csrf=tok), f"创建{typ}批次")


def submit_batch(bid):
    tok = api.csrf()
    cur = api.need(api.j("GET", f"/api/v1/batches/{bid}", csrf="none"), "查批次版本")
    v = (cur.get("data", cur) or {}).get("version", 0)
    return api.need(api.j("POST", f"/api/v1/batches/{bid}/submit", {"version": v}, csrf=tok), "提交批次")


def add_event(bid, etype, summary):
    tok = api.csrf()
    return api.need(api.j("POST", f"/api/v1/batches/{bid}/events",
                          {"eventType": etype, "occurredAt": "2026-09-21T10:00:00+08:00",
                           "dataSource": "MANUAL", "summary": summary}, csrf=tok), f"事件{etype}")


def create_transfer(bid, receiver_org):
    tok = api.csrf()
    return api.need(api.j("POST", "/api/v1/transfers",
                          {"batchId": bid, "receiverOrgId": receiver_org}, csrf=tok), "创建交接")


def submit_transfer(tid):
    tok = api.csrf()
    cur = api.need(api.j("GET", f"/api/v1/transfers/{tid}", csrf="none"), "查交接版本")
    v = (cur.get("data", cur) or {}).get("version", 0)
    return api.need(api.j("POST", f"/api/v1/transfers/{tid}/submit",
                          {"shippedAt": "2026-09-21T10:00:00+08:00", "expectedVersion": v}, csrf=tok), "提交发货")


def accept_transfer(tid, qty):
    tok = api.csrf()
    cur = api.need(api.j("GET", f"/api/v1/transfers/{tid}"), "查交接版本")
    v = (cur.get("data", cur) or {}).get("version", 0)
    return api.need(api.j("POST", f"/api/v1/transfers/{tid}/accept",
                          {"receivedQuantity": qty, "unitCode": "kg",
                           "occurredAt": "2026-09-21T10:00:00+08:00",
                           "differenceReason": "", "expectedVersion": v}, csrf=tok), "接收交接")


def activate_code(bid):
    tok = api.csrf()
    act = api.need(api.j("POST", f"/api/v1/batches/{bid}/public-trace-code/activate", {}, csrf=tok), "激活溯源码")
    return (act.get("data", act) or {}).get("publicId")


print("\n== 2. 环节A · 捕捞舰(ORG1) 建原料批次并交接给加工厂 ==")
api.login("capt_ship")
b1 = mk_batch("SOURCE", 500, "浙江舟山近海拖网")
b1_id = pick(b1, "id", "batchId")
print(f"  原料批次 B1={b1_id} (org1)")
submit_batch(b1_id)
print("  B1 已提交(ACTIVE)")
add_event(b1_id, "SOURCE", "拖网捕捞作业完成，分级筛选")
add_event(b1_id, "FREEZE", "入舱-18℃碎冰锁鲜")
print("  B1 记录捕捞/冷冻事件")
tr1 = create_transfer(b1_id, 2)
tr1_id = pick(tr1, "id", "transferId")
submit_transfer(tr1_id)
print(f"  交接 {tr1_id} B1: org1 -> org2 发货完成")

print("\n== 3. 环节B · 加工厂(ORG2) 接收、PROCESS加工打包、交接给批发 ==")
api.login("plant_op")
accept_transfer(tr1_id, 500)
print("  加工厂已接收 B1 (归属转移到org2)")
b2 = mk_batch("PROCESSING", 490, "深海鲜冻加工厂速冻")
b2_id = pick(b2, "id", "batchId")
print(f"  加工批次 B2={b2_id}")
submit_batch(b2_id)
tok = api.csrf()
op1 = api.need(api.j("POST", "/api/v1/batch-operations",
                     {"operationType": "PROCESS", "occurredAt": "2026-09-21T10:00:00+08:00",
                      "note": "清洗-去壳-速冻-对虾打包",
                      "items": [
                          {"role": "INPUT", "batchId": b1_id, "quantity": 500, "unitCode": "kg"},
                          {"role": "OUTPUT", "batchId": b2_id, "quantity": 490, "unitCode": "kg"},
                          {"role": "LOSS", "quantity": 10, "unitCode": "kg"}]}, csrf=tok), "创建PROCESS")
op1_id = pick(op1, "id", "operationId")
tok = api.csrf()
api.need(api.j("POST", f"/api/v1/batch-operations/{op1_id}/submit", {"version": 0}, csrf=tok), "提交PROCESS")
print("  PROCESS 已提交 -> 谱系边 B1->B2")
add_event(b2_id, "PACK", "对虾速冻打包，转入-18℃冷库待发")
tr2 = create_transfer(b2_id, 3)
tr2_id = pick(tr2, "id", "transferId")
submit_transfer(tr2_id)
print(f"  交接 {tr2_id} B2: org2 -> org3 发货完成")

print("\n== 4. 环节C · 分装批发(ORG3) 接收、REPACK分装、交接给超市 ==")
api.login("whl_op")
accept_transfer(tr2_id, 490)
print("  批发中心已接收 B2 (归属转移到org3)")
b3 = mk_batch("DISTRIBUTION", 480, "南海冷链分装批发")
b3_id = pick(b3, "id", "batchId")
print(f"  分装批次 B3={b3_id}")
submit_batch(b3_id)
tok = api.csrf()
op2 = api.need(api.j("POST", "/api/v1/batch-operations",
                     {"operationType": "REPACK", "occurredAt": "2026-09-21T10:00:00+08:00",
                      "note": "家庭装分装为500g×960盒",
                      "items": [
                          {"role": "INPUT", "batchId": b2_id, "quantity": 490, "unitCode": "kg"},
                          {"role": "OUTPUT", "batchId": b3_id, "quantity": 480, "unitCode": "kg"},
                          {"role": "LOSS", "quantity": 10, "unitCode": "kg"}]}, csrf=tok), "创建REPACK")
op2_id = pick(op2, "id", "operationId")
tok = api.csrf()
api.need(api.j("POST", f"/api/v1/batch-operations/{op2_id}/submit", {"version": 0}, csrf=tok), "提交REPACK")
print("  REPACK 已提交 -> 谱系边 B2->B3")
add_event(b3_id, "WAREHOUSE_OUT", "分装完成出库，装冷链车发往超市")
tr3 = create_transfer(b3_id, 4)
tr3_id = pick(tr3, "id", "transferId")
submit_transfer(tr3_id)
print(f"  交接 {tr3_id} B3: org3 -> org4 发货完成")

print("\n== 5. 环节D · 超市(ORG4) 接收、到货上架、激活溯源码 ==")
api.login("market_op")
accept_transfer(tr3_id, 480)
print("  超市已接收 B3 (归属转移到org4)")
add_event(b3_id, "ARRIVAL", "冷链车到店，核温-18℃±1℃，上柜")
print("  B3 记录到货上架事件")
pub_id = activate_code(b3_id)
if not pub_id:
    api.need({"__http_error__": "-1", "detail": "未能解析 publicId"}, "激活溯源码")
print(f"  已激活公开追溯码 publicId = {pub_id}")

print("\n== 6. 环节E · 消费者扫码溯源（公开接口，无需登录） ==")
public_api = Api()
res = public_api.need(public_api.j("GET", f"/api/public/v1/public/traces/{pub_id}"), "消费者查询")
td = res.get("data", res)
evs = td.get("events", []) if isinstance(td, dict) else []
print("  消费者查询成功！追溯摘要:")
print("  ", json.dumps({
    "product": (td.get("product") or {}).get("name") if isinstance(td, dict) else None,
    "publicBatchNo": (td.get("batch") or {}).get("publicBatchNo") if isinstance(td, dict) else None,
    "batchStatus": td.get("batchStatus") if isinstance(td, dict) else None,
    "tlSteps": len(td.get("timeline", [])) if isinstance(td, dict) else 0,
    "tlEvents": [ (e or {}).get("event") for e in (td.get("timeline", []) if isinstance(td, dict) else []) ],
    "publicId": pub_id,
}, ensure_ascii=False))

print("\n✅ 全流程跑通！")
print("消费者可在浏览器打开: http://localhost:5173/trace/" + pub_id)