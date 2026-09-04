import { useEffect, useMemo, useRef, useState } from "react";
import { Graph } from "@antv/x6";
import * as echarts from "echarts";
import {
  IconAlertTriangle, IconArchive, IconArrowLeft, IconBell, IconCalendar,
  IconChartBar, IconChevronRight, IconClipboardCheck, IconDatabase,
  IconFileDescription, IconGitBranch, IconHelpCircle, IconHome, IconLock,
  IconMenu2, IconPackage, IconPrinter, IconQrcode, IconSearch, IconSettings,
  IconShieldCheck, IconSnowflake, IconTemperature, IconTruck, IconUser, IconX,
} from "@tabler/icons-react";

const navItems = [
  ["首页", IconHome, "home"], ["批次追溯", IconGitBranch, "trace"],
  ["批次档案", IconArchive, "archive"], ["流通与交接", IconTruck, "handover"],
  ["温控监测", IconTemperature, "temperature"], ["质量事件", IconAlertTriangle, "quality", 2],
  ["报表分析", IconChartBar, "reports"], ["基础数据", IconDatabase, "master"],
  ["系统管理", IconSettings, "system"],
];

const batchDetails = {
  rawA: { title: "批次详情（原料批次）", code: "RAW-20260831-017", name: "扇贝（活）", type: "来源批次", quantity: "600 kg", origin: "辽宁海域", businessTime: "2026-08-29 06:30", recordedTime: "2026-08-29 07:10", source: "人工录入", evidence: 3 },
  rawB: { title: "批次详情（原料批次）", code: "RAW-20260830-023", name: "扇贝（活）", type: "来源批次", quantity: "420 kg", origin: "山东海域", businessTime: "2026-08-30 05:40", recordedTime: "2026-08-30 06:05", source: "人工录入", evidence: 3 },
  processed: { title: "批次详情（加工批次）", code: "FS-20260903-001", name: "冷冻扇贝柱 1 kg/袋", type: "加工批次", quantity: "1,000 kg", origin: "渤海水产加工有限公司", businessTime: "2026-09-01 14:20", recordedTime: "2026-09-01 14:35", source: "企业业务系统", evidence: 6 },
  downstreamA: { title: "批次详情（流通批次）", code: "TR-20260902-001", name: "冷冻扇贝柱 1 kg/袋", type: "流通批次", quantity: "480 kg", origin: "锦程冷链物流", businessTime: "2026-09-02 10:00", recordedTime: "2026-09-02 10:08", source: "人工录入", evidence: 2 },
  downstreamB: { title: "批次详情（流通批次）", code: "TR-20260902-002", name: "冷冻扇贝柱 1 kg/袋", type: "流通批次", quantity: "520 kg", origin: "海联冷链物流", businessTime: "2026-09-02 15:30", recordedTime: "2026-09-02 15:42", source: "人工录入", evidence: 2 },
};

function LineageGraph({ selected, onSelect, risk = false }) {
  const host = useRef(null);
  const selectRef = useRef(onSelect);
  selectRef.current = onSelect;
  useEffect(() => {
    if (!host.current) return undefined;
    const graph = new Graph({ container: host.current, width: 850, height: 305, background: { color: "#ffffff" }, interacting: false });
    const nodes = [
      { id: "rawA", x: 16, y: 16, label: "原料批次\nRAW-20260831-017\n扇贝（活）· 辽宁海域\n600 kg" },
      { id: "rawB", x: 16, y: 181, label: "原料批次\nRAW-20260830-023\n扇贝（活）· 山东海域\n420 kg" },
      { id: "processed", x: 326, y: 92, width: 208, height: 122, label: risk ? "风险批次（已冻结）\nFS-20260903-001\n冷冻扇贝柱 1 kg/袋\n1,000 kg" : "加工批次（选中）\nFS-20260903-001\n冷冻扇贝柱 1 kg/袋\n投入 1,020 kg · 产出 1,000 kg" },
      { id: "downstreamA", x: 650, y: 16, label: "流通批次\nTR-20260902-001\n锦程冷链物流\n480 kg" },
      { id: "downstreamB", x: 650, y: 181, label: "流通批次\nTR-20260902-002\n海联冷链物流\n520 kg" },
    ];
    nodes.forEach((item) => graph.addNode({
      shape: "rect", width: item.width || 184, height: item.height || 108, ...item,
      attrs: {
        body: { rx: 8, ry: 8, stroke: item.id === selected ? (risk && item.id === "processed" ? "#ef4444" : "#0284c7") : "#cbd5e1", strokeWidth: item.id === selected ? 2 : 1, fill: risk && item.id === "processed" ? "#fef2f2" : item.id === "processed" ? "#f0f9ff" : "#ffffff" },
        label: { text: item.label, fill: "#0f172a", fontSize: 12, lineHeight: 18, fontWeight: item.id === "processed" ? 600 : 500, fontFamily: "Inter, 'Noto Sans SC', sans-serif", textWrap: { width: (item.width || 184) - 28, height: (item.height || 108) - 22, ellipsis: true } },
      },
    }));
    const edgeAttrs = { line: { stroke: risk ? "#ef4444" : "#0284c7", strokeWidth: 2, targetMarker: { name: "block", width: 8, height: 8 } } };
    [["rawA", "processed", ""], ["rawB", "processed", "多源合批"], ["processed", "downstreamA", "批次拆分"], ["processed", "downstreamB", ""]].forEach(([source, target, label]) => graph.addEdge({
      source, target,
      router: { name: "manhattan" },
      connector: { name: "rounded", args: { radius: 12 } },
      attrs: edgeAttrs,
      labels: label ? [{ position: .5, attrs: { body: { fill: risk ? "#ef4444" : "#0284c7", stroke: "none", rx: 4, ry: 4, refWidth: "130%", refHeight: "180%", refX: "-15%", refY: "-40%" }, label: { text: label, fill: "#ffffff", fontSize: 11, fontWeight: 600 } } }] : [],
    }));
    graph.on("node:click", ({ node }) => selectRef.current(node.id));
    return () => graph.dispose();
  }, [selected, risk]);
  return <div className="graph-host" ref={host} aria-label="批次谱系图" />;
}

function TemperatureChart({ alert = false }) {
  const host = useRef(null);
  useEffect(() => {
    if (!host.current) return undefined;
    const chart = echarts.init(host.current, null, { renderer: "svg" });
    chart.setOption({
      animation: false, grid: { left: 42, right: 22, top: 20, bottom: 32 },
      tooltip: { trigger: "axis", valueFormatter: (value) => `${value} ℃` },
      xAxis: { type: "category", boundaryGap: false, data: ["原料捕捞", "加工速冻", "冷库储存", "冷链运输", "终端接收"], axisLine: { lineStyle: { color: "#94a3b8" } }, axisLabel: { color: "#475569", fontSize: 11 }, splitLine: { show: true, lineStyle: { color: "#e2e8f0" } } },
      yAxis: { type: "value", min: -40, max: 5, interval: 10, name: "℃", nameTextStyle: { color: "#64748b" }, axisLabel: { color: "#64748b" }, splitLine: { lineStyle: { color: "#e2e8f0", type: "dashed" } } },
      series: [
        { name: "温度记录", type: "line", smooth: true, symbolSize: 8, data: alert ? [-0.6, -32.4, -19.7, -15.6, -19.1] : [-0.6, -32.4, -19.7, -20.3, -19.1], itemStyle: { color: alert ? "#ef4444" : "#0284c7" }, lineStyle: { color: alert ? "#ef4444" : "#0284c7", width: 2.5 }, areaStyle: { color: alert ? "rgba(239,68,68,.08)" : "rgba(2,132,199,.08)" }, label: { show: true, formatter: "{c} ℃", color: "#0369a1", fontSize: 11, position: "top" }, markArea: { silent: true, data: [[{ xAxis: "原料捕捞", itemStyle: { color: "rgba(16,185,129,.06)" } }, { xAxis: "加工速冻" }], [{ xAxis: "加工速冻", itemStyle: { color: "rgba(56,189,248,.08)" } }, { xAxis: "冷库储存" }], [{ xAxis: "冷库储存", itemStyle: { color: "rgba(99,102,241,.05)" } }, { xAxis: "终端接收" }]] } },
        { type: "line", symbol: "none", data: [4, null, null, null, null], lineStyle: { type: "dashed", color: "#10b981" }, tooltip: { show: false } },
        { type: "line", symbol: "none", data: [null, -30, null, null, null], lineStyle: { type: "dashed", color: "#38bdf8" }, tooltip: { show: false } },
        { type: "line", symbol: "none", data: [null, null, -18, -18, -18], lineStyle: { type: "dashed", color: "#6366f1" }, tooltip: { show: false } },
      ],
    });
    const observer = new ResizeObserver(() => chart.resize()); observer.observe(host.current);
    return () => { observer.disconnect(); chart.dispose(); };
  }, [alert]);
  return <div className="temperature-chart" ref={host} aria-label="分环节温控曲线" />;
}

function BatchDetail({ batch, onEvidence }) {
  return <section className="panel detail-panel"><div className="panel-heading"><h2>{batch.title}</h2><IconX size={18} /></div><dl className="detail-list"><div><dt>追溯码</dt><dd className="mono">{batch.code}</dd></div><div><dt>批次名称</dt><dd>{batch.name}</dd></div><div><dt>批次类型</dt><dd>{batch.type}</dd></div><div><dt>当前状态</dt><dd><span className="status success"><IconShieldCheck size={15} />正常流转</span></dd></div><div><dt>净含量</dt><dd className="mono">{batch.quantity}</dd></div><div><dt>数据来源</dt><dd>{batch.source}</dd></div></dl><div className="time-audit"><div><span>业务发生时间</span><strong className="mono">{batch.businessTime}</strong></div><div><span>系统记录时间</span><strong className="mono recorded">{batch.recordedTime}</strong></div></div><dl className="detail-list compact"><div><dt>主体/来源</dt><dd>{batch.origin}</dd></div><div><dt>操作员</dt><dd>张伟</dd></div></dl><button className="button secondary full" onClick={onEvidence}><IconFileDescription size={17} />查看证据（{batch.evidence} 条）</button></section>;
}

function TracePage({ notify }) {
  const [selected, setSelected] = useState("processed"); const [code, setCode] = useState("FS-20260903-001"); const [error, setError] = useState(""); const batch = batchDetails[selected];
  const submit = () => { if (code.trim() !== "FS-20260903-001") setError("未找到该追溯码，请检查后重试"); else { setError(""); notify("已加载完整批次谱系"); } };
  return <><div className="search-toolbar panel"><label htmlFor="trace-code">输入追溯码</label><div className="search-box"><IconSearch size={18} /><input id="trace-code" value={code} onChange={(e) => setCode(e.target.value)} onKeyDown={(e) => e.key === "Enter" && submit()} /></div><button className="button primary" onClick={submit}>开始追溯</button><span className="spacer" /><button className="button secondary" onClick={() => notify("追溯报告已加入导出队列")}><IconFileDescription size={17} />导出追溯报告</button><button className="button secondary" onClick={() => window.print()}><IconPrinter size={17} />打印</button></div>{error && <div className="inline-error" role="alert"><IconAlertTriangle size={18} />{error}</div>}<div className="trace-grid"><div className="trace-main"><section className="panel graph-panel"><div className="section-heading"><div><h2>批次溯源图谱</h2><p>点击任一节点查看对应业务记录与证据</p></div><div className="legend"><span><i className="line" />来源 / 流向</span><span><i className="line dashed" />多源合批 / 拆分</span><span>单位：kg</span></div></div><LineageGraph selected={selected} onSelect={setSelected} /><div className="balance-strip"><span>投入</span><strong>1,020 kg</strong><span>−</span><span>损耗</span><strong>20 kg</strong><span>=</span><span>产出</span><strong>1,000 kg</strong></div></section><section className="panel temperature-panel"><div className="section-heading"><div><h2>温控记录 <small>（按环节判定）</small></h2><p>规则仅为演示配置，不构成法规符合性结论</p></div><button className="button ghost"><IconLock size={15} />规则配置</button></div><div className="rule-row">{["原料捕捞 · -2～4 ℃", "加工速冻 · ≤ -30 ℃", "冷库储存 · ≤ -18 ℃", "冷链运输 · ≤ -18 ℃", "终端接收 · ≤ -18 ℃"].map((rule) => <span key={rule}>{rule}<em>示例规则</em></span>)}</div><TemperatureChart /><div className="chart-result success"><IconShieldCheck size={17} />当前记录未发现越界（各环节温度均在示例规则范围内）</div></section></div><aside className="trace-aside"><BatchDetail batch={batch} onEvidence={() => notify(`${batch.code}：证据目录已打开`)} /><section className="panel summary-panel"><h2>批次摘要</h2><div className="summary-grid">{[["合批方式","多源合批"],["输入原料","2 批次"],["输入总量","1,020 kg"],["成品总量","1,000 kg"],["下游批次","2 批次"],["追溯链路","完整"],["直接客户","2 家"],["间接客户","4 家"]].map(([k,v]) => <div key={k}><span>{k}</span><strong className={v === "完整" ? "green" : ""}>{v}</strong></div>)}</div></section></aside></div><DataSourceBadge /></>;
}

const events = [["来源建档","2026-08-29 06:30","2026-08-29 07:10","捕捞记录、来源凭证","完整"],["加工与速冻","2026-09-01 14:20","2026-09-01 14:35","加工记录、封签照片","完整"],["出厂检测","2026-09-01 18:00","2026-09-01 18:22","出厂检验报告","完整"],["冷链发运","2026-09-02 10:00","2026-09-02 10:08","运单","待补"],["交接签收","2026-09-02 19:10","2026-09-02 19:18","交接签收单","完整"]];

function ArchivePage({ notify }) {
  return <div className="page-stack"><div className="page-title"><div><h1>批次档案与证据链</h1><p>批次 FS-20260903-001 的结构化履历与受控凭证</p></div><span className="status success"><IconShieldCheck size={15} />正常流转</span></div><div className="archive-layout"><section className="panel composition"><h2>本批次构成</h2><div className="source-card"><b>RAW-20260831-017</b><span>辽宁海域 · 扇贝（活）</span><strong>600 kg</strong></div><div className="plus">＋</div><div className="source-card"><b>RAW-20260830-023</b><span>山东海域 · 扇贝（活）</span><strong>420 kg</strong></div><div className="composition-result"><IconSnowflake size={22} /><div><span>产出批次</span><b>FS-20260903-001</b><small>投入 1,020 kg − 损耗 20 kg = 产出 1,000 kg</small></div></div></section><section className="panel evidence-table"><div className="section-heading"><div><h2>追溯事件与证据链</h2><p>业务时间与系统记录时间分别留痕</p></div></div><table><thead><tr><th>事件</th><th>业务发生时间</th><th>系统记录时间</th><th>关联证据</th><th>状态</th></tr></thead><tbody>{events.map((row) => <tr key={row[0]}>{row.map((cell, index) => <td key={index} className={index === 1 || index === 2 ? "mono" : ""}>{index === 4 ? <span className={`status ${cell === "完整" ? "success" : "warning"}`}>{cell}</span> : cell}</td>)}</tr>)}</tbody></table></section></div><div className="two-card-grid"><section className="panel info-card"><IconClipboardCheck size={24} /><div><h2>出厂检测摘要</h2><p>报告编号：DEMO-QA-20260901-01</p><strong className="green">结论：合格（模拟报告）</strong></div><button className="button secondary" onClick={() => notify("检测报告预览已打开")}>查看摘要</button></section><section className="panel info-card"><IconTemperature size={24} /><div><h2>全链路温控摘要</h2><p>记录 128 条 · 数据来源：模拟数据</p><strong className="green">越界 0 次</strong></div><button className="button secondary" onClick={() => notify("温控明细已打开")}>查看明细</button></section></div><DataSourceBadge /></div>;
}

function QualityPage({ notify }) {
  const [step, setStep] = useState(0); const labels = ["确认事件", "冻结批次", "确认范围", "发起模拟召回"];
  return <div className="page-stack"><div className="risk-banner"><IconAlertTriangle size={26} /><div><b>高风险温控事件 · EVT-TEMP-20260902-002</b><span>冷链运输实测 -15.6 ℃，超过该环节示例上限 -18 ℃，持续 15 分钟</span></div><span className={`status ${step === 4 ? "success" : "danger"}`}>{step === 4 ? "已处置" : "待处置"}</span></div><div className="quality-layout"><div className="quality-main"><section className="panel graph-panel"><div className="section-heading"><div><h2>波及面分析</h2><p>上游来源、当前库存、在途批次与下游接收方</p></div><span className="status danger"><IconLock size={14} />风险批次</span></div><LineageGraph selected="processed" onSelect={() => {}} risk /></section><section className="panel temperature-panel"><div className="section-heading"><div><h2>超温时段</h2><p>异常发生于冷链运输环节 22:37–22:52</p></div></div><TemperatureChart alert /><div className="chart-result danger"><IconAlertTriangle size={17} />最高温度 -15.6 ℃，已生成高风险事件</div></section></div><aside className="panel action-timeline"><h2>处置时间线</h2>{labels.map((label, index) => <button key={label} className={`action-step ${index < step ? "done" : index === step ? "active" : ""}`} disabled={index !== step}><span>{index < step ? "✓" : index + 1}</span><div><b>{label}</b><small>{index < step ? "已完成并留痕" : index === step ? "等待质量管理员操作" : "完成上一步后解锁"}</small></div></button>)}<button className={`button ${step === 3 ? "danger" : step === 4 ? "secondary" : "primary"} full`} disabled={step === 4} onClick={() => { if (step < 3) { setStep(step + 1); notify(`${labels[step]}已完成`); } else { setStep(4); notify("模拟召回已发起，通知记录已生成"); } }}>{step === 4 ? "模拟召回已发起" : step === 3 ? "发起模拟召回" : labels[step]}</button><p className="disclaimer">教学演练流程，不替代真实法定召回程序。</p></aside></div><DataSourceBadge /></div>;
}

function HandoverPage({ notify }) {
  const [step, setStep] = useState(0); const [submitted, setSubmitted] = useState(false); const stepNames = ["基础信息", "原料关联", "工艺记录", "附件确认"];
  return <div className="page-stack"><div className="page-title"><div><h1>新建加工批次</h1><p>通过向导建立批次、原料关系与首个追溯事件</p></div><span className="status neutral">草稿</span></div><section className="panel wizard"><div className="steps">{stepNames.map((name, index) => <div className={`step ${index === step ? "active" : index < step ? "done" : ""}`} key={name}><span>{index < step ? "✓" : index + 1}</span><b>{name}</b></div>)}</div>{!submitted ? <div className="form-body">{step === 0 && <div className="form-grid"><label>批次名称<input defaultValue="冷冻扇贝柱" /></label><label>规格<input defaultValue="1 kg/袋，10 袋/箱" /></label><label>生产组织<input defaultValue="渤海水产加工有限公司" /></label><label>计划产量<div className="input-unit"><input defaultValue="1000" /><span>kg</span></div></label></div>}{step === 1 && <div className="relation-select"><h3>选择上游原料批次</h3><label><input type="checkbox" defaultChecked /> RAW-20260831-017 · 扇贝（活）· 600 kg</label><label><input type="checkbox" defaultChecked /> RAW-20260830-023 · 扇贝（活）· 420 kg</label><div className="balance-strip"><span>投入 1,020 kg</span><span>− 计划损耗 20 kg</span><strong>= 产出 1,000 kg</strong></div></div>}{step === 2 && <div className="form-grid"><label>加工开始时间<input defaultValue="2026-09-01 13:20" /></label><label>业务发生时间<input defaultValue="2026-09-01 14:20" /></label><label>速冻工艺<input defaultValue="隧道式速冻" /></label><label>示例温控规则<input defaultValue="加工速冻 ≤ -30 ℃" /></label></div>}{step === 3 && <div className="upload-list"><div><IconFileDescription /><span>加工记录-DEMO.pdf<small>模拟附件 · 受控访问</small></span><b>已上传</b></div><div><IconFileDescription /><span>封签照片-DEMO.jpg<small>模拟附件 · 受控访问</small></span><b>已上传</b></div><label><input type="checkbox" defaultChecked /> 我确认业务发生时间、数量与数据来源已核对</label></div>}<div className="wizard-actions"><button className="button secondary" disabled={step === 0} onClick={() => setStep(step - 1)}><IconArrowLeft size={16} />上一步</button><button className="button primary" onClick={() => { if (step < 3) setStep(step + 1); else { setSubmitted(true); notify("加工批次草稿已创建"); } }}>{step < 3 ? <>下一步<IconChevronRight size={16} /></> : "创建草稿"}</button></div></div> : <div className="success-state"><IconShieldCheck size={48} /><h2>批次草稿已创建</h2><p>系统已生成内部批次标识；提交前仍可编辑，尚未写入正式追溯链。</p><button className="button primary" onClick={() => { setSubmitted(false); setStep(0); }}>继续完善</button></div>}</section><DataSourceBadge /></div>;
}

function ConsumerPage({ onBack }) {
  const [state, setState] = useState("valid");
  return <div className="consumer-canvas"><button className="consumer-back" onClick={onBack}><IconArrowLeft size={18} />返回企业端</button><main className="consumer-phone"><header><IconSnowflake size={25} /><div><b>冷冻海产品溯源查询</b><span>查询时间 2026-09-03 10:30</span></div></header><div className="consumer-hero"><span className={`status ${state === "recall" ? "danger" : "success"}`}>{state === "recall" ? "模拟召回提示" : "当前记录正常"}</span><h1>冷冻扇贝柱</h1><p>规格 1 kg/袋 · 批次 FS-20260903-001</p></div><div className="consumer-state-tabs"><button onClick={() => setState("valid")} className={state === "valid" ? "active" : ""}>有效码</button><button onClick={() => setState("recall")} className={state === "recall" ? "active" : ""}>召回状态</button></div>{state === "recall" && <div className="consumer-alert"><IconAlertTriangle size={20} /><div><b>该批次正在进行模拟召回</b><span>请暂停食用，并按页面公布的演示渠道处理。</span></div></div>}<section><h2>产品来源</h2><dl><div><dt>来源类型</dt><dd>国内捕捞</dd></div><div><dt>产地/海域</dt><dd>辽宁及山东近海（脱敏）</dd></div><div><dt>生产日期</dt><dd>2026-09-01</dd></div></dl></section><section><h2>关键履历</h2><ol className="consumer-timeline"><li><b>来源建档</b><span>2026-08-29 · 数据来源：人工录入</span></li><li><b>加工与速冻</b><span>2026-09-01 · 数据来源：企业业务系统</span></li><li><b>冷链运输与交接</b><span>2026-09-02 · 数据来源：模拟数据</span></li></ol></section><section className="consumer-cold"><IconTemperature size={22} /><div><b>温控摘要</b><span>各环节按对应示例规则判定；当前记录未发现越界。</span></div></section><footer><IconShieldCheck size={18} /><p>本页展示教学演练数据，仅表示系统内记录可查询，不构成真实性、防伪或第三方认证。</p></footer></main></div>;
}

function PlaceholderPage({ title }) { return <div className="empty-page panel"><IconPackage size={44} /><h1>{title}</h1><p>该模块已列入页面架构，本轮原型聚焦批次追溯、档案、交接、质量事件与消费者查询。</p><span className="status neutral">Phase 2 实现</span></div>; }
function DataSourceBadge() { return <div className="data-source-badge"><IconDatabase size={14} />数据来源：模拟数据（非真实物联网设备）</div>; }

function AppShell() {
  const [page, setPage] = useState("trace"); const [toast, setToast] = useState(""); const [collapsed, setCollapsed] = useState(false); const title = navItems.find((item) => item[2] === page)?.[0] ?? "批次追溯";
  const notify = (message) => { setToast(message); window.clearTimeout(window.__demoToast); window.__demoToast = window.setTimeout(() => setToast(""), 2400); };
  const content = useMemo(() => { if (page === "trace") return <TracePage notify={notify} />; if (page === "archive") return <ArchivePage notify={notify} />; if (page === "quality") return <QualityPage notify={notify} />; if (page === "handover") return <HandoverPage notify={notify} />; return <PlaceholderPage title={title} />; }, [page, title]);
  if (page === "consumer") return <ConsumerPage onBack={() => setPage("trace")} />;
  return <div className={`app-shell ${collapsed ? "collapsed" : ""}`}><aside className="sidebar"><div className="brand"><IconSnowflake /><span>冷冻海产品溯源系统</span></div><nav>{navItems.map(([label, Icon, id, badge]) => <button key={id} className={page === id ? "active" : ""} onClick={() => setPage(id)}><Icon size={21} /><span>{label}</span>{badge && <em>{badge}</em>}</button>)}</nav><button className="collapse" onClick={() => setCollapsed(!collapsed)}><IconMenu2 size={20} /><span>收起菜单</span></button></aside><div className="workspace"><header className="topbar"><div className="breadcrumb"><span>首页</span><b>/</b><strong>{title}</strong></div><div className="top-actions"><span><IconCalendar size={18} />2026-09-03</span><button aria-label="通知"><IconBell size={20} /><em>3</em></button><button><IconHelpCircle size={20} />帮助</button><button onClick={() => setPage("consumer")}><IconQrcode size={20} />消费者页预览</button><button><IconUser size={20} />质量管理员</button></div></header><main className="content">{content}</main></div>{toast && <div className="toast"><IconShieldCheck size={18} />{toast}</div>}</div>;
}

export function App() { return <AppShell />; }
