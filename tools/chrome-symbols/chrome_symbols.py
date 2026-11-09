#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chrome R8 符号定位器（ChroStar 模块升版适配用）

背景
----
Chrome 每个大版本 R8 都会重排短名（例如 TabModelSelector 在 156=`zor`、
157=`c0s`、158=`vvr`），而 LSPosed 模块靠这些短名 + 方法名做 hook。
每次 Chrome 升版都必须重新标定一张符号表，否则指纹不匹配、所有 hook 静默不装。

本脚本不靠猜短名，而是**按签名特征**在 DEX 里反查：

  TabModelSelector      → 同时有 (Z)->TabModel 和 ()->TabModel，且被
                          RecentlyClosedBridge 构造器引用
  HomepageManager       → 静态 ()->自身 + 实例 (Z,Z)->GURL
  ChromeTabCreator      → ChromeTabbedActivity 字节码里直接 invoke 的、
                          首参是 LoadUrlParams 的短名类
  DownloadMessageUiController → (OfflineItem,Z,Z,Z)
  MessageUiCoordinator  → (PropertyModel,Z) + (PropertyModel,WebContents,I,Z)
  OfflineItem 字段      → 按「名字 + 类型」双重校验，不符时给出候选
  BookmarkBridge        → 保留名类，列根 getter / j / l
  J.N native 桥         → 确认类与 OJ / VIOOOOO 等入口存在

用法
----
  # 1) 从手机拉三包（需 adb + root 可读 /data/app）
  python3 tools/chrome-symbols/chrome_symbols.py pull

  # 2) 扫描并输出报告 + 可直接落地的 Java 符号表
  python3 tools/chrome-symbols/chrome_symbols.py scan --version 157
  python3 tools/chrome-symbols/chrome_symbols.py scan --version 157 --emit-java Chrome157.java

  # 3) 崩溃栈里的混淆帧反查源码（模块自身 mapping.txt）
  python3 tools/chrome-symbols/chrome_symbols.py crash z.c \
      --mapping module/app/build/outputs/mapping/release/mapping.txt

产物
----
  work/chrome-symbols/<versionCode>/{base,split_chrome,split_on_demand}.apk
  work/chrome-symbols/<versionCode>/symbols.json      # 机器可读，便于跨版本 diff
  （work/ 已在 .gitignore 里，APK 不进 Git）
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Tuple

# ---------------------------------------------------------------------------
# DEX 解析
# ---------------------------------------------------------------------------


def _uleb(buf: bytes, i: int) -> Tuple[int, int]:
    v = 0
    shift = 0
    while True:
        c = buf[i]
        i += 1
        v |= (c & 0x7F) << shift
        if not (c & 0x80):
            break
        shift += 7
    return v, i


@dataclass
class Method:
    name: str
    ret: str
    params: List[str]
    code_off: int

    @property
    def n_params(self) -> int:
        return len(self.params)


@dataclass
class ClassInfo:
    name: str                 # Lorg/...;  或 Lc0s;
    super_name: str
    fields: List[Tuple[str, str]] = field(default_factory=list)
    methods: List[Method] = field(default_factory=list)
    dex: "Dex" = None         # 声明它的 dex（解码字节码用）

    @property
    def short(self) -> str:
        return short_type(self.name)

    def find_methods(self, name: str) -> List[Method]:
        return [m for m in self.methods if m.name == name]


class Dex:
    def __init__(self, data: bytes):
        self.d = data
        v = struct.unpack_from('<22I', data, 0x20)
        self.string_off = v[7]
        self.type_off = v[9]
        self.proto_off = v[11]
        self.field_off = v[13]
        self.method_off = v[15]
        self.class_size = v[16]
        self.class_off = v[17]

    def string(self, idx: int) -> str:
        off = struct.unpack_from('<I', self.d, self.string_off + idx * 4)[0]
        _, p = _uleb(self.d, off)
        end = self.d.index(b'\x00', p)
        return self.d[p:end].decode('utf-8', 'replace')

    def type(self, idx: int) -> str:
        if idx == 0xFFFFFFFF:
            return ''
        return self.string(struct.unpack_from('<I', self.d, self.type_off + idx * 4)[0])

    def proto(self, idx: int) -> Tuple[str, List[str]]:
        _, ret, poff = struct.unpack_from('<III', self.d, self.proto_off + idx * 12)
        params: List[str] = []
        if poff:
            n = struct.unpack_from('<I', self.d, poff)[0]
            for k in range(n):
                params.append(self.type(struct.unpack_from('<H', self.d, poff + 4 + k * 2)[0]))
        return self.type(ret), params

    def class_data(self, off: int) -> Tuple[List[Tuple[str, str]], List[Method]]:
        d = self.d
        p = off
        sf, p = _uleb(d, p)
        inf, p = _uleb(d, p)
        dm, p = _uleb(d, p)
        vm, p = _uleb(d, p)

        fields: List[Tuple[str, str]] = []
        fidx = 0
        for _ in range(sf + inf):
            diff, p = _uleb(d, p)
            fidx += diff
            _, p = _uleb(d, p)
            _, tidx, nidx = struct.unpack_from('<HHI', d, self.field_off + fidx * 8)
            fields.append((self.string(nidx), self.type(tidx)))

        methods: List[Method] = []
        for count in (dm, vm):
            midx = 0
            for _ in range(count):
                diff, p = _uleb(d, p)
                midx += diff
                _, p = _uleb(d, p)
                code, p = _uleb(d, p)
                _, pidx, nidx = struct.unpack_from('<HHI', d, self.method_off + midx * 8)
                ret, params = self.proto(pidx)
                methods.append(Method(self.string(nidx), ret, params, code))
        return fields, methods

    def classes(self) -> Iterable[ClassInfo]:
        for ci in range(self.class_size):
            base = self.class_off + ci * 32
            cidx, _acc, sup, _ifc, _src, _ann, cdata, _sv = struct.unpack_from(
                '<8I', self.d, base)
            name = self.type(cidx)
            if cdata:
                fields, methods = self.class_data(cdata)
            else:
                fields, methods = [], []
            yield ClassInfo(name, self.type(sup), fields, methods, self)


# 指令长度表（只用于顺序遍历，不做语义分析）
INSN_LEN = {op: 1 for op in range(256)}
for _op in [0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1D, 0x1E, 0x1F,
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x29, 0x2D, 0x2E, 0x2F, 0x30,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C,
            0x3D, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E,
            0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A,
            0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66,
            0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4,
            0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF, 0xE0,
            0xE1, 0xE2, 0xFE, 0xFF]:
    INSN_LEN[_op] = 2
for _op in [0x14, 0x17, 0x1B, 0x2A, 0x2B, 0x2C, 0x6E, 0x6F, 0x70, 0x71, 0x72,
            0x74, 0x75, 0x76, 0x77, 0x78, 0xFC, 0xFD]:
    INSN_LEN[_op] = 3
for _op in (0xFA, 0xFB):
    INSN_LEN[_op] = 4
INSN_LEN[0x18] = 5


def decode_method(dex: Dex, code_off: int) -> Tuple[List[Tuple[str, str, List[str]]], List[int]]:
    """返回 (invoke 目标列表[(class, name, params)], 该方法里出现的小整数常量)"""
    d = dex.d
    try:
        _rs, _ins, _outs, _tries, _dbg, isz = struct.unpack_from('<4H2I', d, code_off)
        insns = struct.unpack_from(f'<{isz}H', d, code_off + 16)
    except struct.error:
        return [], []

    invokes: List[Tuple[str, str, List[str]]] = []
    consts: List[int] = []
    i = 0
    while i < isz:
        op = insns[i] & 0xFF
        if op == 0x12:
            b = (insns[i] >> 8) & 0xF
            consts.append(b - 16 if b & 8 else b)
        elif op == 0x13:
            consts.append(insns[i + 1])
        elif op == 0x14:
            consts.append((insns[i + 2] << 16) | insns[i + 1])
        elif 0x6E <= op <= 0x72 or 0x74 <= op <= 0x78:
            mid = insns[i + 1]
            try:
                cidx, pidx, nidx = struct.unpack_from('<HHI', d, dex.method_off + mid * 8)
            except struct.error:
                i += INSN_LEN.get(op, 1)
                continue
            ret, params = dex.proto(pidx)
            invokes.append((dex.type(cidx), dex.string(nidx), params))
        i += INSN_LEN.get(op, 1)
    return invokes, consts


def short_type(t: str) -> str:
    """Lorg/chromium/url/GURL; -> GURL（不能用 strip('L;')，会削掉末尾的 L）"""
    if not t:
        return ''
    if t.startswith('L') and t.endswith(';'):
        return t[1:-1].split('/')[-1]
    return t


# ---------------------------------------------------------------------------
# APK 集合
# ---------------------------------------------------------------------------

ROOT_PKG_RE = re.compile(r'^L[A-Za-z0-9$]{1,4};$')   # R8 根包短名：Lc0s;


@dataclass
class ApkSet:
    apks: Dict[str, str]                     # name -> path
    classes: Dict[str, ClassInfo] = field(default_factory=dict)

    @classmethod
    def load(cls, paths: Dict[str, str]) -> "ApkSet":
        s = cls(paths)
        for label, path in paths.items():
            if not os.path.exists(path):
                continue
            with zipfile.ZipFile(path) as z:
                for entry in sorted(n for n in z.namelist()
                                    if n.startswith('classes') and n.endswith('.dex')):
                    try:
                        dex = Dex(z.read(entry))
                    except Exception:
                        continue
                    for ci in dex.classes():
                        s.classes.setdefault(ci.name, ci)
        return s

    def get(self, name: str) -> Optional[ClassInfo]:
        return self.classes.get(name)

    def find_by_simple_name(self, simple: str) -> List[ClassInfo]:
        return [c for c in self.classes.values() if c.short == simple]

    def iter(self) -> Iterable[ClassInfo]:
        return self.classes.values()


# ---------------------------------------------------------------------------
# 定位器
# ---------------------------------------------------------------------------


def find_selector(apks: ApkSet) -> Optional[dict]:
    """TabModelSelector：同时有 (Z)->TabModel 与 ()->TabModel"""
    best = None
    for c in apks.iter():
        if not ROOT_PKG_RE.match(c.name):
            continue
        model_methods = [m for m in c.methods
                         if m.n_params == 1 and m.params[0] == 'Z'
                         and short_type(m.ret) == 'TabModel']
        cur_model = [m for m in c.methods
                     if m.n_params == 0 and short_type(m.ret) == 'TabModel']
        cur_tab = [m for m in c.methods
                   if m.n_params == 0 and short_type(m.ret).endswith('Tab')]
        if not (model_methods and cur_model):
            continue
        score = 0
        # 交叉校验：被 RecentlyClosedBridge 构造器引用，可信度最高
        for cls in apks.iter():
            if 'RecentlyClosedBridge' in cls.name:
                for m in cls.find_methods('<init>'):
                    if c.name in m.params:
                        score += 10
        best = {
            'class': c.short,
            'get_model': model_methods[0].name,
            'get_current_model': cur_model[0].name,
            'get_current_tab': cur_tab[0].name if cur_tab else '?',
            'score': score,
        }
        if score >= 10:
            break
    return best


def find_homepage(apks: ApkSet) -> Optional[dict]:
    """HomepageManager：静态 ()->自身（单例），且实例方法 (Z,Z)->GURL"""
    for c in apks.iter():
        if not ROOT_PKG_RE.match(c.name):
            continue
        singleton = [m for m in c.methods
                     if m.n_params == 0 and m.ret == c.name]
        gurl = [m for m in c.methods
                if m.n_params == 2 and m.params == ['Z', 'Z']
                and short_type(m.ret) == 'GURL']
        if singleton and gurl:
            names = [m.name for m in gurl]
            # 156 基线用的是 b(false,false)；优先它，否则取第一个并在报告里列出候选
            chosen = 'b' if 'b' in names else names[0]
            return {'class': c.short, 'singleton': singleton[0].name,
                    'home_url': chosen, 'home_args': 2,
                    'home_candidates': names}
    return None


def find_tab_creator(apks: ApkSet) -> Optional[dict]:
    """
    ChromeTabCreator：从 ChromeTabbedActivity 的字节码里找
    「目标方法首参是 LoadUrlParams」的根包短名类。
    """
    activity = apks.get('Lorg/chromium/chrome/browser/ChromeTabbedActivity;')
    if activity is None:
        return None

    votes: Dict[str, int] = {}
    for m in activity.methods:
        if not m.code_off:
            continue
        invokes, _ = decode_method(activity.dex, m.code_off)
        for cls, _name, params in invokes:
            if not ROOT_PKG_RE.match(cls):
                continue
            if params and params[0].endswith('LoadUrlParams;'):
                votes[cls] = votes.get(cls, 0) + 1

    if not votes:
        return None
    creator_name = max(votes, key=lambda k: votes[k])
    creator = apks.get(creator_name)
    if creator is None:
        return None
    load_methods = [m.name for m in creator.methods
                    if m.n_params and m.params[0].endswith('LoadUrlParams;')]

    incognito = [c.short for c in apks.iter()
                 if c.super_name == creator_name and ROOT_PKG_RE.match(c.name)]
    return {
        'class': creator.short,
        'load_methods': sorted(set(load_methods)),
        'incognito': incognito[0] if incognito else None,
        'votes': votes[creator_name],
    }


def find_download_message(apks: ApkSet) -> Optional[dict]:
    """DownloadMessageUiController：(OfflineItem,Z,Z,Z)"""
    for c in apks.iter():
        for m in c.methods:
            if (m.n_params == 4 and short_type(m.params[0]) == 'OfflineItem'
                    and m.params[1:] == ['Z', 'Z', 'Z']):
                return {'class': c.short, 'method': m.name}
    return None


def find_message_ui(apks: ApkSet) -> Optional[dict]:
    """MessageUiCoordinator：(PropertyModel,Z) + (PropertyModel,WebContents,I,Z)"""
    for c in apks.iter():
        if not ROOT_PKG_RE.match(c.name):
            continue
        short_ok = [m for m in c.methods
                    if m.n_params == 2 and short_type(m.params[0]) == 'PropertyModel'
                    and m.params[1] == 'Z']
        long_ok = [m for m in c.methods
                   if m.n_params == 4 and short_type(m.params[0]) == 'PropertyModel'
                   and short_type(m.params[1]) == 'WebContents'
                   and m.params[2] == 'I' and m.params[3] == 'Z']
        if short_ok and long_ok:
            return {'class': c.short, 'short_method': short_ok[0].name,
                    'long_method': long_ok[0].name}
    return None


OFFLINE_ITEM = 'Lorg/chromium/components/offline_items_collection/OfflineItem;'
OFFLINE_EXPECT = {
    'state': ('s0', 'I'),
    'path': ('k0', 'Ljava/lang/String;'),
    'title': ('V', 'Ljava/lang/String;'),
    'mime': ('l0', 'Ljava/lang/String;'),
    'received': ('v0', 'J'),
    'total': ('e0', 'J'),
}


def find_offline_item(apks: ApkSet) -> dict:
    """
    OfflineItem 字段：先按基线名 + 类型双重校验；任一不符就列出候选让人确认。
    （这个类各版本字段名通常不变，但类型漂移会导致 getIntField 抛异常）
    """
    c = apks.get(OFFLINE_ITEM)
    if c is None:
        return {'found': False}
    by_name = {n: t for n, t in c.fields}
    out = {'found': True, 'ok': True, 'fields': {}, 'candidates': {}}
    for key, (name, want) in OFFLINE_EXPECT.items():
        got = by_name.get(name)
        if got == want:
            out['fields'][key] = name
        else:
            out['ok'] = False
            out['fields'][key] = None
            cand = [n for n, t in c.fields if t == want]
            out['candidates'][key] = cand
    return out


BOOKMARK_BRIDGE = 'Lorg/chromium/chrome/browser/bookmarks/BookmarkBridge;'


def find_bookmark_bridge(apks: ApkSet) -> dict:
    c = apks.get(BOOKMARK_BRIDGE)
    if c is None:
        return {'found': False}
    roots = [m.name for m in c.methods
             if m.n_params == 0 and m.ret.endswith('BookmarkId;')]
    item_getter = [m.name for m in c.methods
                   if m.n_params == 1 and m.params[0].endswith('BookmarkId;')
                   and short_type(m.ret) == 'BookmarkItem']
    children = [m.name for m in c.methods
                if m.n_params == 1 and m.params[0].endswith('BookmarkId;')
                and short_type(m.ret) == 'ArrayList']
    return {'found': True, 'roots': sorted(roots),
            'item': item_getter[0] if item_getter else None,
            'children': children[0] if children else None}


def find_native_bridge(apks: ApkSet) -> dict:
    """J.N：Chrome 的 JNI 静态入口，需要 OJ / VIOOOOO 等"""
    c = apks.get('LJ/N;')
    if c is None:
        return {'found': False}
    names = {m.name for m in c.methods}
    return {'found': True,
            'has_OJ': 'OJ' in names,
            'has_VIOOOOO': 'VIOOOOO' in names,
            'methods': len(c.methods)}


DIALOG_BRIDGES = [
    'DangerousDownloadDialogBridge',
    'InsecureDownloadDialogBridge',
    'DuplicateDownloadDialogBridge',
    'PolicyWarningDownloadDialogBridge',
    'OpenDownloadDialogBridge',
    'DownloadDialogBridge',
]


def find_dialogs(apks: ApkSet) -> dict:
    """6 个下载弹窗 Bridge 是否存在 + 它们调用 J.N 时出现的小整数（与基线对比）"""
    out = {}
    for simple in DIALOG_BRIDGES:
        hit = None
        for c in apks.iter():
            if c.short == simple:
                hit = c
                break
        if hit is None:
            out[simple] = None
            continue
        ids = set()
        for m in hit.methods:
            if not m.code_off:
                continue
            invokes, consts = decode_method(hit.dex, m.code_off)
            if any(cls == 'LJ/N;' for cls, _n, _p in invokes):
                ids.update(v for v in consts if 0 <= v <= 200)
        out[simple] = sorted(ids)
    return out


def find_recently_closed(apks: ApkSet) -> List[dict]:
    hits = []
    for c in apks.iter():
        if 'RecentlyClosedBridge' in c.name:
            ctors = [m.params for m in c.find_methods('<init>')]
            hits.append({'class': c.name.strip('L;').replace('/', '.'),
                         'ctor_params': [[short_type(p) for p in ps] for ps in ctors]})
    return hits


# ---------------------------------------------------------------------------
# 报告 / 模板
# ---------------------------------------------------------------------------


def scan(workdir: str, version: str, emit_java: Optional[str]) -> int:
    apks = ApkSet.load({
        'base': os.path.join(workdir, 'base.apk'),
        'split_chrome': os.path.join(workdir, 'split_chrome.apk'),
        'split_on_demand': os.path.join(workdir, 'split_on_demand.apk'),
    })
    if not apks.classes:
        print(f'[x] {workdir} 下没有可读的 apk，先跑 pull', file=sys.stderr)
        return 2

    result = {
        'version': version,
        'selector': find_selector(apks),
        'homepage': find_homepage(apks),
        'tab_creator': find_tab_creator(apks),
        'download_message': find_download_message(apks),
        'message_ui': find_message_ui(apks),
        'offline_item': find_offline_item(apks),
        'bookmark_bridge': find_bookmark_bridge(apks),
        'native_bridge': find_native_bridge(apks),
        'dialogs': find_dialogs(apks),
        'recently_closed': find_recently_closed(apks),
        'classes_total': len(apks.classes),
    }

    print(f'=== Chrome {version} 符号定位报告 ===')
    print(f'类总数: {result["classes_total"]}')

    def show(key: str, label: str):
        v = result[key]
        print(f'\n[{label}]')
        if not v:
            print('  !! 未命中（该版本可能改了签名，需要人工复核）')
            return
        for k, val in v.items():
            print(f'  {k:20s} {val}')

    show('selector', 'TabModelSelector')
    show('homepage', 'HomepageManager')
    show('tab_creator', 'ChromeTabCreator')
    show('download_message', 'DownloadMessageUiController')
    show('message_ui', 'MessageUiCoordinator')

    oi = result['offline_item']
    print('\n[OfflineItem 字段]')
    if not oi.get('found'):
        print('  !! 类不存在（包名可能又搬了）')
    elif oi['ok']:
        print('  OK 与基线一致:', oi['fields'])
    else:
        print('  !! 与基线不一致，需人工确认：')
        for k, v in oi['fields'].items():
            print(f'    {k:10s} {v}   候选={oi["candidates"].get(k)}')

    bb = result['bookmark_bridge']
    print('\n[BookmarkBridge]')
    if bb.get('found'):
        print(f'  根 getter({len(bb["roots"])}): {bb["roots"]}')
        print(f'  item={bb["item"]}  children={bb["children"]}')
    else:
        print('  !! 未找到')

    nb = result['native_bridge']
    print('\n[J.N native 桥]')
    print(f'  found={nb.get("found")} OJ={nb.get("has_OJ")} VIOOOOO={nb.get("has_VIOOOOO")}')

    print('\n[下载弹窗 Bridge]')
    for k, v in result['dialogs'].items():
        print(f'  {k:32s} {"缺失!" if v is None else v}')

    print('\n[RecentlyClosedBridge]')
    for r in result['recently_closed']:
        print(f'  {r["class"]}  ctor={r["ctor_params"]}')

    os.makedirs(workdir, exist_ok=True)
    with open(os.path.join(workdir, 'symbols.json'), 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f'\n[ok] 结果已写入 {os.path.join(workdir, "symbols.json")}')

    if emit_java:
        java = render_java(result)
        with open(emit_java, 'w', encoding='utf-8') as f:
            f.write(java)
        print(f'[ok] Java 符号表已写入 {emit_java}')
    return 0 if (result['selector'] and result['tab_creator']) else 1


def render_java(r: dict) -> str:
    ver = r['version']
    sel = r['selector'] or {}
    home = r['homepage'] or {}
    creator = r['tab_creator'] or {}
    dm = r['download_message'] or {}
    mui = r['message_ui'] or {}
    oi = r['offline_item']
    note = '' if oi.get('ok') else '\n    // !! OfflineItem 字段与基线不一致，先按 report 里的候选人工确认'
    fields = '\n'.join(
        f'    public static final String OI_{k.upper()} = "{v}";'
        for k, v in oi.get('fields', {}).items() if v) if oi.get('found') else ''

    return f'''// ChroStar — Chrome {ver} 符号表
// 由 tools/chrome-symbols/chrome_symbols.py 生成（按签名特征定位，非猜测）
// 生成后请人工复核带 !! 的项，再编译到真机验证指纹日志：
//   "split ClassLoader acquired ... -> Chrome {ver} fingerprint OK"
package io.github.ylw6669.chrostar;

public final class Chrome{ver} {{

    public static final String SELECTOR = "{sel.get('class', '?')}";
    public static final String SELECTOR_GET_MODEL = "{sel.get('get_model', '?')}";
    public static final String SELECTOR_GET_CURRENT_MODEL = "{sel.get('get_current_model', '?')}";
    public static final String SELECTOR_GET_TAB = "{sel.get('get_current_tab', '?')}";

    public static final String HOME_MGR = "{home.get('class', '?')}";

    public static final String TAB_CREATOR = "{creator.get('class', '?')}";
    public static final String TAB_CREATOR_INCOGNITO = "{creator.get('incognito') or ''}";

    public static final String DOWNLOAD_MESSAGE = "{dm.get('class', '?')}";
    public static final String MESSAGE_UI = "{mui.get('class', '?')}";
{note}
{fields}

    private Chrome{ver}() {{
    }}
}}
'''


# ---------------------------------------------------------------------------
# adb pull
# ---------------------------------------------------------------------------


def sh(cmd: List[str], **kw) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout


def pull(pkg: str, workdir: Optional[str]) -> int:
    try:
        paths = sh(['adb', 'shell', 'pm', 'path', pkg]).strip().splitlines()
    except Exception as e:
        print(f'[x] adb 调用失败: {e}', file=sys.stderr)
        return 2
    apk_paths = [p.split(':', 1)[1] for p in paths if p.startswith('package:')]
    if not apk_paths:
        print('[x] 手机上没找到该包', file=sys.stderr)
        return 2

    try:
        dump = sh(['adb', 'shell', 'dumpsys', 'package', pkg])
        m = re.search(r'versionName=([^\s]+)', dump)
        version = m.group(1) if m else 'unknown'
        m2 = re.search(r'versionCode=(\d+)', dump)
        code = m2.group(1) if m2 else version
    except Exception:
        version, code = 'unknown', 'unknown'

    workdir = workdir or os.path.join('work', 'chrome-symbols', code)
    os.makedirs(workdir, exist_ok=True)
    print(f'Chrome {version} (versionCode {code}) -> {workdir}')

    wanted = {'base.apk', 'split_chrome.apk', 'split_on_demand.apk'}
    got = []
    for remote in apk_paths:
        name = os.path.basename(remote)
        if name not in wanted:
            continue
        dst = os.path.join(workdir, name)
        print(f'  pull {name} ...', flush=True)
        subprocess.run(['adb', 'pull', remote, dst], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if os.path.exists(dst):
            got.append(name)

    with open(os.path.join(workdir, 'meta.json'), 'w', encoding='utf-8') as f:
        json.dump({'version': version, 'versionCode': code, 'apks': got},
                  f, ensure_ascii=False, indent=2)
    print(f'[ok] 已拉取 {len(got)} 个包: {", ".join(got)}')
    print(f'下一步: python3 tools/chrome-symbols/chrome_symbols.py scan '
          f'--version {version.split(".")[0]} --workdir {workdir}')
    return 0


# ---------------------------------------------------------------------------
# 崩溃帧反查
# ---------------------------------------------------------------------------


def crash(symbol: str, mapping_path: str) -> int:
    if not os.path.exists(mapping_path):
        print(f'[x] mapping 文件不存在: {mapping_path}', file=sys.stderr)
        return 2

    cls_part, _, meth_part = symbol.partition('.')
    mapped_classes: Dict[str, Tuple[str, List[str]]] = {}
    current_orig = None
    current_obf = None
    with open(mapping_path, encoding='utf-8') as f:
        for line in f:
            if not line.startswith(' ') and ' -> ' in line and line.rstrip().endswith(':'):
                orig, obf = line.rstrip()[:-1].split(' -> ')
                current_orig, current_obf = orig, obf
                mapped_classes[obf] = (orig, [])
            elif line.startswith('    ') and current_obf and ' -> ' in line:
                m = re.search(r'(?:[\d:]+:)?[^(]*?([\w$<>]+)\([^)]*\)(?::\d+:\d+)? -> (\w+)$',
                              line.rstrip())
                if m:
                    mapped_classes[current_obf][1].append(f'{m.group(1)} -> {m.group(2)}')

    key = f'io.github.ylw6669.chrostar.{cls_part}'
    if key not in mapped_classes:
        print(f'[x] mapping 里没有 {key}', file=sys.stderr)
        return 1
    orig, methods = mapped_classes[key]
    print(f'{symbol}  =>  {orig}.{meth_part}')
    if meth_part:
        hits = []
        for x in methods:
            if x.endswith(f'-> {meth_part}'):
                orig_name = x.split(' -> ')[0]
                if orig_name not in hits:
                    hits.append(orig_name)
        if hits:
            print('  候选原方法:')
            for h in hits:
                print('   ', h)
        else:
            print('  (未在方法映射里找到，可能是内联/合成方法)')
    return 0


# ---------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description='Chrome R8 符号定位器（ChroStar 升版适配）')
    sub = ap.add_subparsers(dest='cmd', required=True)

    p_pull = sub.add_parser('pull', help='从手机拉 base/split_chrome/split_on_demand')
    p_pull.add_argument('--package', default='com.android.chrome')
    p_pull.add_argument('--workdir')

    p_scan = sub.add_parser('scan', help='扫描并输出符号报告 / Java 模板')
    p_scan.add_argument('--workdir', default='work/chrome-symbols/latest')
    p_scan.add_argument('--version', required=True, help='大版本号，用于命名 ChromeNNN.java')
    p_scan.add_argument('--emit-java', help='输出 Java 符号表到该路径')

    p_crash = sub.add_parser('crash', help='用 mapping.txt 反查崩溃栈里的混淆帧')
    p_crash.add_argument('symbol', help='形如 z.c（类.方法）')
    p_crash.add_argument('--mapping',
                         default='module/app/build/outputs/mapping/release/mapping.txt')

    args = ap.parse_args()
    if args.cmd == 'pull':
        return pull(args.package, args.workdir)
    if args.cmd == 'scan':
        return scan(args.workdir, args.version, args.emit_java)
    if args.cmd == 'crash':
        return crash(args.symbol, args.mapping)
    return 2


if __name__ == '__main__':
    sys.exit(main())
