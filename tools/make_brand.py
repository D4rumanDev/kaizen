"""Genera icono (adaptativo y clásico) y banner de TV desde art/. Uso: python tools/make_brand.py"""
from PIL import Image, ImageDraw, ImageFilter
import sys
R = 'app/src/main/res/'
BG = (18, 18, 18, 255)

sheet = Image.open('art/Icono Maneki-neko y banner Kaizen.png').convert('RGBA')
left = sheet.crop((0, 0, 440, sheet.height))
box = left.split()[3].point(lambda v: 255 if v > 40 else 0).getbbox()
icon = left.crop(box)

def fit(im, size):
    s = size / max(im.size)
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)

def centred(canvas, im):
    canvas.alpha_composite(im, ((canvas.width - im.width) // 2, (canvas.height - im.height) // 2))
    return canvas

for d, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    fg = px * 9 // 4
    centred(Image.new('RGBA', (fg, fg)), fit(icon, fg * 0.63)).save(f'{R}mipmap-{d}/ic_launcher_foreground.png')
    sq = centred(Image.new('RGBA', (px, px), BG), fit(icon, px * 0.84))
    sq.save(f'{R}mipmap-{d}/ic_launcher.png')
    mask = Image.new('L', (px * 4, px * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    rd = sq.copy(); rd.putalpha(mask.resize((px, px), Image.LANCZOS)); rd.save(f'{R}mipmap-{d}/ic_launcher_round.png')

# Banner de TV 16:9: el cartel (hoy 16:9; si fuera más ancho, a todo el ancho) sobre su propio fondo desenfocado, sin recortar al gato ni el texto.
ban = Image.open('art/Kaizen bajo la luna de Japón.png').convert('RGB')
W, H = 640, 360
bg = ban.resize((W, H), Image.LANCZOS).filter(ImageFilter.GaussianBlur(18))
bg = Image.eval(bg, lambda v: int(v * 0.55))
fgb = ban.resize((W, round(W * ban.height / ban.width)), Image.LANCZOS)
bg.paste(fgb, (0, (H - fgb.height) // 2))
bg.save(R + 'drawable/tv_banner.webp', quality=92)
print('ok', box)
