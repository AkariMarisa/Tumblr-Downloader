#!/usr/bin/env python3
"""Generate 3 interface-revamp concept mockups as SVG (2 phone screens each)."""
import math

SANS = "Noto Sans, Noto Sans CJK SC, DejaVu Sans, sans-serif"
MONO = "Noto Sans Mono, DejaVu Sans Mono, monospace"
W, H = 770, 830          # artboard
PH_W, PH_H = 330, 710    # phone frame
SCR_W, SCR_H = 312, 692  # screen inside frame (inset 9)
PAD = 9

defs = []

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def T(x, y, s, size=14, fill="#000", w=400, anchor="start", family=None, ls=None):
    family = family or SANS
    attrs = f' x="{x}" y="{y}" font-size="{size}" fill="{fill}" font-weight="{w}"'
    attrs += f' text-anchor="{anchor}" font-family="{family}"'
    if ls: attrs += f' letter-spacing="{ls}"'
    return f"<text{attrs}>{esc(s)}</text>"

def R(x, y, w, h, rx=0, fill="#fff", stroke=None, sw=1, op=None):
    a = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}"'
    if stroke: a += f' stroke="{stroke}" stroke-width="{sw}"'
    if op is not None: a += f' opacity="{op}"'
    return a + "/>"

def L(x1, y1, x2, y2, stroke="#000", sw=1, op=None):
    a = f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" stroke-width="{sw}"'
    if op is not None: a += f' opacity="{op}"'
    return a + "/>"

def C(cx, cy, r, fill="#000", stroke=None, sw=1):
    a = f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"'
    if stroke: a += f' stroke="{stroke}" stroke-width="{sw}"'
    return a + "/>"

# ---------- icons (all vector, no emoji) ----------
def ic_download(cx, cy, c, sw=2):
    return (f'<g stroke="{c}" stroke-width="{sw}" stroke-linecap="round" fill="none">'
            f'<path d="M{cx},{cy-9} V{cy+1}"/>'
            f'<path d="M{cx-5},{cy-3} L{cx},{cy+3} L{cx+5},{cy-3}"/>'
            f'<path d="M{cx-7},{cy+7} H{cx+7}"/></g>')

def ic_list(cx, cy, c, sw=2):
    return (f'<g stroke="{c}" stroke-width="{sw}" stroke-linecap="round">'
            f'<path d="M{cx-8},{cy-6} H{cx+8}"/><path d="M{cx-8},{cy} H{cx+8}"/>'
            f'<path d="M{cx-8},{cy+6} H{cx+8}"/></g>')

def ic_gear(cx, cy, c, sw=1.8):
    g = [C(cx, cy, 7, "none", c, sw), C(cx, cy, 2.6, c)]
    for k in range(8):
        a = math.radians(k * 45)
        x1, y1 = cx + 7.2 * math.cos(a), cy + 7.2 * math.sin(a)
        x2, y2 = cx + 10.2 * math.cos(a), cy + 10.2 * math.sin(a)
        g.append(L(round(x1, 1), round(y1, 1), round(x2, 1), round(y2, 1), c, sw))
    return f'<g stroke-linecap="round">{"".join(g)}</g>'

def ic_link(cx, cy, c, sw=1.7):
    return (f'<g transform="rotate(-45 {cx} {cy})" fill="none" stroke="{c}" stroke-width="{sw}">'
            f'<rect x="{cx-10}" y="{cy-4}" width="13" height="8" rx="4"/>'
            f'<rect x="{cx-3}" y="{cy-4}" width="13" height="8" rx="4"/></g>')

def ic_signal(cx, cy, c, sw=2):
    return (f'<g fill="none" stroke="{c}" stroke-width="{sw}" stroke-linecap="round">'
            f'<path d="M{cx-5},{cy+2} A 7 7 0 0 1 {cx+5},{cy+2}"/>'
            f'<path d="M{cx-9},{cy+4} A 12.5 12.5 0 0 1 {cx+9},{cy+4}"/></g>'
            f'{C(cx, cy+4, 2, c)}')

def ic_pause(cx, cy, c):
    return (f'<g fill="{c}"><rect x="{cx-5}" y="{cy-6}" width="3.4" height="12" rx="1.4"/>'
            f'<rect x="{cx+1.6}" y="{cy-6}" width="3.4" height="12" rx="1.4"/></g>')

def ic_x(cx, cy, c, sw=2):
    return (f'<g stroke="{c}" stroke-width="{sw}" stroke-linecap="round">'
            f'<path d="M{cx-5},{cy-5} L{cx+5},{cy+5}"/><path d="M{cx+5},{cy-5} L{cx-5},{cy+5}"/></g>')

# ---------- shared chrome ----------
def statusbar(color):
    return (T(22, 27, "9:41", 12, color, 700)
            + f'<g fill="{color}">'
            + R(240, 21, 3, 5, 1, color) + R(245, 18, 3, 8, 1, color) + R(250, 15, 3, 11, 1, color)
            + f'<rect x="264" y="16" width="20" height="11" rx="3.5" fill="none" stroke="{color}" stroke-width="1.5"/>'
            + R(266.5, 18.5, 11, 6, 1.5, color) + "</g>")

def nav_light(active, accent="#B02E5C", pill="#FCE7EF"):
    """Bottom nav for concepts A: white bar, pill behind active icon."""
    items = [(52, ic_download, "Download"), (156, ic_list, "Downloads"), (260, ic_gear, "Settings")]
    out = [R(0, 628, 312, 64, 0, "#fff"), L(0, 628, 312, 628, "#F0E8ED", 1)]
    for cx, ico, lab in items:
        on = lab == active
        c = accent if on else "#7A7480"
        if on:
            out.append(R(cx - 26, 636, 52, 32, 16, pill))
        out.append(ico(cx, 652, c, 1.9))
        out.append(T(cx, 684, lab, 10.5, c, 700, "middle"))
    return "".join(out)

def nav_glass(active, grad="url(#gBtnH)"):
    """Floating glass pill nav for concept B."""
    items = [(66, ic_download, "Download"), (156, ic_list, "Downloads"), (246, ic_gear, "Settings")]
    out = [R(18, 624, 276, 58, 29, "#FFFFFF", "#FFFFFF", 1),
           ]
    out[0] = '<rect x="18" y="624" width="276" height="58" rx="29" fill="#FFFFFF" opacity="0.08" stroke="#FFFFFF" stroke-opacity="0.15" stroke-width="1"/>'
    for cx, ico, lab in items:
        on = lab == active
        if on:
            out.append(C(cx, 646, 17, grad))
            out.append(ico(cx, 646, "#fff", 1.9))
            out.append(T(cx, 675, lab, 10, "#fff", 700, "middle"))
        else:
            out.append(ico(cx, 646, "#8E93A3", 1.9))
            out.append(T(cx, 675, lab, 10, "#8E93A3", 700, "middle"))
    return "".join(out)

def phone(px, py, cid, inner, dark=False):
    bg = "#0B0E14" if dark else "#ffffff"
    # screen inset PAD, frame rx 42 → screen/clip rx must be 42-PAD=33 (concentric),
    # and the clip rect must be offset by PAD like the bg — a (0,0) clip used to cut
    # 9px off the right/bottom and double-cut the top-left corner.
    defs.append(f'<clipPath id="{cid}"><rect x="{PAD}" y="{PAD}" width="{SCR_W}" height="{SCR_H}" rx="33"/></clipPath>')
    return (f'<g transform="translate({px},{py})">'
            + R(0, 0, PH_W, PH_H, 42, "#0d0d0d")
            + R(PAD, PAD, SCR_W, SCR_H, 33, bg)
            + f'<g clip-path="url(#{cid})"><g transform="translate({PAD},{PAD})">{inner}</g></g>'
            + '</g>')

def task_row_light(y, thumb, name, sub, bar_pct, chip_txt, chip_fill, chip_text, running=True):
    o = [R(18, y, 276, 76, 18, "#fff", "#F2E4EA", 1.5),
         R(30, y + 11, 54, 54, 14, f"url(#{thumb})"),
         T(96, y + 28, name, 12.5, "#1D1B20", 700),
         T(96, y + 45, sub, 10.5, "#9A939E")]
    o.append(R(96, y + 53, 140, 5, 3, "#F3EDF1"))
    if bar_pct > 0:
        o.append(R(96, y + 53, round(140 * bar_pct), 5, 3, "url(#gP)"))
    cw = 8 + len(chip_txt) * 6.2
    o.append(R(282 - cw, y + 24, cw, 24, 12, chip_fill))
    o.append(T(282 - cw / 2, y + 40, chip_txt, 10.5, chip_text, 800, "middle"))
    return "".join(o)

def task_row_dark(y, thumb, name, sub, bar_pct, chip_txt, chip_fill, chip_text):
    o = ['<rect x="18" y="%d" width="276" height="76" rx="18" fill="#FFFFFF" opacity="0.06" stroke="#FFFFFF" stroke-opacity="0.13" stroke-width="1"/>' % y,
         R(30, y + 11, 54, 54, 14, f"url(#{thumb})"),
         T(96, y + 28, name, 12.5, "#EDEEF2", 700),
         T(96, y + 45, sub, 10.5, "#6E7486")]
    o.append(R(96, y + 53, 140, 5, 3, "#FFFFFF", None, 1, 0.1))
    if bar_pct > 0:
        o.append(R(96, y + 53, round(140 * bar_pct), 5, 3, "url(#gBtnH)"))
    cw = 10 + len(chip_txt) * 6.4
    o.append(f'<rect x="{282-cw}" y="{y+24}" width="{cw}" height="24" rx="12" fill="{chip_fill}"/>')
    o.append(T(282 - cw / 2, y + 40, chip_txt, 10.5, chip_text, 800, "middle"))
    return "".join(o)

# ================= SCREEN BUILDERS =================

def a_home():
    o = [statusbar("#1D1B20"),
         T(18, 80, "Download", 30, "#1D1B20", 800),
         C(274, 68, 17, "#FCE7EF"), T(274, 73, "A", 14, "#D93F72", 800, "middle"),
         R(18, 98, 276, 62, 24, "#fff", "#F2E4EA", 1.5),
         ic_link(44, 129, "#D93F72"),
         T(64, 134, "Paste post or blog link", 14, "#8A8490"),
         R(18, 174, 276, 52, 26, "url(#gBtnV)"),
         T(156, 207, "Download", 16.5, "#fff", 800, "middle"),
         R(18, 240, 133, 42, 15, "#FCE7EF"), T(84.5, 266, "Blog scan", 13, "#B02E5C", 700, "middle"),
         R(161, 240, 133, 42, 15, "#FCE7EF"), T(227.5, 266, "Batch download", 13, "#B02E5C", 700, "middle"),
         T(18, 312, "RECENT", 11, "#9A939E", 800, ls=1.4)]
    recs = [("gF1", "kthingz.tumblr.com", "42 media · last scan yesterday"),
            ("gF2", "qqchurch.tumblr.com", "128 media · last scan 3 days ago"),
            ("gF3", "shoujo-drops.tumblr.com", "310 media · last scan last week"),
            ("gF4", "akarimarisa.tumblr.com", "12 media · not scanned yet")]
    y = 326
    for fav, t1, t2 in recs:
        o += [C(36, y + 22, 16, f"url(#{fav})"),
              T(62, y + 19, t1, 13.5, "#1D1B20", 700),
              T(62, y + 37, t2, 10.5, "#9A939E"),
              L(18, y + 52, 294, y + 52, "#F3EDF1", 1)]
        y += 52
    o.append(T(156, 566, "Clipboard links are detected automatically", 11, "#B4ADB9", 400, "middle"))
    o.append(nav_light("Download"))
    return "".join(o)

def a_downloads():
    o = [statusbar("#1D1B20"),
         T(18, 80, "Downloads", 30, "#1D1B20", 800),
         C(274, 68, 17, "#FCE7EF"), T(274, 73, "A", 14, "#D93F72", 800, "middle"),
         R(18, 98, 276, 152, 24, "#fff", "#F2E4EA", 1.5),
         C(50, 130, 21, "url(#gP)"),
         T(80, 126, "blogname.tumblr.com", 14, "#1D1B20", 800),
         T(80, 145, "8,596 posts scanned · 214 media", 10.5, "#9A939E"),
         R(32, 172, 248, 10, 5, "#FCE7EF"),
         R(32, 172, 154, 10, 5, "url(#gP)"),
         R(32, 196, 118, 38, 19, "#FCE7EF"), T(91, 220, "Pause", 14, "#B02E5C", 800, "middle"),
         R(162, 196, 118, 38, 19, "none", "#E7D9E1", 1.5), T(221, 220, "Cancel", 14, "#7A7480", 800, "middle")]
    rows = [("gT1", "qqchurch-81ba2f.mov", "Downloading · 4.2 MB/s", 0.64, "64%", "#FCE7EF", "#B02E5C"),
            ("gT2", "kthingz-a91c7d.jpg", "2.1 MB · 1440×2160", 1.0, "Done", "#DCF5E4", "#1B7A3F"),
            ("gT3", "kthingz-5f3e1a.mp4", "8.6 MB · queued", 0.0, "Queued", "#F3EDF1", "#7A7480"),
            ("gT1", "shoujo-77aa1c.png", "5.8 MB · queued", 0.0, "Queued", "#F3EDF1", "#7A7480")]
    y = 266
    for th, nm, sb, pct, chip, cf, ct in rows:
        o.append(task_row_light(y, th, nm, sb, pct, chip, cf, ct))
        y += 88
    o.append(nav_light("Downloads"))
    return "".join(o)

def b_home():
    o = [blobs(False),
         statusbar("#EDEEF2"),
         T(18, 80, "Download", 30, "#EDEEF2", 800),
         C(274, 68, 17, "#FFFFFF"), T(274, 73, "A", 14, "#0B0E14", 800, "middle"),
         '<rect x="18" y="98" width="276" height="62" rx="24" fill="#FFFFFF" opacity="0.07" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>',
         ic_link(44, 129, "#FF8FAC"),
         T(64, 134, "Paste post or blog link", 14, "#8E93A3"),
         R(18, 174, 276, 52, 26, "url(#gBtnH)"),
         T(156, 207, "Download", 16.5, "#fff", 800, "middle"),
         '<rect x="18" y="240" width="133" height="42" rx="15" fill="#FFFFFF" opacity="0.07" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>',
         T(84.5, 266, "Blog scan", 13, "#CDD1DC", 700, "middle"),
         '<rect x="161" y="240" width="133" height="42" rx="15" fill="#FFFFFF" opacity="0.07" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>',
         T(227.5, 266, "Batch download", 13, "#CDD1DC", 700, "middle"),
         T(18, 312, "RECENT", 11, "#6E7486", 800, ls=1.4)]
    recs = [("gF1", "kthingz.tumblr.com", "42 media · last scan yesterday"),
            ("gF2", "qqchurch.tumblr.com", "128 media · last scan 3 days ago"),
            ("gF3", "shoujo-drops.tumblr.com", "310 media · last scan last week"),
            ("gF4", "akarimarisa.tumblr.com", "12 media · not scanned yet")]
    y = 326
    for fav, t1, t2 in recs:
        o += [C(36, y + 22, 16, f"url(#{fav})"),
              T(62, y + 19, t1, 13.5, "#EDEEF2", 700),
              T(62, y + 37, t2, 10.5, "#6E7486"),
              L(18, y + 52, 294, y + 52, "#FFFFFF", 1, 0.07)]
        y += 52
    o.append(T(156, 556, "Clipboard links are detected automatically", 11, "#565B6B", 400, "middle"))
    o.append(nav_glass("Download"))
    return "".join(o)

GLASS = "#FFFFFF"          # glass fill base (light variant)
GLASS_STROKE = "#E7E0EF"   # hairline edge on light bg

def glass(x, y, w, h, rx, op=0.72, shadow=True):
    f = ' filter="url(#soft)"' if shadow else ""
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="#FFFFFF" fill-opacity="{op}" stroke="{GLASS_STROKE}" '
            f'stroke-width="1"{f}/>')

def nav_glass_light(active):
    items = [(66, ic_download, "Download"), (156, ic_list, "Downloads"), (246, ic_gear, "Settings")]
    out = [f'<rect x="18" y="624" width="276" height="58" rx="29" fill="#FFFFFF" '
           f'fill-opacity="0.88" stroke="{GLASS_STROKE}" stroke-width="1" filter="url(#soft)"/>']
    for cx, ico, lab in items:
        on = lab == active
        if on:
            out.append(C(cx, 646, 17, "url(#gBtnH)"))
            out.append(ico(cx, 646, "#fff", 1.9))
            out.append(T(cx, 675, lab, 10, "#B02E5C", 800, "middle"))
        else:
            out.append(ico(cx, 646, "#9A939E", 1.9))
            out.append(T(cx, 675, lab, 10, "#9A939E", 700, "middle"))
    return "".join(out)

def bl_home():
    """Aurora Glass — LIGHT variant, home screen."""
    o = [blobs(True),
         statusbar("#1D1B20"),
         T(18, 80, "Download", 30, "#1D1B20", 800),
         C(274, 68, 17, "#FFFFFF"), T(274, 73, "A", 14, "#D93F72", 800, "middle"),
         glass(18, 98, 276, 62, 24, 0.75),
         ic_link(44, 129, "#D93F72"),
         T(64, 134, "Paste post or blog link", 14, "#8A8490"),
         R(18, 174, 276, 52, 26, "url(#gBtnH)"),
         T(156, 207, "Download", 16.5, "#fff", 800, "middle"),
         glass(18, 240, 133, 42, 15, 0.66, shadow=False),
         T(84.5, 266, "Blog scan", 13, "#5D5670", 700, "middle"),
         glass(161, 240, 133, 42, 15, 0.66, shadow=False),
         T(227.5, 266, "Batch download", 13, "#5D5670", 700, "middle"),
         T(18, 312, "RECENT", 11, "#9A939E", 800, ls=1.4)]
    recs = [("gF1", "kthingz.tumblr.com", "42 media · last scan yesterday"),
            ("gF2", "qqchurch.tumblr.com", "128 media · last scan 3 days ago"),
            ("gF3", "shoujo-drops.tumblr.com", "310 media · last scan last week"),
            ("gF4", "akarimarisa.tumblr.com", "12 media · not scanned yet")]
    y = 326
    for fav, t1, t2 in recs:
        o += [C(36, y + 22, 16, f"url(#{fav})"),
              T(62, y + 19, t1, 13.5, "#1D1B20", 700),
              T(62, y + 37, t2, 10.5, "#9A939E"),
              L(18, y + 52, 294, y + 52, "#EFE9F4", 1)]
        y += 52
    o.append(T(156, 556, "Clipboard links are detected automatically", 11, "#A79FB5", 400, "middle"))
    o.append(nav_glass_light("Download"))
    return "".join(o)

def bl_downloads():
    """Aurora Glass — LIGHT variant, downloads list."""
    o = [blobs(True),
         statusbar("#1D1B20"),
         T(18, 80, "Downloads", 30, "#1D1B20", 800),
         C(274, 68, 17, "#FFFFFF"), T(274, 73, "A", 14, "#D93F72", 800, "middle"),
         glass(18, 98, 276, 132, 24, 0.78),
         f'<circle cx="66" cy="164" r="34" fill="none" stroke="#EDE7F3" stroke-width="9"/>',
         f'<circle cx="66" cy="164" r="34" fill="none" stroke="url(#gBtnH)" stroke-width="9" stroke-linecap="round"'
         f' stroke-dasharray="132.4 213.6" transform="rotate(-90 66 164)"/>',
         T(66, 170, "62%", 15, "#1D1B20", 800, "middle"),
         T(118, 140, "blogname.tumblr.com", 13.5, "#1D1B20", 800),
         T(118, 159, "8,596 posts · 214 media", 10.5, "#9A939E"),
         R(118, 174, 84, 30, 15, "url(#gBtnH)"),
         T(160, 194, "Pause", 12.5, "#fff", 800, "middle"),
         f'<rect x="210" y="174" width="72" height="30" rx="15" fill="#FFFFFF" fill-opacity="0.7" '
         f'stroke="{GLASS_STROKE}" stroke-width="1"/>',
         T(246, 194, "Cancel", 12.5, "#5D5670", 800, "middle")]
    rows = [("gT1", "qqchurch-81ba2f.mov", "Downloading · 4.2 MB/s", 0.64, "64%", "#FCE7EF", "#B02E5C", 0.78),
            ("gT2", "kthingz-a91c7d.jpg", "2.1 MB · 1440×2160", 1.0, "Done", "#DCF5E4", "#1B7A3F", 0.78),
            ("gT3", "kthingz-5f3e1a.mp4", "8.6 MB · queued", 0.0, "Queued", "#F1EEF5", "#7A7480", 0.7)]
    y = 246
    for th, nm, sb, pct, chip, cf, ct, op in rows:
        o.append(task_row_glass(y, th, nm, sb, pct, chip, cf, ct, op))
        y += 88
    o.append(T(156, 528, "Tasks pause on network loss · resume on reconnect", 11, "#A79FB5", 400, "middle"))
    o.append(nav_glass_light("Downloads"))
    return "".join(o)

def task_row_glass(y, thumb, name, sub, bar_pct, chip_txt, chip_fill, chip_text, op):
    o = [glass(18, y, 276, 76, 18, op),
         R(30, y + 11, 54, 54, 14, f"url(#{thumb})"),
         T(96, y + 28, name, 12.5, "#1D1B20", 700),
         T(96, y + 45, sub, 10.5, "#9A939E"),
         R(96, y + 53, 140, 5, 3, "#EEE9F3")]
    if bar_pct > 0:
        o.append(R(96, y + 53, round(140 * bar_pct), 5, 3, "url(#gP)"))
    cw = 10 + len(chip_txt) * 6.4
    o.append(R(282 - cw, y + 24, cw, 24, 12, chip_fill))
    o.append(T(282 - cw / 2, y + 40, chip_txt, 10.5, chip_text, 800, "middle"))
    return "".join(o)

def b_downloads():
    o = [blobs(False),
         statusbar("#EDEEF2"),
         T(18, 80, "Downloads", 30, "#EDEEF2", 800),
         C(274, 68, 17, "#FFFFFF"), T(274, 73, "A", 14, "#0B0E14", 800, "middle"),
         '<rect x="18" y="98" width="276" height="132" rx="24" fill="#FFFFFF" opacity="0.07" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>',
         f'<circle cx="66" cy="164" r="34" fill="none" stroke="#FFFFFF" stroke-opacity="0.12" stroke-width="9"/>',
         f'<circle cx="66" cy="164" r="34" fill="none" stroke="url(#gBtnH)" stroke-width="9" stroke-linecap="round"'
         f' stroke-dasharray="132.4 213.6" transform="rotate(-90 66 164)"/>',
         T(66, 170, "62%", 15, "#EDEEF2", 800, "middle"),
         T(118, 140, "blogname.tumblr.com", 13.5, "#EDEEF2", 800),
         T(118, 159, "8,596 posts · 214 media", 10.5, "#8E93A3"),
         R(118, 174, 84, 30, 15, "url(#gBtnH)"),
         T(160, 194, "Pause", 12.5, "#fff", 800, "middle"),
         R(210, 174, 72, 30, 15, "none", "#FFFFFF", 1),
         '<rect x="210" y="174" width="72" height="30" rx="15" fill="none" stroke="#FFFFFF" stroke-opacity="0.25" stroke-width="1"/>',
         T(246, 194, "Cancel", 12.5, "#CDD1DC", 800, "middle")]
    rows = [("gT1", "qqchurch-81ba2f.mov", "Downloading · 4.2 MB/s", 0.64, "64%", "#FF5C8A", "#0B0E14"),
            ("gT2", "kthingz-a91c7d.jpg", "2.1 MB · 1440×2160", 1.0, "Done", "rgba(52,199,116,0.18)", "#4ADE80"),
            ("gT3", "kthingz-5f3e1a.mp4", "8.6 MB · queued", 0.0, "Queued", "#FFFFFF", "#8E93A3")]
    y = 246
    for th, nm, sb, pct, chip, cf, ct in rows:
        blk = task_row_dark(y, th, nm, sb, pct, chip, cf, ct)
        if chip == "Queued":
            blk = blk.replace(f'<rect x="{282-(10+len(chip)*6.4)}" y="{y+24}" width="{10+len(chip)*6.4}" height="24" rx="12" fill="#FFFFFF"/>',
                              f'<rect x="{282-(10+len(chip)*6.4)}" y="{y+24}" width="{10+len(chip)*6.4}" height="24" rx="12" fill="#FFFFFF" fill-opacity="0.10"/>')
        o.append(blk)
        y += 88
    o.append(T(156, 528, "Tasks pause on network loss · resume on reconnect", 11, "#565B6B", 400, "middle"))
    o.append(nav_glass("Downloads"))
    return "".join(o)

def c_shell(active_tab):
    tabs = [("Download", 18), ("Downloads", 112)]
    o = [statusbar("#17171A"),
         T(18, 56, "Tumblr Downloader", 14.5, "#17171A", 800),
         ic_gear(283, 50, "#6B6B70", 1.6),
         L(0, 96, 312, 96, "#E4E2DC", 1)]
    for name, x in tabs:
        if name == active_tab:
            o.append(T(x, 88, name, 14, "#17171A", 800))
            o.append(R(x, 94, len(name) * 7.9, 2.5, 1, "#26487A"))
        else:
            o.append(T(x, 88, name, 14, "#8B8B90", 600))
    return "".join(o)

def c_cell(x, y, w, h):
    return R(x, y, w, h, 16, "#fff", "#E4E2DC", 1)

def c_home():
    o = [c_shell("Download"), '<g transform="translate(0,26)">']
    # cell 1: paste link (wide)
    o += [c_cell(18, 84, 276, 126),
          T(32, 108, "PASTE LINK", 10.5, "#8B8B90", 800, ls=1.3),
          ic_link(44, 134, "#26487A"),
          T(62, 139, "https://…/post/1234", 13, "#6B6B70"),
          L(32, 150, 278, 150, "#17171A", 1.5),
          R(32, 160, 246, 36, 10, "#17171A"),
          T(155, 183, "GO", 14, "#fff", 800, "middle", ls=1)]
    # row 2: blog scan + queue
    o += [c_cell(18, 220, 133, 96),
          T(32, 244, "BLOG SCAN", 10.5, "#8B8B90", 800, ls=1.3),
          ic_signal(44, 274, "#26487A"),
          T(66, 279, "New scan", 13, "#17171A", 700),
          T(66, 297, "whole blog", 10.5, "#8B8B90"),
          c_cell(159, 220, 135, 96),
          T(173, 244, "QUEUE", 10.5, "#8B8B90", 800, ls=1.3),
          T(173, 288, "12", 34, "#17171A", 700, family=MONO),
          T(221, 288, "items", 12, "#8B8B90", 600),
          T(173, 306, "waiting", 10.5, "#8B8B90")]
    # cell 3: storage (wide)
    o += [c_cell(18, 326, 276, 106),
          T(32, 350, "STORAGE", 10.5, "#8B8B90", 800, ls=1.3),
          T(32, 388, "4.2", 32, "#17171A", 700, family=MONO),
          T(92, 388, "GB used", 13, "#8B8B90", 600),
          R(32, 400, 246, 6, 3, "#E4E2DC"), R(32, 400, 34, 6, 3, "#26487A"),
          T(32, 424, "1,204 files · 186 GB free", 11, "#6B6B70", 400, family=MONO)]
    # cell 4: recent scans (wide)
    o += [c_cell(18, 442, 276, 118),
          T(32, 466, "RECENT SCANS", 10.5, "#8B8B90", 800, ls=1.3),
          C(45, 490, 13, "url(#gF1)"),
          T(66, 488, "kthingz.tumblr.com", 12.5, "#17171A", 700),
          T(66, 505, "42 media · yesterday", 10.5, "#6B6B70", 400, family=MONO),
          L(32, 517, 278, 517, "#E4E2DC", 1),
          C(45, 542, 13, "url(#gF2)"),
          T(66, 540, "qqchurch.tumblr.com", 12.5, "#17171A", 700),
          T(66, 557, "128 media · 3 days ago", 10.5, "#6B6B70", 400, family=MONO)]
    # cell 5: tip (wide)
    o += [c_cell(18, 570, 276, 88),
          T(32, 594, "SHORTCUT", 10.5, "#8B8B90", 800, ls=1.3),
          T(32, 618, "Clipboard links are detected automatically —", 12, "#17171A"),
          T(32, 638, "just open the app and press GO.", 12, "#17171A")]
    o.append("</g>")
    return "".join(o)

def c_downloads():
    o = [c_shell("Downloads"), '<g transform="translate(0,26)">']
    # scan module
    o += [c_cell(18, 84, 276, 134),
          T(32, 108, "SCANNING", 10.5, "#8B8B90", 800, ls=1.3),
          T(32, 158, "62%", 42, "#17171A", 700, family=MONO),
          T(208, 112, "Pause", 13, "#26487A", 800, "middle"),
          T(262, 112, "Cancel", 13, "#8B8B90", 800, "middle"),
          R(32, 176, 246, 3, 1.5, "#E4E2DC"), R(32, 176, 152, 3, 1.5, "#26487A"),
          T(32, 202, "blogname.tumblr.com · 8,596 posts", 11, "#6B6B70", 400, family=MONO)]
    # in progress
    o += [T(18, 248, "IN PROGRESS", 10.5, "#8B8B90", 800, ls=1.3),
          R(18, 260, 276, 58, 0, "none"),
          R(18, 264, 44, 44, 8, "#E9E7E1"),
          R(24, 272, 32, 28, 4, "url(#gT1)"),
          T(74, 284, "qqchurch-81ba2f.mov", 13, "#17171A", 700),
          T(74, 302, "12.4 MB · 4.2 MB/s", 10.5, "#6B6B70", 400, family=MONO),
          T(294, 284, "64%", 13, "#26487A", 700, "end", family=MONO),
          R(230, 294, 64, 3, 1.5, "#E4E2DC"), R(230, 294, 41, 3, 1.5, "#26487A"),
          L(18, 318, 294, 318, "#E4E2DC", 1)]
    # completed
    o += [T(18, 346, "COMPLETED", 10.5, "#8B8B90", 800, ls=1.3)]
    done = [("kthingz-a91c7d.jpg", "2.1 MB · 1440×2160", "gT2"),
            ("kthingz-5f3e1a.mp4", "8.6 MB · 1920×1080", "gT3"),
            ("shoujo-77aa1c.png", "5.8 MB · 1080×1350", "gT4")]
    y = 358
    for nm, sz, th in done:
        o += [R(18, y, 44, 44, 8, "#E9E7E1"),
              R(24, y + 8, 32, 28, 4, f"url(#{th})"),
              T(74, y + 20, nm, 13, "#17171A", 700),
              T(74, y + 38, sz, 10.5, "#6B6B70", 400, family=MONO),
              T(294, y + 20, "DONE", 12, "#1B7A3F", 700, "end", family=MONO),
              L(18, y + 56, 294, y + 56, "#E4E2DC", 1)]
        y += 58
    # queued
    o += [T(18, y + 26, "QUEUED", 10.5, "#8B8B90", 800, ls=1.3),
          R(18, y + 38, 44, 44, 8, "#E9E7E1"),
          T(74, y + 58, "kthingz-b2c9d4.gif", 13, "#17171A", 700),
          T(74, y + 76, "3.2 MB", 10.5, "#6B6B70", 400, family=MONO),
          T(294, y + 58, "WAIT", 12, "#8B8B90", 700, "end", family=MONO)]
    o.append("</g>")
    return "".join(o)

# ================= REMAINING SCREENS (dark/light pairs) =================
def tk(light):
    return {
        "ink":  "#1D1B20" if light else "#EDEEF2",
        "sub":  "#9A939E" if light else "#6E7486",
        "body": "#5F5866" if light else "#A9AEBD",
        "soft": "#A79FB5" if light else "#565B6B",
        "hint": "#8A8490" if light else "#8E93A3",
        "div":  "#EFE9F4" if light else "#FFFFFF",
        "divop": 1 if light else 0.07,
        "accent": "#B02E5C" if light else "#FF8FAC",
        "green": "#1B7A3F" if light else "#4ADE80",
    }

def panel(x, y, w, h, rx, light, op=None, shadow=None):
    if light:
        op = 0.75 if op is None else op
        sh = ' filter="url(#soft)"' if (True if shadow is None else shadow) else ""
        return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="#FFFFFF" '
                f'fill-opacity="{op}" stroke="{GLASS_STROKE}" stroke-width="1"{sh}/>')
    op = 0.07 if op is None else op
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="#FFFFFF" '
            f'fill-opacity="{op}" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>')

def blobs(light):
    s = 1.0 if light else 1.05
    return (f'<circle cx="300" cy="42" r="{int(228*s)}" fill="url(#blobV)"/>'
            f'<circle cx="10" cy="702" r="{int(228*s)}" fill="url(#blobP)"/>')

def ic_back(cx, cy, c, sw=2.2):
    return (f'<path d="M{cx+4},{cy-7} L{cx-3},{cy} L{cx+4},{cy+7}" fill="none" stroke="{c}" '
            f'stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/>')

def ic_chev_r(cx, cy, c, sw=1.8):
    return (f'<path d="M{cx-2},{cy-5} L{cx+3},{cy} L{cx-2},{cy+5}" fill="none" stroke="{c}" '
            f'stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/>')

def ic_image(cx, cy, c, sw=1.9):
    return (f'<g fill="none" stroke="{c}" stroke-width="{sw}" stroke-linecap="round">'
            f'<rect x="{cx-8}" y="{cy-6.5}" width="16" height="13" rx="3"/>'
            f'<circle cx="{cx+3}" cy="{cy-2.5}" r="2"/></g>'
            f'<path d="M{cx-8},{cy+5} L{cx-2},{cy-0.5} L{cx+2},{cy+3} L{cx+5},{cy+1} L{cx+8},{cy+4.5}" '
            f'fill="none" stroke="{c}" stroke-width="{sw}" stroke-linejoin="round"/>')

def ic_theme(cx, cy, c, sw=1.9):
    r = 7.5
    return (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{c}" stroke-width="{sw}"/>'
            f'<path d="M{cx},{cy-r} A {r},{r} 0 0 1 {cx},{cy+r} Z" fill="{c}"/>')

def ic_check(cx, cy, c="#fff", sw=2.2):
    return (f'<path d="M{cx-4},{cy} L{cx-1.2},{cy+3.2} L{cx+4.5},{cy-3.5}" fill="none" stroke="{c}" '
            f'stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/>')

def ic_play(cx, cy, c):
    return f'<path d="M{cx-4},{cy-6} L{cx+6},{cy} L{cx-4},{cy+6} Z" fill="{c}"/>'

def segmented_theme(y, light):
    """Theme selector: System / Light / Dark — active mirrors the variant."""
    t = tk(light)
    active = "Light" if light else "Dark"
    o = [T(34, y + 28, "Theme", 14.5, t["ink"], 700),
         R(34, y + 44, 244, 38, 19, "#000000" if light else "#FFFFFF",
           None, 1, 0.06 if light else 0.10)]
    segs = ["System", "Light", "Dark"]
    for i, s in enumerate(segs):
        sx = 38 + i * 80
        if s == active:
            o.append(R(sx, y + 48, 76, 30, 15, "url(#gBtnH)"))
            o.append(T(sx + 38, y + 68, s, 11.5, "#fff", 800, "middle"))
        else:
            o.append(T(sx + 38, y + 68, s, 11.5, t["sub"], 600, "middle"))
    return "".join(o)

def scr_settings(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         ic_back(28, 66, t["ink"]),
         T(18, 116, "Settings", 28, t["ink"], 800)]
    rows = [("Download", "Directory · speed · clipboard", "gT1", "dl"),
            ("Media quality", "Image and video rendition", "gT2", "img"),
            ("Blog scan", "Request interval · long-blog", "gT3", "sig"),
            ("Interface", "Language and theme", "gF4", "thm")]
    y = 140
    for title, summ, grad, ic in rows:
        o.append(panel(18, y, 276, 76, 20, light))
        o.append(R(34, y + 16, 44, 44, 14, f"url(#{grad})"))
        if ic == "dl":   o.append(ic_download(56, y + 38, "#fff", 1.9))
        elif ic == "img": o.append(ic_image(56, y + 38, "#fff"))
        elif ic == "sig": o.append(ic_signal(56, y + 38, "#fff", 1.9))
        else:             o.append(ic_theme(56, y + 38, "#fff"))
        o += [T(92, y + 34, title, 14.5, t["ink"], 700),
              T(92, y + 55, summ, 11, t["sub"]),
              ic_chev_r(274, y + 38, t["hint"])]
        y += 88
    o.append(panel(18, y, 276, 100, 20, light))
    o.append(segmented_theme(y, light))
    o.append(T(156, y + 152, "Tumblr Downloader 1.0.8", 11.5, t["soft"], 400, "middle", family=MONO))
    return "".join(o)

def scr_picker(light):
    t = tk(light)
    # ghost of the home screen underneath
    o = ['<g opacity="0.45">', blobs(light), statusbar(t["ink"]),
         T(18, 80, "Download", 30, t["ink"], 800),
         panel(18, 98, 276, 62, 24, light, shadow=False),
         R(18, 174, 276, 52, 26, "url(#gBtnH)"),
         T(156, 207, "Download", 16.5, "#fff", 800, "middle"),
         panel(18, 240, 133, 42, 15, light, shadow=False),
         panel(161, 240, 133, 42, 15, light, shadow=False), "</g>",
         R(0, 0, 312, 692, 0, "#06070D" if not light else "#EFE9F7", None, 1, 0.60 if not light else 0.70)]
    # bottom sheet
    sheet_fill = "#12151D" if not light else "#FFFFFF"
    sheet_op = "0.97" if not light else "0.985"
    stroke = "#FFFFFF" if not light else GLASS_STROKE
    sop = "0.12" if not light else "1"
    o.append(f'<rect x="-4" y="168" width="320" height="560" rx="30" fill="{sheet_fill}" '
             f'fill-opacity="{sheet_op}" stroke="{stroke}" stroke-opacity="{sop}" stroke-width="1"/>')
    o += [R(136, 182, 40, 4, 2, "#000000" if light else "#FFFFFF", None, 1, 0.18 if light else 0.3),
          T(18, 220, "Choose what to download", 19, t["ink"], 800),
          T(18, 244, "3 of 5 selected", 12.5, t["sub"])]
    # filter chips
    chips = [("Images", 18, 84, True), ("Videos", 108, 76, False), ("GIFs", 190, 62, False)]
    for lab, cx, cw, on in chips:
        if on:
            o.append(R(cx, 258, cw, 34, 17, "url(#gBtnH)"))
            o.append(T(cx + cw / 2, 280, lab, 12.5, "#fff", 800, "middle"))
        else:
            o.append(panel(cx, 258, cw, 34, 17, light, op=None if light else 0.10, shadow=False))
            o.append(T(cx + cw / 2, 280, lab, 12.5, t["hint"], 700, "middle"))
    # grid 2×2
    cells = [("img-a91c7d.jpg", "2.1 MB", "gT1", True),
             ("img-5f3e1a.png", "5.8 MB", "gT2", True),
             ("vid-81ba2f.mov", "12.4 MB", "gT3", False),
             ("gif-b2c9d4.gif", "3.2 MB", "gT4", True)]
    for i, (nm, sz, grad, sel) in enumerate(cells):
        cx = 18 + (i % 2) * 142
        cy = 304 + (i // 2) * 114
        o.append(panel(cx, cy, 134, 106, 16, light, op=0.9 if light else 0.10, shadow=False))
        o.append(R(cx + 8, cy + 8, 118, 60, 8, f"url(#{grad})"))
        ccx, ccy = cx + 112, cy + 22
        if sel:
            o.append(C(ccx, ccy, 11, "url(#gBtnH)"))
            o.append(ic_check(ccx, ccy))
        else:
            o.append(C(ccx, ccy, 11, "#FFFFFF" if light else "#12151D",
                       "#D9D3E2" if light else "#FFFFFF", 1.6))
            if not light:
                o[-1] = C(ccx, ccy, 11, "#12151D", "#FFFFFF", 1.6)
        o += [T(cx + 8, cy + 86, nm, 11, t["ink"], 700),
              T(cx + 8, cy + 100, sz, 10, t["sub"], 400, family=MONO)]
    # select row + footer
    o += [T(18, 552, "Select all", 13, t["accent"], 800),
          T(104, 552, "Clear", 13, t["sub"], 700),
          L(18, 572, 294, 572, t["div"], 1, t["divop"]),
          panel(18, 590, 110, 52, 26, light, shadow=False),
          T(73, 622, "Skip", 15, t["ink"], 800, "middle"),
          R(140, 590, 154, 52, 26, "url(#gBtnH)"),
          T(217, 622, "Download (3)", 14.5, "#fff", 800, "middle")]
    return "".join(o)

def scr_blog(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         ic_back(28, 66, t["ink"]),
         T(48, 71, "blogname.tumblr.com", 15, t["ink"], 700),
         T(18, 108, "214 media · 8,596 posts scanned", 12, t["sub"]),
         panel(18, 122, 276, 72, 20, light),
         T(32, 150, "SCANNING", 11, t["sub"], 800, ls=1.3),
         T(280, 152, "62%", 15, t["ink"], 800, "end", family=MONO),
         R(32, 162, 248, 8, 4, "#EDE7F3" if light else "#FFFFFF", None, 1, 0.12 if not light else 1),
         R(32, 162, 154, 8, 4, "url(#gBtnH)"),
         panel(18, 206, 134, 44, 22, light, shadow=False),
         ic_pause(48, 228, t["ink"]),
         T(66, 233, "Pause all", 13.5, t["ink"], 700),
         panel(160, 206, 134, 44, 22, light, op=0.45 if light else 0.05, shadow=False),
         ic_play(190, 228, t["hint"]),
         T(208, 233, "Resume all", 13.5, t["hint"], 700),
         T(18, 278, "FILES · 214", 11, t["sub"], 800, ls=1.3)]
    files = [("kthingz-a91c7d.jpg", "2.1 MB · 1440×2160", "DONE", "gT2", 1.0, "green"),
             ("qqchurch-81ba2f.mov", "12.4 MB · 4.2 MB/s", "64%", "gT1", 0.64, "run"),
             ("kthingz-5f3e1a.mp4", "8.6 MB · 1920×1080", "DONE", "gT3", 1.0, "green"),
             ("shoujo-77aa1c.png", "5.8 MB · 1080×1350", "DONE", "gT4", 1.0, "green"),
             ("kthingz-b2c9d4.gif", "3.2 MB", "WAIT", "gF3", 0.0, "grey"),
             ("qqchurch-9de21f.mp4", "22.1 MB", "WAIT", "gF4", 0.0, "grey")]
    y = 292
    for nm, sz, st, grad, pct, kind in files:
        o += [R(18, y + 4, 44, 44, 10, f"url(#{grad})"),
              T(74, y + 24, nm, 13, t["ink"], 700),
              T(74, y + 41, sz, 10.5, t["sub"], 400, family=MONO)]
        col = t["green"] if kind == "green" else (t["accent"] if kind == "run" else t["hint"])
        o.append(T(294, y + 24, st, 12 if st != "64%" else 13, col, 700, "end",
                   family=MONO if st != "WAIT" else SANS))
        if kind == "run":
            o += [R(230, y + 34, 64, 3, 1.5, "#EDE7F3" if light else "#FFFFFF", None, 1,
                    1 if light else 0.12),
                  R(230, y + 34, 41, 3, 1.5, "url(#gBtnH)")]
        o.append(L(18, y + 56, 294, y + 56, t["div"], 1, t["divop"]))
        y += 60
    return "".join(o)

def scr_onboarding(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         T(294, 70, "Skip", 13, t["sub"], 600, "end"),
         panel(18, 96, 276, 244, 28, light, op=0.6 if light else 0.06),
         C(156, 218, 60, "url(#gBtnH)"),
         f'<g transform="translate(156,218) scale(2.4)">{ic_link(0, 0, "#fff", 1.5)}</g>',
         C(62, 142, 11, "url(#gT1)"),
         C(248, 300, 15, "url(#gT3)"),
         R(232, 124, 40, 40, 13, "#FFFFFF" if light else "#FFFFFF", None, 1, 0.5 if light else 0.10),
         ic_image(252, 144, "#D93F72" if light else "#FF8FAC", 1.7),
         R(44, 276, 44, 44, 13, "#FFFFFF" if light else "#FFFFFF", None, 1, 0.5 if light else 0.10),
         ic_download(66, 298, "#D93F72" if light else "#FF8FAC", 1.8),
         T(156, 402, "Paste a link to start", 24, t["ink"], 800, "middle"),
         T(156, 438, "Copy a Tumblr post link — the app detects", 13, t["body"], 400, "middle"),
         T(156, 460, "it automatically and queues the download.", 13, t["body"], 400, "middle"),
         T(156, 482, "You can also paste or share links manually.", 13, t["body"], 400, "middle")]
    # page dots (page 1 active)
    o.append(R(118, 524, 20, 8, 4, "url(#gBtnH)"))
    dot_fill = "#D9D3E2" if light else "#FFFFFF"
    dot_op = 1 if light else 0.28
    for i in range(4):
        o.append(C(150 + i * 14, 528, 4, dot_fill, None, 1))
        if dot_op != 1:
            o[-1] = o[-1].replace("/>", f' opacity="{dot_op}"/>')
    o += [R(18, 572, 276, 54, 27, "url(#gBtnH)"),
          T(156, 605, "Get started", 16.5, "#fff", 800, "middle")]
    return "".join(o)

# ================= REMAINING SCREENS PART 2 =================
def sw_toggle(x, y, on, light):
    if on:
        o = R(x, y, 46, 28, 14, "url(#gBtnH)")
        o += C(x + 32, y + 14, 11, "#ffffff")
    else:
        fill = "#D9D3E2" if light else "#FFFFFF"
        o = R(x, y, 46, 28, 14, fill, None, 1, 1 if light else 0.25)
        o += C(x + 14, y + 14, 11, "#ffffff")
    return o

def ic_lock(cx, cy, c, sw=1.7):
    return (f'<path d="M{cx-5},{cy-2} v-4 a5,5 0 0 1 10,0 v4" fill="none" stroke="{c}" '
            f'stroke-width="{sw}" stroke-linecap="round"/>'
            + R(cx - 7, cy - 2, 14, 11, 3, c))

def scr_settings_detail(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         ic_back(28, 66, t["ink"]),
         T(18, 116, "Download", 28, t["ink"], 800)]
    # switch rows
    o.append(panel(18, 140, 276, 92, 20, light))
    o += [T(32, 172, "Auto-detect clipboard", 14.5, t["ink"], 700),
          T(32, 196, "Parse Tumblr links when you", 11, t["sub"]),
          T(32, 212, "switch back to the app", 11, t["sub"]),
          sw_toggle(232, 168, True, light)]
    o.append(panel(18, 244, 276, 106, 20, light))
    o += [T(32, 276, "Auto-download all media", 14.5, t["ink"], 700),
          T(32, 300, "On: queue everything right away.", 11, t["sub"]),
          T(32, 316, "Off (default): the media picker", 11, t["sub"]),
          T(32, 332, "opens first", 11, t["sub"]),
          sw_toggle(232, 272, False, light)]
    # slider row
    o.append(panel(18, 362, 276, 92, 20, light))
    o += [T(32, 394, "Max concurrent downloads", 14.5, t["ink"], 700),
          T(278, 394, "3", 15, t["ink"], 800, "end", family=MONO),
          R(40, 424, 232, 4, 2, "#E4DEEC" if light else "#FFFFFF", None, 1, 1 if light else 0.18),
          R(40, 424, 96, 4, 2, "url(#gBtnH)")]
    o.append(f'<circle cx="136" cy="426" r="10" fill="#FFFFFF" stroke="url(#gBtnH)" stroke-width="3"/>')
    # value row + destructive row
    o.append(panel(18, 466, 276, 66, 20, light))
    o += [T(32, 505, "Download speed limit", 14.5, t["ink"], 700),
          T(252, 505, "Unlimited", 13, t["sub"], 600, "end"),
          ic_chev_r(274, 500, t["hint"])]
    o.append(panel(18, 544, 276, 66, 20, light))
    o += [T(32, 583, "Clear auto-detect cache", 14.5, t["accent"], 700),
          ic_chev_r(274, 578, t["hint"])]
    return "".join(o)

def scr_about(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         ic_back(28, 66, t["ink"]),
         R(114, 104, 84, 84, 26, "url(#gBtnH)"),
         f'<g transform="translate(156,146) scale(2.1)">{ic_download(0, 0, "#fff", 1.7)}</g>',
         T(156, 240, "Tumblr Downloader", 24, t["ink"], 800, "middle"),
         T(156, 276, "A lightweight Tumblr media downloader.", 13, t["body"], 400, "middle"),
         T(156, 298, "Quickly download images and videos from", 13, t["body"], 400, "middle"),
         T(156, 320, "posts via share links or pasted URLs.", 13, t["body"], 400, "middle")]
    # version pill
    vw = 176
    o += [panel((312 - vw) // 2, 348, vw, 36, 18, light, shadow=False),
          T(156, 371, "Version 1.0.8 (9)", 13, t["sub"], 700, "middle", family=MONO)]
    # link chips
    chips = [("GitHub", 32, 80), ("F-Droid", 122, 80), ("Ko-fi", 212, 68)]
    for lab, cx, cw in chips:
        o.append(panel(cx, 412, cw, 44, 22, light, shadow=False))
        o.append(T(cx + cw / 2, 440, lab, 13.5, t["ink"], 700, "middle"))
    # support card
    o.append(panel(18, 484, 276, 116, 20, light))
    o += [T(156, 516, "If this app helps you, consider", 13, t["body"], 400, "middle"),
          T(156, 538, "supporting development on Ko-fi", 13, t["body"], 400, "middle"),
          R(56, 554, 200, 40, 20, "url(#gBtnH)"),
          T(156, 579, "Support on Ko-fi", 13.5, "#fff", 800, "middle")]
    o.append(T(156, 640, "GPL-3.0 · Open source", 11.5, t["soft"], 400, "middle", family=MONO))
    return "".join(o)

def viewer_shell(fn, count):
    t = tk("#EDEEF2")
    o = ['<rect width="312" height="692" fill="#060608"/>',
         f'<g opacity="0.75">{statusbar("#EDEEF2")}</g>',
         ic_back(26, 66, "#FFFFFF"),
         T(48, 71, fn, 14, "#FFFFFF", 700),
         T(294, 71, count, 13, "#FFFFFF", 600, "end", family=MONO)]
    return o

def scr_viewer_image(light=False):
    o = viewer_shell("kthingz-a91c7d.jpg", "3 / 12")
    o += [R(30, 116, 252, 378, 18, "url(#gT1)", "#FFFFFF", 1),
          ]
    o[-1] = '<rect x="30" y="116" width="252" height="378" rx="18" fill="url(#gT1)" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>'
    o += [T(156, 528, "Pinch to zoom · Double tap to fit", 11.5, "#FFFFFF", 400, "middle"),
          '<rect x="96" y="568" width="120" height="48" rx="24" fill="#FFFFFF" fill-opacity="0.14" stroke="#FFFFFF" stroke-opacity="0.22" stroke-width="1"/>',
          T(156, 598, "Back", 15, "#FFFFFF", 800, "middle")]
    return "".join(o)

def scr_viewer_video(light=False):
    o = viewer_shell("qqchurch-81ba2f.mov", "1 / 4")
    o.append('<rect x="60" y="120" width="192" height="342" rx="18" fill="url(#gT3)" stroke="#FFFFFF" stroke-opacity="0.14" stroke-width="1"/>')
    # center play (paused state)
    o += ['<circle cx="156" cy="285" r="34" fill="#060608" fill-opacity="0.55"/>',
          '<path d="M147,271 L176,285 L147,299 Z" fill="#FFFFFF"/>']
    # seek bar inside frame bottom
    o += ['<rect x="76" y="432" width="160" height="4" rx="2" fill="#FFFFFF" fill-opacity="0.30"/>',
          '<rect x="76" y="432" width="104" height="4" rx="2" fill="#FFFFFF" fill-opacity="0.55"/>',
          '<rect x="76" y="432" width="68" height="4" rx="2" fill="url(#gBtnH)"/>',
          '<circle cx="144" cy="434" r="7" fill="#FFFFFF"/>']
    # times + info
    o += [T(60, 496, "1:24", 13, "#FFFFFF", 700, "start", family=MONO),
          T(252, 496, "3:42", 13, "#FFFFFF", 600, "end", family=MONO),
          panel(44, 520, 224, 40, 20, False, op=0.14),
          T(156, 545, "12.4 MB · 1920×1080 · H.264", 12.5, "#FFFFFF", 600, "middle", family=MONO),
          '<rect x="96" y="592" width="120" height="48" rx="24" fill="#FFFFFF" fill-opacity="0.14" stroke="#FFFFFF" stroke-opacity="0.22" stroke-width="1"/>',
          T(156, 622, "Back", 15, "#FFFFFF", 800, "middle")]
    return "".join(o)

def scr_login(light):
    t = tk(light)
    o = [blobs(light), statusbar(t["ink"]),
         T(18, 72, "Login and return to", 12.5, t["sub"], 600),
         T(18, 90, "continue parsing", 12.5, t["sub"], 600),
         R(210, 58, 84, 44, 22, "url(#gBtnH)"),
         T(252, 86, "Done", 14.5, "#fff", 800, "middle")]
    # webview card (Tumblr's own page)
    dark_web = not light
    web_bg = "#04182E" if dark_web else "#FFFFFF"
    web_ink = "#FFFFFF" if dark_web else "#15202B"
    web_sub = "#7A8CA3" if dark_web else "#657786"
    web_field = "#0B2540" if dark_web else "#F4F6F8"
    web_border = "#1D3A5C" if dark_web else "#E4E8EC"
    web_btn_bg = "#FFFFFF" if dark_web else "#001935"
    web_btn_ink = "#001935" if dark_web else "#FFFFFF"
    o.append(f'<rect x="8" y="122" width="296" height="566" rx="24" fill="{web_bg}" '
             f'stroke="{GLASS_STROKE if dark_web else "#EDE7F3"}" stroke-opacity="1" stroke-width="1"/>')
    cx0 = 32   # inner left
    cw0 = 248
    o += [T(156, 194, "tumblr.", 34, web_ink, 800, "middle"),
          T(156, 254, "Log in", 19, web_ink, 800, "middle"),
          R(cx0, 278, cw0, 46, 12, web_field, web_border, 1.4),
          T(cx0 + 16, 307, "Email or username", 14, web_sub),
          R(cx0, 336, cw0, 46, 12, web_field, web_border, 1.4),
          T(cx0 + 16, 365, "Password", 14, web_sub),
          T(cx0 + cw0 - 16, 365, "Show", 13, "#36465D" if dark_web else "#001935", 700, "end"),
          R(cx0, 400, cw0, 48, 24, web_btn_bg),
          T(156, 430, "Log in", 15, web_btn_ink, 800, "middle"),
          L(cx0, 478, cx0 + 108, 478, web_border, 1.4),
          L(cx0 + 140, 478, cx0 + cw0, 478, web_border, 1.4),
          T(156, 482, "or", 12.5, web_sub, 600, "middle"),
          R(cx0, 500, 120, 44, 22, web_field, web_border, 1.4),
          T(cx0 + 60, 528, "Google", 13.5, web_ink, 700, "middle"),
          R(cx0 + 128, 500, 120, 44, 22, web_field, web_border, 1.4),
          T(cx0 + 188, 528, "Apple", 13.5, web_ink, 700, "middle"),
          T(156, 584, "Don't have an account?", 13, web_sub, 400, "middle"),
          T(156, 606, "Sign up", 13.5, "#36465D" if dark_web else "#001935", 800, "middle"),
          T(156, 656, "By continuing you agree to Tumblr's", 10.5, web_sub, 400, "middle"),
          T(156, 670, "Terms of Service and Privacy Policy", 10.5, web_sub, 400, "middle")]
    return "".join(o)

def scr_filter(light):
    t = tk(light)
    # ghost downloads screen
    o = ['<g opacity="0.55">', blobs(light), statusbar(t["ink"]),
         T(18, 80, "Downloads", 30, t["ink"], 800),
         panel(18, 98, 276, 152, 24, light, shadow=False),
         C(50, 130, 21, "url(#gP)"),
         T(80, 126, "blogname.tumblr.com", 14, t["ink"], 700),
         R(32, 172, 248, 10, 5, "#E4DEEC" if light else "#FFFFFF", None, 1, 1 if light else 0.2),
         R(32, 172, 154, 10, 5, "url(#gP)"),
         panel(18, 266, 276, 76, 18, light, shadow=False),
         R(30, 277, 54, 54, 14, "url(#gT1)"), "</g>",
         R(0, 0, 312, 692, 0, "#06070D" if not light else "#EFE9F7", None, 1, 0.50 if not light else 0.64)]
    # dialog
    fill = "#151821" if not light else "#FFFFFF"
    stroke = "#FFFFFF" if not light else GLASS_STROKE
    sop = "0.14" if not light else "1"
    o.append(f'<rect x="24" y="196" width="264" height="304" rx="28" fill="{fill}" '
             f'fill-opacity="0.98" stroke="{stroke}" stroke-opacity="{sop}" stroke-width="1" filter="url(#soft)"/>')
    o.append(T(48, 244, "Download", 19, t["ink"], 800))
    rows = [("Images and videos", "214", True), ("Images only", "148", False), ("Videos only", "66", False)]
    y = 286
    for lab, cnt, sel in rows:
        cy = y + 16
        if sel:
            o.append(f'<circle cx="62" cy="{cy}" r="9.5" fill="none" stroke="url(#gBtnH)" stroke-width="2.4"/>')
            o.append(C(62, cy, 4.5, "url(#gBtnH)"))
        else:
            o.append(C(62, cy, 9.5, "none", "#B7AFBF" if not light else "#C9C2D3", 2))
        o += [T(84, cy + 5, lab, 14, t["ink"], 600 if not sel else 700),
              T(264, cy + 5, cnt, 13, t["sub"], 600, "end", family=MONO)]
        y += 52
    o += [panel(48, 436, 100, 44, 22, light, shadow=False),
          T(98, 464, "Cancel", 14, t["ink"], 800, "middle"),
          R(160, 436, 104, 44, 22, "url(#gBtnH)"),
          T(212, 464, "Start", 14, "#fff", 800, "middle")]
    return "".join(o)

# ================= ASSEMBLY =================
GRADS = """
<linearGradient id="gP" x1="0" y1="0" x2="1" y2="0">
  <stop offset="0" stop-color="#D93F72"/><stop offset="1" stop-color="#8B5CF6"/></linearGradient>
<linearGradient id="gBtnV" x1="0" y1="0" x2="0" y2="1">
  <stop offset="0" stop-color="#CE3A6B"/><stop offset="1" stop-color="#D93F72"/></linearGradient>
<linearGradient id="gBtnH" x1="0" y1="0" x2="1" y2="0">
  <stop offset="0" stop-color="#FF5C8A"/><stop offset="1" stop-color="#8B5CF6"/></linearGradient>
<linearGradient id="gT1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFD1E3"/><stop offset="1" stop-color="#FF5C8A"/></linearGradient>
<linearGradient id="gT2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#D1E3FF"/><stop offset="1" stop-color="#8B5CF6"/></linearGradient>
<linearGradient id="gT3" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFE2C7"/><stop offset="1" stop-color="#F59E0B"/></linearGradient>
<linearGradient id="gT4" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#D1F5E4"/><stop offset="1" stop-color="#22C55E"/></linearGradient>
<linearGradient id="gF1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFB199"/><stop offset="1" stop-color="#FF5C8A"/></linearGradient>
<linearGradient id="gF2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#A18CD1"/><stop offset="1" stop-color="#8B5CF6"/></linearGradient>
<linearGradient id="gF3" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#99F5D6"/><stop offset="1" stop-color="#22C55E"/></linearGradient>
<linearGradient id="gF4" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFE7A0"/><stop offset="1" stop-color="#F59E0B"/></linearGradient>
<radialGradient id="blobV">
  <stop offset="0" stop-color="#8B5CF6" stop-opacity="0.36"/>
  <stop offset="0.45" stop-color="#8B5CF6" stop-opacity="0.17"/>
  <stop offset="0.75" stop-color="#8B5CF6" stop-opacity="0.05"/>
  <stop offset="1" stop-color="#8B5CF6" stop-opacity="0"/></radialGradient>
<radialGradient id="blobP">
  <stop offset="0" stop-color="#FF5C8A" stop-opacity="0.28"/>
  <stop offset="0.45" stop-color="#FF5C8A" stop-opacity="0.13"/>
  <stop offset="0.75" stop-color="#FF5C8A" stop-opacity="0.04"/>
  <stop offset="1" stop-color="#FF5C8A" stop-opacity="0"/></radialGradient>
<filter id="soft" x="-40%" y="-40%" width="180%" height="180%">
  <feDropShadow dx="0" dy="7" stdDeviation="11" flood-color="#7A4E9D" flood-opacity="0.22"/>
</filter>
"""

def artboard(title, s1, s2, cap1, cap2, dk1=False, dk2=False):
    body = (T(385, 34, title, 16, "#1a1a1a", 800, "middle")
            + phone(40, 60, f"clip-{s1[0]}", s1[1], dk1)
            + phone(400, 60, f"clip-{s2[0]}", s2[1], dk2)
            + T(205, 800, cap1, 13, "#555", 700, "middle")
            + T(565, 800, cap2, 13, "#555", 700, "middle"))
    d = "\n".join(defs)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">\n'
            f'<rect width="{W}" height="{H}" fill="#E9E9E6"/>\n'
            f'<defs>{GRADS}\n{d}</defs>\n{body}\n</svg>')

concepts = [
    ("design-a-m3-expressive", "CONCEPT A · Material 3 Expressive — 亮色 / 胶囊形 / 底部导航",
     ("a1", a_home()), ("a2", a_downloads()), False, False,
     "Screen 1 · 下载主页", "Screen 2 · 下载列表"),
    ("design-b-glass-dark", "CONCEPT B · Aurora Glass — 暗色玻璃 2.0 / 渐变发光 / 悬浮胶囊导航",
     ("b1", b_home()), ("b2", b_downloads()), True, True,
     "Screen 1 · 下载主页", "Screen 2 · 下载列表"),
    ("design-c-bento-calm", "CONCEPT C · Bento Calm — 结构化理性风 / 保留顶部标签（改动最小）",
     ("c1", c_home()), ("c2", c_downloads()), False, False,
     "Screen 1 · 下载主页", "Screen 2 · 下载列表"),
    ("design-b2-aurora-light", "CONCEPT B+ · Aurora Glass LIGHT — 亮色玻璃版 / 柔光斑 / 白磨砂浮卡",
     ("bl1", bl_home()), ("bl2", bl_downloads()), False, False,
     "Screen 1 · 下载主页", "Screen 2 · 下载列表"),
    ("design-s1-settings", "SETTINGS · 设置页 — Aurora Glass 双主题",
     ("s1d", scr_settings(False)), ("s1l", scr_settings(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s2-media-picker", "MEDIA PICKER · 下载前选择器 — Aurora Glass 双主题",
     ("s2d", scr_picker(False)), ("s2l", scr_picker(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s3-blog-detail", "BLOG DETAIL · 博客详情 — Aurora Glass 双主题",
     ("s3d", scr_blog(False)), ("s3l", scr_blog(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s4-onboarding", "ONBOARDING · 首次引导 — Aurora Glass 双主题",
     ("s4d", scr_onboarding(False)), ("s4l", scr_onboarding(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s5-settings-detail", "SETTINGS DETAIL · 设置子页（下载分类）— Aurora Glass 双主题",
     ("s5d", scr_settings_detail(False)), ("s5l", scr_settings_detail(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s6-about", "ABOUT · 关于页 — Aurora Glass 双主题",
     ("s6d", scr_about(False)), ("s6l", scr_about(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s7-media-viewer", "MEDIA VIEWER · 媒体查看器 — 沉浸式暗色",
     ("s7a", scr_viewer_image()), ("s7v", scr_viewer_video()), True, True,
     "IMAGE · 图片查看", "VIDEO · 视频播放"),
    ("design-s8-login", "TUMBLR LOGIN · 登录页 — Aurora Glass 双主题",
     ("s8d", scr_login(False)), ("s8l", scr_login(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
    ("design-s9-filter-dialog", "BLOG SCAN FILTER · 扫描类型对话框 — Aurora Glass 双主题",
     ("s9d", scr_filter(False)), ("s9l", scr_filter(True)), True, False,
     "DARK · 暗色", "LIGHT · 亮色"),
]

import os
# SVG output goes next to this script in svg/; convert to png with:
#   for f in svg/*.svg; do rsvg-convert -z 2 -o "png/$(basename "${f%.svg}").png" "$f"; done
outdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "svg")
os.makedirs(outdir, exist_ok=True)
for name, title, s1, s2, dk1, dk2, cap1, cap2 in concepts:
    defs.clear()
    svg = artboard(title, s1, s2, cap1, cap2, dk1, dk2)
    path = os.path.join(outdir, name + ".svg")
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)
    print("wrote", path, len(svg), "bytes")
