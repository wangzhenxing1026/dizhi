/**
 * 合并脚本（在页面内截获的 qt=s 原始响应基础上，复刻 Java 流水线）：
 * 1) output.txt：region_id 全部换成 dizhi1.txt 楼栋编码；追加 香榭名园3栋、华润大厦(南山) 两行
 * 2) failed.txt：移除 香榭名园3栋（沙河武装部保留）
 * 3) review.txt：追加 香榭名园3栋（低置信度）
 * 4) map.html：REGIONS 增加 code/typeLabel 字段并追加两条新记录；列表改两行样式（序号/编码/边界标签/类型）
 * 用法: node tools/merge_new.js [--write]
 */
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const WRITE = process.argv.includes('--write');

// ---------- 通用工具（移植 OutputFormatter/CoordinateConverter） ----------
function fmt14(v) {
  let s = v.toFixed(14);
  if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
  return s;
}
const MCBAND = [12890594.86, 8362377.87, 5591021, 3481989.83, 1678043.12, 0];
const MC2LL = [
  [1.410526172116255e-8, 8.98305509648872e-6, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2],
  [-7.435856389565537e-9, 8.983055097726239e-6, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86],
  [-3.030883460898826e-8, 8.98305509983578e-6, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37],
  [-1.981981304930552e-8, 8.983055099779535e-6, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06],
  [3.09191371068437e-9, 8.983055096812155e-6, 0.00006995724062, 23.10934304144901, -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4],
  [2.890871144776878e-9, 8.983055095805407e-6, -3.068298e-8, 7.47137025468032, -0.00000353937994, -0.02145144861037, -0.00001234426596, 0.00010322952773, -0.00000323890364, 826088.5]
];
function mcToBd09(x, y) {
  let c = MC2LL[MC2LL.length - 1];
  for (let i = 0; i < MCBAND.length; i++) {
    if (Math.abs(y) >= MCBAND[i]) { c = MC2LL[i]; break; }
  }
  let lon = c[0] + c[1] * Math.abs(x);
  const b = Math.abs(y) / c[9];
  const lat = c[2] + c[3] * b + c[4] * b * b + c[5] * b * b * b + c[6] * b * b * b * b + c[7] * b * b * b * b * b + c[8] * b * b * b * b * b * b;
  return [lon * (x < 0 ? -1 : 1), lat * (y < 0 ? -1 : 1)];
}
function centerJson(p) { return `{"lon":${fmt14(p[0])},"lat":${fmt14(p[1])}}`; }
function polygonJson(poly) {
  return '[' + poly.map(p => `{"lon":${fmt14(p[0])},"lat":${fmt14(p[1])}}`).join(',') + ']';
}

// ---------- PolygonUtils 移植 ----------
function parseBudGeom(s) {
  const result = [];
  if (!s) return result;
  const re = /POLYGON\s*\(\(([^)]*)\)\)/g;
  let m;
  while ((m = re.exec(s))) {
    const poly = [];
    for (const pair of m[1].split(',')) {
      const xy = pair.trim().split(/\s+/);
      if (xy.length >= 2) {
        const x = parseFloat(xy[0]), y = parseFloat(xy[1]);
        if (!isNaN(x) && !isNaN(y)) poly.push([x, y]);
      }
    }
    if (poly.length >= 3) result.push(poly);
  }
  return result;
}
function bbox(poly) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of poly) { minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); }
  return [minX, minY, maxX, maxY];
}
function inBbox(px, py, bb) { return px >= bb[0] && px <= bb[2] && py >= bb[1] && py <= bb[3]; }
function isPointInPolygon(px, py, poly) {
  let inside = false;
  const n = poly.length;
  for (let i = 0, j = n - 1; i < n; j = i++) {
    const xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
    if (((yi > py) !== (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) inside = !inside;
  }
  return inside;
}
function area(poly) {
  let s = 0; const n = poly.length;
  for (let i = 0, j = n - 1; i < n; j = i++) s += (poly[j][0] + poly[i][0]) * (poly[j][1] - poly[i][1]);
  return Math.abs(s) / 2.0;
}
function selectPolygon(polygons, cx, cy) {
  if (!polygons || !polygons.length) return null;
  let largest = null, largestArea = -1;
  for (const poly of polygons) {
    const bb = bbox(poly);
    if (inBbox(cx, cy, bb) && isPointInPolygon(cx, cy, poly)) return poly;
    const a = area(poly);
    if (a > largestArea) { largestArea = a; largest = poly; }
  }
  return largest;
}

// ---------- ResponseParser 移植 ----------
function extractCenter(n) {
  const diPoint = n.ext && n.ext.detail_info && n.ext.detail_info.navi_xy && n.ext.detail_info.navi_xy.diPoint;
  if (diPoint && typeof diPoint === 'object') {
    const x = Number(diPoint.x), y = Number(diPoint.y);
    if (!isNaN(x) && !isNaN(y) && diPoint.x != null) return [x, y];
  }
  if (n.diPointX != null && n.diPointY != null && Number(n.diPointX) !== 0) {
    return [Number(n.diPointX) / 100.0, Number(n.diPointY) / 100.0];
  }
  if (typeof n.geo === 'string') {
    const m = n.geo.match(/(-?\d+\.?\d*),(-?\d+\.?\d*)/);
    if (m) return [parseFloat(m[1]), parseFloat(m[2])];
  }
  return null;
}
function parseCandidates(raw) {
  const root = JSON.parse(raw);
  const recaptcha = root.result && root.result.anti_session && root.result.anti_session.need_recaptcha;
  const err = root.result && root.result.error != null ? root.result.error : 0;
  if (err !== 0 || recaptcha) {
    throw new Error('响应异常: need_recaptcha=' + !!recaptcha + ' error=' + err);
  }
  const content = Array.isArray(root.content) ? root.content : [];
  const candidates = [];
  content.forEach((n, index) => {
    if (!n || typeof n !== 'object') return;
    const c = { index, name: n.name ?? null, addr: n.addr ?? n.poi_address ?? null, stdTag: n.std_tag ?? null, cityName: n.city_name ?? null, showTag: null, polygons: [], hasCenter: false };
    if (typeof n.tag_info === 'string' && n.tag_info.startsWith('{')) {
      try {
        const ti = JSON.parse(n.tag_info);
        c.showTag = ti.classification ? ti.classification.show_tag : null;
        if (c.stdTag == null && ti.classification) c.stdTag = ti.classification.std_tag;
      } catch (e) {}
    }
    if (c.showTag == null && Array.isArray(n.show_tag) && n.show_tag.length > 0) c.showTag = n.show_tag[0];
    const ctr = extractCenter(n);
    if (ctr) { c.centerX = ctr[0]; c.centerY = ctr[1]; c.hasCenter = true; }
    const budGeom = n.ext && n.ext.detail_info && n.ext.detail_info.guoke_geo_bud && n.ext.detail_info.guoke_geo_bud.bud_geom;
    if (budGeom) c.polygons = parseBudGeom(budGeom);
    candidates.push(c);
  });
  return candidates;
}

// ---------- AddressMatcher 移植 ----------
const CJK_RUN = /[\u4e00-\u9fa5]{2,}/g;
const UNIT_TOKEN = /[0-9A-Za-z]{1,4}(栋|座|楼|幢|号楼|单元)/g;
const CJK_NUM_UNIT = /[一二三四五六七八九十]{1,3}单元?/g;
const UNIT_CHARS = '栋座楼幢号楼单元';
const RESIDENTIAL_HINT = /栋|座|单元|楼|花园|苑|村|公寓|家园|小区|府|阁|居|庭|台|轩|庄/;
const AREA_DISTRICTS = [['香蜜湖','福田区'],['华强北','福田区'],['梅林','福田区'],['车公庙','福田区'],['上下沙','福田区'],['前海','南山区'],['蛇口','南山区'],['西丽','南山区'],['科技园','南山区'],['南油','南山区'],['华侨城','南山区'],['沙河','南山区'],['石岩','宝安区'],['西乡','宝安区'],['福永','宝安区'],['沙井','宝安区'],['松岗','宝安区'],['坪洲','宝安区'],['布吉','龙岗区'],['平湖','龙岗区'],['葵涌','大鹏新区'],['大鹏','大鹏新区']];
const DISTRICTS = ['福田区','罗湖区','南山区','盐田区','宝安区','龙岗区','龙华区','坪山区','光明区','大鹏新区'];
const GENERIC_WORDS = ['宿舍','宿舍楼','教学楼','办公楼','项目部','工业园','工业区','商业楼','综合楼','家属楼','住宅楼','大楼','厂房','仓库','基地','停车场','大门','食堂'];
const COMMERCIAL_TAG = /餐饮|美食|小吃|快餐|蛋糕|购物|超市|便利店|公司|企业|酒店|宾馆|生活服务|美容|健身|药店|金融/;
const nvl = s => s == null ? '' : s;
function isGeneric(s) { return GENERIC_WORDS.some(g => s.includes(g)); }
function cjkRuns(s) { return s == null ? [] : (s.match(CJK_RUN) || []); }
function effectiveRuns(s) { return cjkRuns(s).filter(r => !(r.length <= 4 && isGeneric(r))); }
function cjkLen(s) { let n = 0; for (const ch of s) if (ch >= '\u4e00' && ch <= '\u9fa5') n++; return n; }
function isSubsequence(a, b) { let i = 0; for (let j = 0; j < b.length && i < a.length; j++) if (a[i] === b[j]) i++; return i === a.length; }
function unitTokens(input) {
  const tokens = [];
  if (input == null) return tokens;
  for (const re of [new RegExp(UNIT_TOKEN.source, 'g'), new RegExp(CJK_NUM_UNIT.source, 'g')]) {
    let m; while ((m = re.exec(input))) tokens.push(m[0]);
  }
  return tokens;
}
function inputDistrict(input) {
  for (const [area, d] of AREA_DISTRICTS) if (input.includes(area)) return d;
  for (const d of DISTRICTS) if (input.includes(d.slice(0, d.length - 1))) return d;
  return null;
}
function tagAll(c) { return nvl(c.stdTag) + ' ' + nvl(c.showTag); }
function nameScore(candName, input) {
  if (candName == null || candName === '' || input == null) return 0;
  const inS = input.replace(/ /g, ''), cn = candName.replace(/ /g, '');
  if (cn === inS) return 40;
  const genericName = isGeneric(cn);
  if (cn.includes(inS)) return genericName ? 15 : 30;
  const tokens = unitTokens(input);
  if (!genericName && inS.includes(cn) && cjkLen(cn) >= 2) return 30;
  if (!genericName && inS.length >= 5 && isSubsequence(inS, cn)) return 28;
  const runs = effectiveRuns(input);
  if (!runs.length) return 0;
  let hits = 0;
  for (const r of runs) if (cn.includes(r)) hits++;
  if (hits === 0) return 0;
  let score = 40.0 * hits / runs.length;
  if (tokens.length && !tokens.some(t => cn.includes(t))) score *= 0.5;
  return score;
}
function addrScore(candAddr, input) {
  if (candAddr == null || candAddr === '') return 0;
  const runs = effectiveRuns(input);
  let score = 0;
  if (runs.length) {
    let hits = 0;
    for (const r of runs) if (candAddr.includes(r)) hits++;
    score = 25.0 * hits / runs.length;
  }
  const district = inputDistrict(input);
  if (district != null) {
    if (candAddr.includes(district)) score = Math.min(25, score + 10);
    else if (DISTRICTS.some(d => !d.equals && d !== district && candAddr.includes(d))) score = Math.max(0, score - 15);
  }
  return score;
}
function unitScore(c, input) {
  const tokens = unitTokens(input);
  if (!tokens.length) return 10;
  const hay = nvl(c.name) + ' ' + nvl(c.addr);
  for (const t of tokens) {
    const re = new RegExp('(?<![0-9A-Za-z])' + t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    if (re.test(hay)) return 20;
  }
  for (const ch of UNIT_CHARS) if (hay.includes(ch)) return 0;
  return 10;
}
function typeScore(c, input) {
  const residentialInput = RESIDENTIAL_HINT.test(input);
  const tag = tagAll(c);
  const residentialTag = tag.includes('住宅') || tag.includes('楼栋') || tag.includes('居住') || tag.includes('小区');
  if (residentialTag) return residentialInput ? 10 : 5;
  return residentialInput ? 0 : 5;
}
function score(c, input) {
  let nameS = nameScore(c.name, input);
  let addrS = addrScore(c.addr, input);
  const residentialInput = RESIDENTIAL_HINT.test(input);
  if (residentialInput && COMMERCIAL_TAG.test(tagAll(c))) { nameS *= 0.5; addrS *= 0.5; }
  const unitS = unitScore(c, input);
  const typeS = typeScore(c, input);
  const polyS = c.polygons && c.polygons.length ? 5 : 0;
  const total = nameS + addrS + unitS + typeS + polyS;
  return { c, total, detail: `名称${Math.round(nameS)}/地址${Math.round(addrS)}/楼栋${Math.round(unitS)}/类型${Math.round(typeS)}/多边形${Math.round(polyS)}=${Math.round(total)}` };
}
function scoreAll(candidates, input) {
  const scores = candidates.map(c => score(c, input));
  scores.sort((a, b) =>
    b.total - a.total ||
    ((a.c.polygons && a.c.polygons.length ? 0 : 1) - (b.c.polygons && b.c.polygons.length ? 0 : 1)) ||
    (a.c.index - b.c.index) ||
    ((b.c.name || '').length - (a.c.name || '').length));
  return scores;
}

/** 复刻 queryBest：返回 AddressResult 风格对象（单地址） */
const BOUNDS = [113.75, 22.40, 114.65, 22.88];
function processQuery(raw, input, filterFn) {
  let candidates = parseCandidates(raw);
  if (filterFn) candidates = candidates.filter(filterFn);
  const scores = scoreAll(candidates, input);
  let skipped = 0, chosen = null;
  for (const s of scores) {
    const c = s.c;
    if (c.cityName != null && c.cityName !== '' && c.cityName !== '深圳市') { skipped++; continue; }
    if (c.hasCenter) {
      const ll = mcToBd09(c.centerX, c.centerY);
      if (ll[0] < BOUNDS[0] || ll[0] > BOUNDS[2] || ll[1] < BOUNDS[1] || ll[1] > BOUNDS[3]) { skipped++; continue; }
    }
    chosen = s;
    break;
  }
  if (!chosen) return { failed: '所有候选均为市外/越界结果' };
  const c = chosen.c;
  const result = {
    selectedName: c.name,
    regionName: c.name || input,
    centerLL: mcToBd09(c.centerX, c.centerY),
    polygonLL: [],
    placeType: '未知',
    scoreDetail: `[${c.name}] ${chosen.detail}${skipped > 0 ? `（已跳过${skipped}个市外/越界候选）` : ''}`,
    status: '成功',
    message: ''
  };
  const tag = nvl(c.stdTag) || nvl(c.showTag);
  if (tag) {
    const semi = tag.lastIndexOf(';');
    result.placeType = semi >= 0 && semi < tag.length - 1 ? tag.substring(semi + 1) : tag;
  }
  if (c.polygons && c.polygons.length) {
    const poly = selectPolygon(c.polygons, c.centerX, c.centerY);
    if (poly) {
      const out = poly.map(p => mcToBd09(p[0], p[1]));
      if (out.length >= 3) {
        const f = out[0], l = out[out.length - 1];
        if (f[0] !== l[0] || f[1] !== l[1]) out.push([f[0], f[1]]);
      }
      result.polygonLL = out;
    }
  }
  if (chosen.total < 40) {
    result.status = '低置信度';
    result.message = `「${input}」最高分 ${chosen.total.toFixed(0)} 低于阈值 40`;
  }
  return result;
}

// ---------- 1. 处理两条新数据 ----------
const rXiang = processQuery(fs.readFileSync(path.join(ROOT, 'retry/raw_xiangxiang.json'), 'utf8'), '香榭名园3栋');
const rHuarun = processQuery(fs.readFileSync(path.join(ROOT, 'retry/raw_huarun_南山区华润大厦.json'), 'utf8'), '华润大厦',
  // 楼栋编码语义：仅接受房地产类 POI（排除"华润大厦艺术中心"等楼内场馆）
  c => (c.stdTag || '').startsWith('房地产'));
console.log('香榭名园3栋 ->', rXiang.failed || `${rXiang.selectedName} [${rXiang.status}] ${rXiang.scoreDetail}`);
console.log('华润大厦(南山) ->', rHuarun.failed || `${rHuarun.selectedName} [${rHuarun.status}] ${rHuarun.scoreDetail}`);
if (rXiang.failed || rHuarun.failed) { console.error('存在失败查询，中止'); process.exit(1); }

// ---------- 2. dizhi1 编码映射 ----------
const d1 = fs.readFileSync(path.join(ROOT, 'src/main/resources/dizhi1.txt'), 'utf8').split(/\r?\n/).filter(x => x).slice(1);
const rowsByAddr = new Map();
for (const line of d1) {
  const [label, district, station, addr, code] = line.split('\t');
  if (!rowsByAddr.has(addr)) rowsByAddr.set(addr, []);
  rowsByAddr.get(addr).push({ label, district, code });
}
// 重复地址的编码归属（华润大厦现有行中心点在罗湖区 -> 桂园所编码；其余重复为同所重复编码取第一条）
const DUP_PICK = { '华润大厦': a => a.find(x => x.district === '罗湖'), '阳光棕榈园31栋': a => a[0], '雅庭苑2栋B单元': a => a[0] };
function pickInfo(addr) {
  const arr = rowsByAddr.get(addr);
  if (!arr) return null;
  if (arr.length === 1) return arr[0];
  const pick = DUP_PICK[addr];
  return pick ? pick(arr) : arr[0];
}

// ---------- 3. 重写 output.txt（换编码 + 追加两行） ----------
const outLines = fs.readFileSync(path.join(ROOT, 'output.txt'), 'utf8').split(/\r?\n/).filter(x => x);
// 幂等：移除上次可能已追加的行
const pre = outLines.filter(l => !l.split('\t')[4].startsWith('香榭名园3栋') && l.split('\t')[0] !== '4403050070050500006');
const newOut = pre.map(line => {
  const c = line.split('\t');
  const info = pickInfo(c[4]);
  if (!info) { console.error('dizhi1 无此地址:', c[4]); process.exit(1); }
  return [info.code, c[1], c[2], c[3], c[4], info.label].join('\t');
});
const originalCount = pre.length;
function appendRow(res, addr, code, label) {
  const row = [code, res.regionName, centerJson(res.centerLL), polygonJson(res.polygonLL), addr, label].join('\t');
  newOut.push(row);
}
appendRow(rXiang, '香榭名园3栋', '4403040070021500007', '住宅');
appendRow(rHuarun, '华润大厦', '4403050070050500006', '商业办公');
if (!WRITE) {
  console.log(`[dry-run] output.txt 将写 ${newOut.length} 行（原 ${originalCount}）`);
} else {
  fs.writeFileSync(path.join(ROOT, 'output.txt'), newOut.join('\r\n') + '\r\n', 'utf8');
  console.log(`output.txt 已写 ${newOut.length} 行（原 ${originalCount}）`);
}

// ---------- 4. failed.txt / review.txt ----------
const failedLines = fs.readFileSync(path.join(ROOT, 'failed.txt'), 'utf8').split(/\r?\n/).filter(x => x && !x.startsWith('香榭名园3栋'));
if (WRITE) {
  const hasXiang = failedLines.some(l => l.startsWith('香榭名园3栋'));
  if (!hasXiang) {
    fs.writeFileSync(path.join(ROOT, 'failed.txt'), failedLines.join('\r\n') + '\r\n', 'utf8');
    let review = fs.readFileSync(path.join(ROOT, 'review.txt'), 'utf8');
    if (!review.endsWith('\r\n') && review.length) review += '\r\n';
    if (!review.startsWith('香榭名园3栋\t') && !review.includes('\r\n香榭名园3栋\t')) {
      const reviewAdd = ['香榭名园3栋', rXiang.selectedName, rXiang.scoreDetail, rXiang.message].join('\t');
      fs.writeFileSync(path.join(ROOT, 'review.txt'), review + reviewAdd + '\r\n', 'utf8');
    }
    console.log('failed.txt 剩', failedLines.length, '行; review.txt 已追加香榭名园3栋');
  } else {
    console.log('failed/review 上次已更新，跳过');
  }
} else {
  console.log('[dry-run] failed.txt 将剩', failedLines.length, '行');
}

// ---------- 5. map.html ----------
const htmlPath = path.join(ROOT, 'map.html');
let h = fs.readFileSync(htmlPath, 'utf8');
const m = h.match(/var REGIONS = (\[[\s\S]*?\]);\s*\r?\n/);
if (!m) { console.error('未找到 REGIONS'); process.exit(1); }
const regions = JSON.parse(m[1]);
if (regions.length !== outLines.length) { console.error(`REGIONS ${regions.length} 与原 output ${outLines.length} 不一致`); process.exit(1); }
// 原有条目补 code/typeLabel（按行序对应）
outLines.forEach((line, i) => {
  const c = line.split('\t');
  regions[i].code = c[0];
  regions[i].typeLabel = c[5];
});
// 新条目
function newRegion(res, addr, code, label) {
  return {
    regionId: code, regionName: res.regionName, inputAddress: addr, selectedName: res.selectedName,
    placeType: res.placeType, status: res.status, scoreDetail: res.scoreDetail, subQueries: res.selectedName,
    code, typeLabel: label,
    center: { lon: parseFloat(fmt14(res.centerLL[0])), lat: parseFloat(fmt14(res.centerLL[1])) },
    polygon: res.polygonLL.map(p => ({ lon: parseFloat(fmt14(p[0])), lat: parseFloat(fmt14(p[1])) }))
  };
}
regions.push(newRegion(rXiang, '香榭名园3栋', '4403040070021500007', '住宅'));
regions.push(newRegion(rHuarun, '华润大厦', '4403050070050500006', '商业办公'));
h = h.replace(m[0], 'var REGIONS = ' + JSON.stringify(regions) + ';\n');

// 5.1 列表两行样式 CSS
const cssOld = '.li{padding:5px 10px;cursor:pointer;font-size:12px;border-bottom:1px solid #f2f2f2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}';
const cssNew = '.li{padding:6px 10px;cursor:pointer;font-size:12px;border-bottom:1px solid #f2f2f2}\n'
  + '.li:hover{background:#eef5ff}\n'
  + '.li .t1{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n'
  + '.li .t2{color:#888;font-size:11px;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n'
  + '.chip{display:inline-block;padding:0 5px;border-radius:3px;font-size:10px;line-height:16px;margin-left:6px}\n'
  + '.chip.bd{background:#e6f7ee;color:#0a8f4c}\n'
  + '.chip.ct{background:#f0f0f0;color:#666}';
if (h.split(cssOld).length !== 2) { console.error('CSS 锚点未唯一命中'); process.exit(1); }
h = h.replace(cssOld, cssNew);

// 5.2 buildList 两行渲染（序号 + 原地址 / 编码 + 边界标签 + 类型标签）
const blOld = "    html += '<div class=\"li\" data-i=\"' + i + '\"><span class=\"dot\" style=\"background:' + statusColor(r.status) + '\"></span>' + esc(r.inputAddress) + '</div>';";
const blNew = "    n++;\n"
  + "    var hasBd = r.polygon && r.polygon.length >= 3;\n"
  + "    html += '<div class=\"li\" data-i=\"' + i + '\">'\n"
  + "      + '<div class=\"t1\">' + n + '. ' + esc(r.inputAddress) + '</div>'\n"
  + "      + '<div class=\"t2\">' + esc(r.code || r.regionId)\n"
  + "      + '<span class=\"chip ' + (hasBd ? 'bd\">边界' : 'ct\">仅中心') + '</span>'\n"
  + "      + ' ' + esc(r.typeLabel || r.placeType || '') + '</div></div>';";
if (h.split(blOld).length !== 2) { console.error('buildList 锚点未唯一命中'); process.exit(1); }
h = h.replace(blOld, blNew);
const cntOld = '  var kw2 = (kw || \'\').toLowerCase();';
const cntNew = '  var kw2 = (kw || \'\').toLowerCase();\n  var n = 0;';
if (h.split(cntOld).length !== 2) { console.error('计数器锚点未唯一命中'); process.exit(1); }
h = h.replace(cntOld, cntNew);

// 5.3 信息窗增加编码行
const infoOld = "    + '<b>' + esc(r.regionName) + '</b> (ID:' + r.regionId + ')<br/>'";
const infoNew = "    + '<b>' + esc(r.regionName) + '</b> (ID:' + esc(r.code || r.regionId) + ')<br/>'";
if (h.split(infoOld).length !== 2) { console.error('信息窗锚点未唯一命中'); process.exit(1); }
h = h.replace(infoOld, infoNew);

if (WRITE) {
  fs.writeFileSync(htmlPath, h, 'utf8');
  console.log('map.html 已更新');
} else {
  console.log('[dry-run] map.html 未写回');
}
