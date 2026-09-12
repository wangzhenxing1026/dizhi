/**
 * 一次性处理脚本：
 * 1) 校验 dizhi.txt 与 dizhi1.txt 行对齐
 * 2) 用 dizhi1.txt 的"新二级类型标签"补全 output.txt 为 6 列
 * 3) 修改 map.html：列表显示原地址、状态/边界筛选同步列表
 * 用法: node tools/enrich_output.js  (仅生成报告)   node tools/enrich_output.js --write  (写回文件)
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const WRITE = process.argv.includes('--write');

function readLines(p) {
  return fs.readFileSync(p, 'utf8').split(/\r?\n/).filter(x => x.length > 0);
}

// ---------- 1. dizhi / dizhi1 对齐校验 ----------
const dz = readLines(path.join(ROOT, 'src/main/resources/dizhi.txt'));
const d1 = readLines(path.join(ROOT, 'src/main/resources/dizhi1.txt'));
if (dz[0] === '具体位置描述') dz.shift();
if (d1[0] && d1[0].startsWith('新二级类型标签')) d1.shift();
console.log(`dizhi 数据行: ${dz.length}, dizhi1 数据行: ${d1.length}`);
if (dz.length !== d1.length) {
  console.error('行数不一致，中止'); process.exit(1);
}
let mismatch = 0;
for (let i = 0; i < dz.length; i++) {
  if (dz[i] !== d1[i].split('\t')[3]) {
    mismatch++;
    if (mismatch <= 10) console.log(`  不一致 行${i + 2}: dizhi=[${dz[i]}] dizhi1=[${d1[i].split('\t')[3]}]`);
  }
}
console.log(`对齐不一致: ${mismatch}`);
if (mismatch > 0) process.exit(1);

// 地址 -> 补全信息（新二级类型标签/行政区/派出所）
const infoByAddr = new Map();
for (const line of d1) {
  const c = line.split('\t');
  const label = c[0], addr = c[3];
  if (!infoByAddr.has(addr)) infoByAddr.set(addr, []);
  infoByAddr.get(addr).push(label);
}
console.log(`dizhi1 唯一地址数: ${infoByAddr.size}`);

// ---------- 2. output.txt 补全 ----------
const html = fs.readFileSync(path.join(ROOT, 'map.html'), 'utf8');
const m = html.match(/var REGIONS = (\[[\s\S]*?\]);\s*\r?\n/);
if (!m) { console.error('未找到 REGIONS'); process.exit(1); }
const REGIONS = JSON.parse(m[1]);
console.log(`REGIONS 条数: ${REGIONS.length}`);
const regionById = new Map(REGIONS.map(r => [r.regionId, r]));

const outLines = readLines(path.join(ROOT, 'output.txt'));
if (outLines.length !== REGIONS.length) {
  console.error(`output.txt 行数 ${outLines.length} 与 REGIONS ${REGIONS.length} 不一致，中止`);
  process.exit(1);
}
const noMatch = [];
const newOut = outLines.map((line, i) => {
  const cols = line.split('\t');
  if (cols.length !== 5) { console.error(`第${i + 1}行列数=${cols.length}，中止`); process.exit(1); }
  const reg = regionById.get(+cols[0]);
  if (!reg || reg.regionId !== +cols[0]) { console.error(`第${i + 1}行 regionId 与 REGIONS 不匹配`); process.exit(1); }
  const labels = infoByAddr.get(reg.inputAddress);
  let placeType;
  if (labels && labels.length) {
    placeType = [...new Set(labels)].join('/');
  } else {
    placeType = '';
    noMatch.push({ id: cols[0], addr: reg.inputAddress });
  }
  return [cols[0], cols[1], cols[2], cols[3], reg.inputAddress, placeType].join('\t');
});
const typeCounts = {};
newOut.forEach(l => { const t = l.split('\t')[5] || '(空)'; typeCounts[t] = (typeCounts[t] || 0) + 1; });
console.log('place_type 分布:', typeCounts);
if (noMatch.length) {
  console.log(`无匹配(${noMatch.length}):`);
  noMatch.slice(0, 20).forEach(x => console.log(`  ${x.id}\t${x.addr}`));
}

// ---------- 3. map.html 修改 ----------
let h = html;
const edits = [];

// 3.1 面板加边界筛选按钮行（不依赖换行符，直接接在最后一个状态按钮后）
edits.push([
  '<button data-st="低置信度">低置信度</button>',
  '<button data-st="低置信度">低置信度</button><br/>\\n<b>边界筛选：</b>'
    + '<button class="active" data-bd="all">全部</button>'
    + '<button data-bd="yes">有边界</button>'
    + '<button data-bd="no">无边界</button>'
]);

// 3.2 列表面板下移，给新增按钮行腾位置
edits.push([ '#listPanel{position:absolute;top:90px;', '#listPanel{position:absolute;top:124px;' ]);

// 3.3 筛选状态与过滤逻辑（状态+边界，地图与列表共用）；文件行尾为 \r\r\n，全部用单行锚点替换
edits.push([
  'function filter(st){',
  'var curSt = \'all\', curBd = \'all\';\n'
    + 'function bdHas(r){ return r.polygon && r.polygon.length >= 3; }\n'
    + 'function passFilter(r){ if (curSt !== \'all\' && r.status !== curSt) return false; if (curBd === \'yes\' && !bdHas(r)) return false; if (curBd === \'no\' && bdHas(r)) return false; return true; }\n'
    + 'function filter(st){'
]);
edits.push([
  "var show = st === 'all' || r.status === st;",
  'var show = passFilter(r);'
]);

// 3.4 状态按钮选择器收窄到 data-st（避免误绑边界按钮），记录状态并同步刷新列表
edits.push([
  "var btns = document.querySelectorAll('#panel button');",
  "var btns = document.querySelectorAll('#panel button[data-st]');"
]);
edits.push([
  "    filter(b.getAttribute('data-st'));",
  "    curSt = b.getAttribute('data-st');\n"
    + '    filter(curSt);\n'
    + "    buildList(document.getElementById('addrSearch').value.trim());"
]);
// 边界按钮组：插在状态按钮处理器之后（escAttr 前）
edits.push([
  'function escAttr(s){',
  "var bdBtns = document.querySelectorAll('#panel button[data-bd]');\n"
    + 'bdBtns.forEach(function(b){\n'
    + "  b.addEventListener('click', function(){\n"
    + "    bdBtns.forEach(function(x){ x.classList.remove('active'); });\n"
    + "    b.classList.add('active');\n"
    + "    curBd = b.getAttribute('data-bd');\n"
    + '    filter(curSt);\n'
    + "    buildList(document.getElementById('addrSearch').value.trim());\n"
    + '  });\n'
    + '});\n'
    + 'function escAttr(s){'
]);

// 3.5 列表项显示原地址（map.html 原文为：+ '"></span>' + esc(r.regionName) + '</div>';）
edits.push([
  "'\"></span>' + esc(r.regionName) + '</div>';",
  "'\"></span>' + esc(r.inputAddress) + '</div>';"
]);

// 3.6 buildList 同样应用状态+边界筛选
edits.push([
  "if (kw2 && r.regionName.toLowerCase().indexOf(kw2) < 0 && r.inputAddress.toLowerCase().indexOf(kw2) < 0) return;",
  'if (!passFilter(r)) return;\n'
    + "    if (kw2 && r.regionName.toLowerCase().indexOf(kw2) < 0 && r.inputAddress.toLowerCase().indexOf(kw2) < 0) return;"
]);

for (const [from, to] of edits) {
  const f = from.replace(/\\n/g, '\n').replace(/\\'/g, "'");
  const t = to.replace(/\\n/g, '\n').replace(/\\'/g, "'");
  if (h.split(f).length !== 2) {
    console.error('替换点未唯一命中: ' + JSON.stringify(f.slice(0, 80)));
    process.exit(1);
  }
  h = h.replace(f, t);
}

// ---------- 写回 ----------
if (WRITE) {
  fs.writeFileSync(path.join(ROOT, 'output.txt'), newOut.join('\r\n') + '\r\n', 'utf8');
  fs.writeFileSync(path.join(ROOT, 'map.html'), h, 'utf8');
  console.log('已写回 output.txt 和 map.html');
} else {
  console.log('[dry-run] 未写回。加 --write 执行写回');
}
