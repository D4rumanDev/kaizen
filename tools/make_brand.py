"""Genera icono (adaptativo y clásico) y banner de TV desde art/. Uso: python tools/make_brand.py

Proporciones (Android): icono adaptativo = lienzo de 108 dp (x densidad) con la zona segura en un círculo de 66 dp: el gato se ajusta al 60 % del lienzo (65 dp).
Icono clásico = 48 dp (mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192), el gato al 84 %; el redondo, el mismo recortado en círculo.
Logo del canal de inicio = cuadrado, 320 x 320 px (80 dp).
Banner de Android TV = 16:9, 320 x 180 px en xhdpi y con el nombre de la app dentro; aquí 640 x 360 (el doble, misma proporción).
"""
from PIL import Image, ImageDraw, ImageFilter
import sys
R = 'app/src/main/res/'
BG = (18, 18, 18, 255)

# El gato suelto con fondo transparente (art/icono.png), recortado a su silueta.
icon = Image.open('art/icono.png').convert('RGBA')
box = icon.split()[3].point(lambda v: 255 if v > 40 else 0).getbbox()
icon = icon.crop(box)

def fit(im, size):
    s = size / max(im.size)
    return im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)

def centred(canvas, im):
    canvas.alpha_composite(im, ((canvas.width - im.width) // 2, (canvas.height - im.height) // 2))
    return canvas

for d, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    fg = px * 9 // 4
    centred(Image.new('RGBA', (fg, fg)), fit(icon, fg * 0.60)).save(f'{R}mipmap-{d}/ic_launcher_foreground.png')
    sq = centred(Image.new('RGBA', (px, px), BG), fit(icon, px * 0.84))
    sq.save(f'{R}mipmap-{d}/ic_launcher.png')
    mask = Image.new('L', (px * 4, px * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
    rd = sq.copy(); rd.putalpha(mask.resize((px, px), Image.LANCZOS)); rd.save(f'{R}mipmap-{d}/ic_launcher_round.png')

# Logo del canal «Capítulos recientes» del inicio de Android TV (HomeChannels): cuadrado 1:1 (320 px = 80 dp a 4x), el gato sobre transparente al 92 %.
centred(Image.new('RGBA', (320, 320)), fit(icon, 320 * 0.92)).save(R + 'drawable-nodpi/ic_home_channel.png')

# Banner de TV 16:9: el cartel (hoy 16:9; si fuera más ancho, a todo el ancho) sobre su propio fondo desenfocado, sin recortar al gato ni el texto.
ban = Image.open('art/Kaizen bajo la luna de Japón.png').convert('RGB')
W, H = 640, 360
bg = ban.resize((W, H), Image.LANCZOS).filter(ImageFilter.GaussianBlur(18))
bg = Image.eval(bg, lambda v: int(v * 0.55))
fgb = ban.resize((W, round(W * ban.height / ban.width)), Image.LANCZOS)
bg.paste(fgb, (0, (H - fgb.height) // 2))
bg.save(R + 'drawable/tv_banner.webp', quality=92)
print('ok', box)
