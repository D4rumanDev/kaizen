#!/usr/bin/env python3
"""
Prueba desde el PC la lógica de los extractores nuevos de Kaizen (VidHide, Filemoon, MixDrop, Savefile, Dood, VidGuard...) contra páginas reales de
capítulo: saca los enlaces de embed de cada web, aplica el mismo patrón que el extractor de la app y comprueba que el vídeo responde.
Los hosts se resuelven con DNS sobre HTTPS de Cloudflare (el DNS del PC no resuelve varios); solo lectura, no toca la TV.
No ejecuta ningún JavaScript de las páginas (es código ajeno): el empaquetado «p,a,c,k,e,d» se deshace con sustituciones de texto.

Uso: python tools/probe_servers.py [URL_DE_CAPITULO ...]
Sin argumentos usa los capítulos de ejemplo de DEFAULT_PAGES.
"""
import re
import subprocess
import sys

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
DEFAULT_PAGES = [
    "https://vww.animeflv.one/ver/tokyo-revengers-santen-sensou-hen-6",
    "https://animegratis.net/anime/tokyo-revengers-santen-sensou-hen/episodio-6",
    "https://jkanime.net/tokyo-revengers-santen-sensou-hen/6",
    "https://monoschinos.st/ver/to-aru-anbu-no-item-episodio-1",
]
HOSTS = {
    "vidhide": r"vidhide|filelions|earnvids", "filemoon": r"filemoon|byse", "mixdrop": r"mixdrop|mxdrop|mixdroop", "savefile": r"savefiles",
    "dood": r"dood|d0o0d|ds2play|do7go", "vidguard": r"vidguard|vgfplay|vembed|listeamed|bembed", "vidmoly": r"vidmoly",
    "uqload": r"uqload", "sibnet": r"sibnet\.ru", "sendvid": r"sendvid", "streamhub": r"streamhub",
}
PACKED = re.compile(r"""\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""", re.S)


def curl(url, referer=None, head=False, maxtime=25):
    cmd = ["curl", "-sL", "-m", str(maxtime), "--doh-url", "https://1.1.1.1/dns-query", "-A", UA, "-w", "\n%{http_code}"]
    if referer:
        cmd += ["-e", referer]
    if head:
        cmd += ["-r", "0-1023"]
    cmd.append(url)
    out = subprocess.run(cmd, capture_output=True).stdout.decode("utf-8", "replace")
    body, _, code = out.rpartition("\n")
    return code.strip(), body


def embeds(page_html):
    text = page_html.replace("\\/", "/").replace("&amp;", "&")
    found = {}
    for m in re.finditer(r"https?://[A-Za-z0-9.\-]+/[A-Za-z0-9./_?=&%\-]*", text):
        url = m.group(0)
        for name, pattern in HOSTS.items():
            if re.search(pattern, url.split("/")[2]):
                found.setdefault(name, set()).add(url.rstrip("'\"\\"))
    return found


def unknown_embeds(page_html):
    """Hosts de reproductores que la página ofrece (iframe, data-video, enlaces de «embed» o «/e/») y que ningún extractor reconoce."""
    text = page_html.replace("\\/", "/").replace("&amp;", "&")
    hosts = {}
    for m in re.finditer(r"(?:src|data-[a-z-]+|href|url)\s*[=:]\s*[\"'](https?://([A-Za-z0-9.\-]+)/[^\"'\s]*)", text):
        url, host = m.group(1), m.group(2)
        if not re.search(r"/(e|embed|v|d|f|embed-[^/]+)/|embed|player|\.php\?|stream", url, re.I):
            continue
        if any(re.search(pattern, host) for pattern in HOSTS.values()):
            continue
        hosts.setdefault(host, url)
    return hosts


def _base(n, radix):
    digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    out = ""
    while True:
        out = digits[n % radix] + out
        n //= radix
        if n == 0:
            return out


def unpack(script):
    """Deshace el empaquetado estándar (Dean Edwards) solo con sustituciones de texto; lo mismo que hace la app con Unpacker.unpackHtml."""
    m = PACKED.search(script)
    if not m:
        return script
    payload = m.group(1).replace("\\'", "'").replace("\\\\", "\\")
    radix, count, words = int(m.group(2)), int(m.group(3)), m.group(4).split("|")
    for i in range(count - 1, -1, -1):
        if i < len(words) and words[i]:
            payload = re.sub(r"\b" + _base(i, radix) + r"\b", lambda _m, w=words[i]: w, payload)
    return payload


def check_media(url, referer):
    code, body = curl(url, referer=referer, head=True, maxtime=20)
    return code, body[:12].replace("\n", " ")


def probe(name, url):
    code, html = curl(url)
    if code != "200":
        return f"página {code}"
    host = "/".join(url.split("/")[:3]) + "/"
    page = unpack(html)
    if name == "vidhide":
        m = re.search(r'"((?:https?:)?/[^"]*?m3u8[^"]*?)"', page)
        if not m:
            return "sin m3u8 tras desempaquetar"
        media = m.group(1)
        media = "https:" + media if media.startswith("//") else (media if media.startswith("http") else host.rstrip("/") + media)
        return "m3u8 -> %s %s" % check_media(media, host)
    if name == "mixdrop":
        w = page.split('Core.wurl="')[1].split('"')[0] if 'Core.wurl="' in page else ""
        if not w:
            return "sin MDCore.wurl"
        media = "https:" + w if w.startswith("//") else w
        return "mp4 -> %s %s" % check_media(media, "https://mixdrop.co/")
    if name == "savefile":
        m = re.search(r'\{file:"([^"]+m3u8[^"]*)"\}', page)
        return ("m3u8 -> %s %s" % check_media(m.group(1), host)) if m else "sin {file:...m3u8}"
    if name == "uqload":
        m = re.search(r'sources:\s*\["(http[^"]+)"', page)
        return ("mp4 -> %s %s" % check_media(m.group(1), host)) if m else "sin sources"
    if name == "vidmoly":
        m = re.search(r'sources: (.*?\]),', page)
        files = re.findall(r'file:"(.*?)"', m.group(1)) if m else []
        return ("m3u8 -> %s %s" % check_media(files[0], "https://vidmoly.to/")) if files else "sin sources/file"
    if name == "dood":
        return "pass_md5 encontrado (el enlace final se firma en la app)" if re.search(r"/pass_md5/[^']*", page) else "sin pass_md5"
    if name == "vidguard":
        return "script con eval presente (la app lo evalúa en Rhino)" if "eval" in page else "sin script eval"
    if name == "filemoon":
        parts = url.split("/")
        media_id = [s for s in parts[3:] if s][-1].split("?")[0]
        code2, details = curl(f"https://{parts[2]}/api/videos/{media_id}/embed/details", referer=url)
        m = re.search(r'"embed_frame_url"\s*:\s*"([^"]+)"', details.replace("\\/", "/"))
        return "API details %s, embed_frame_url=%s" % (code2, m.group(1).split("/")[2] if m else "ausente")
    return "sin comprobación específica"


def main():
    pages = sys.argv[1:] or DEFAULT_PAGES
    for page in pages:
        code, html = curl(page)
        print(f"\n== {page} [{code}]")
        if code != "200":
            continue
        for host, url in sorted(unknown_embeds(html).items()):
            print(f"  SIN EXTRACTOR  {host:34s} {url[:80]}")
        for name, urls in sorted(embeds(html).items()):
            for url in sorted(urls)[:2]:
                try:
                    print(f"  {name:9s} {url[:70]:70s} {probe(name, url)}")
                except Exception as e:  # noqa: BLE001 - es una sonda: se informa y se sigue
                    print(f"  {name:9s} {url[:70]:70s} ERROR {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
